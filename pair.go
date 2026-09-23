// Client side of `aiusagebar -pair`: asks the already-running bridge on this
// machine for a short-lived pairing code over the control socket and prints it,
// so the code can be typed into the watch.
package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
)

// fetchPairCode mints a code through the running bridge's control socket.
func fetchPairCode() (code string, expiresIn int, err error) {
	resp, err := controlClient().Post("http://aiusagebar/pair/new", "application/json", strings.NewReader("{}"))
	if err != nil {
		return "", 0, fmt.Errorf("no bridge answering on %s - is aiusagebar running with -serve? (%w)", controlSocketPath(), err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", 0, fmt.Errorf("bridge said %s", resp.Status)
	}
	var body struct {
		Code      string `json:"code"`
		ExpiresIn int    `json:"expires_in_sec"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return "", 0, err
	}
	return body.Code, body.ExpiresIn, nil
}

func requestPairCode(addr string) error {
	code, expiresIn, err := fetchPairCode()
	if err != nil {
		return err
	}
	fmt.Printf("Pairing code: %s\n", code)
	fmt.Printf("Enter it on the watch within %d seconds. It works once.\n", expiresIn)
	if port := portOf(addr); strings.HasPrefix(port, ":") {
		for _, ip := range localIPs() {
			fmt.Printf("  bridge: http://%s%s\n", ip, port)
		}
	}
	return nil
}
