// language: Go, file: backend/main.go
// vivo WireLink backend — WebSocket broker between keepers and admins.
package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

var (
	adminPassword string
	adminToken    string
)

var upgrader = websocket.Upgrader{
	CheckOrigin:     func(r *http.Request) bool { return true },
	ReadBufferSize:  1 << 22, // 4 MB — large enough for base64 photos
	WriteBufferSize: 1 << 22,
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
	keepers = map[string]*Client{} // deviceId -> keeper
	admins  = map[string]*Client{} // sessionId -> admin
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
	log.Fatal(http.ListenAndServe(":"+port, mux))
}

// pingHandler answers HEAD/GET — used by the keep-alive cron.
func pingHandler(w http.ResponseWriter, _ *http.Request) {
	w.WriteHeader(http.StatusOK)
}

// loginHandler validates the shared password and returns the derived token.
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

// keeperHandler accepts a keeper device and registers it by deviceId.
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
		old.Close() // evict stale socket for same device
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
		// keeper -> admin: hello, result, anything except command
		if m.Type == "result" || m.Type == "hello" {
			mu.RLock()
			for _, a := range admins {
				_ = a.Send(m)
			}
			mu.RUnlock()
		}
	}
}

// adminHandler authenticates by token, then relays commands to keepers.
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

	// push current keeper list immediately
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

		// pick target keeper
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

func mustJSON(v any) json.RawMessage {
	b, _ := json.Marshal(v)
	return b
}
