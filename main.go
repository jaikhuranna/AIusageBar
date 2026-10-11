// AIusageBar is a Linux tray client of the standalone AIusage server.
package main

import (
	"aiusagebar/internal/tray"
	"log"
	"os"
)

func main() {
	if err := tray.Run(os.Args[1:]); err != nil {
		log.Fatal(err)
	}
}
