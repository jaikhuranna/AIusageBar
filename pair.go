// Client side of `aiusagebar -pair`: asks the already-running bridge on this
// machine for a short-lived pairing code and prints it, so the code can be
// typed into the watch. Loopback only, by the bridge's own rule.
package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"
)

func requestPairCode(addr string) error {
	if addr == "" {
		addr = ":8765"
	}
	port := portOf(addr)
	if !strings.HasPrefix(port, ":") {
		port = ":8765" // addr wasn't host:port; fall back to the documented default
	}
	url := "http://127.0.0.1" + port + "/pair/new"
	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Post(url, "application/json", strings.NewReader("{}"))
	if err != nil {
		return fmt.Errorf("no bridge answering on %s - is aiusagebar running with -serve? (%w)", url, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("bridge said %s", resp.Status)
	}
	var body struct {
		Code      string `json:"code"`
		ExpiresIn int    `json:"expires_in_sec"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return err
	}
	fmt.Printf("Pairing code: %s\n", body.Code)
	fmt.Printf("Enter it on the watch within %d seconds. It works once.\n", body.ExpiresIn)
	for _, ip := range localIPs() {
		fmt.Printf("  bridge: http://%s%s\n", ip, port)
	}
	return nil
}
