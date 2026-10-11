.PHONY: build server tray test

build: server tray

server:
	CGO_ENABLED=0 go build -trimpath -o aiusage-server ./cmd/aiusage-server

tray:
	go build -trimpath -o aiusagebar .

test:
	go test -race ./...
