// The standalone server has no desktop, GTK, or tray dependencies.
package main

import (
	"aiusagebar/internal/bridge"
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := bridge.Run(ctx, os.Args[1:]); err != nil {
		log.Fatal(err)
	}
}
