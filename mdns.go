// mDNS/DNS-SD advertisement, so the watch can find the bridge without anyone
// typing an IP address on a 1.2" screen (wearos/DESIGN.md §5).
//
// This shells out to avahi-publish-service rather than pulling in a zeroconf
// library: the target is a Linux desktop that already runs avahi-daemon, and a
// subprocess we can start and kill is less machinery than a second mDNS
// responder fighting avahi for port 5353. If avahi isn't there, the bridge
// still works - discovery just degrades to typing the host by hand.
package main

import (
	"log"
	"os/exec"
	"strconv"
	"strings"
)

const mdnsService = "_aiusage._tcp"

// advertise publishes the bridge until the returned stop func is called.
// The auth TXT record reflects the shared-secret flag only; whether a client
// actually needs a token can change at runtime (pairing closes the bridge), so
// clients confirm with GET /healthz rather than trusting this record.
func advertise(addr, sharedToken string) (stop func()) {
	port := strings.TrimPrefix(portOf(addr), ":")
	if _, err := strconv.Atoi(port); err != nil {
		log.Printf("mdns: can't work out a port from %q, not advertising", addr)
		return func() {}
	}
	bin, err := exec.LookPath("avahi-publish-service")
	if err != nil {
		log.Printf("mdns: avahi-publish-service not found, not advertising (%v)", err)
		return func() {}
	}
	auth := "none"
	if sharedToken != "" {
		auth = "token"
	}
	name := "AIusageBar on " + hostname()
	cmd := exec.Command(bin, name, mdnsService, port,
		"schema="+strconv.Itoa(schemaVersion),
		"host="+hostname(),
		"path=/usage",
		"auth="+auth,
	)
	if err := cmd.Start(); err != nil {
		log.Printf("mdns: %v", err)
		return func() {}
	}
	log.Printf("mdns: advertising %q as %s on port %s", name, mdnsService, port)
	done := make(chan struct{})
	go func() {
		err := cmd.Wait()
		select {
		case <-done: // we killed it
		default:
			log.Printf("mdns: advertisement stopped: %v", err)
		}
	}()
	return func() {
		close(done)
		if cmd.Process != nil {
			cmd.Process.Kill()
		}
	}
}
