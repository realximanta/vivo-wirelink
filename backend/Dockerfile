# language: dockerfile, file: backend/Dockerfile
# Multi-stage build: compile in golang, ship only the binary.

# ---------- build stage ----------
FROM golang:1.22-alpine AS build

WORKDIR /src

# copy module files first for layer caching
COPY go.mod go.sum* ./
RUN go mod download || true

# copy sources and build a static binary
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/server .

# ---------- runtime stage ----------
FROM alpine:3.20

# non-root user
RUN adduser -D -u 10001 app

# ca-certificates for outbound TLS (if we ever talk to Cloudflare D1)
RUN apk add --no-cache ca-certificates

USER app
WORKDIR /home/app

COPY --from=build /out/server /home/app/server

ENV PORT=8080
EXPOSE 8080

CMD ["/home/app/server"]
