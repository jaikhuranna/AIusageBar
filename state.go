// Persisted bridge state: paired devices, the alert event ring, and the
// threshold arming state that keeps one crossing from firing twice.
//
// Lives in $XDG_STATE_HOME/aiusagebar/state.json (0600 - it holds device
// tokens). Everything here is plain data; the monitor owns the locking.
package main

import (
	"encoding/json"
	"os"
	"path/filepath"
)

const (
	schemaVersion = 1
	maxEvents     = 200 // ring size; the watch only ever needs the recent tail
)

type device struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Token    string `json:"token"`
	PairedAt string `json:"paired_at"`
}

// eventSnap is the slice of the snapshot that explains why an event fired.
type eventSnap struct {
	Utilization int   `json:"utilization"`
	ResetsInSec int64 `json:"resets_in_sec"`
}

type event struct {
	ID        string    `json:"id"`
	DedupeKey string    `json:"dedupe_key"`
	Kind      string    `json:"kind"` // threshold_crossed | window_reset
	Window    string    `json:"window"`
	Threshold int       `json:"threshold,omitempty"`
	Severity  string    `json:"severity"` // info | warn | critical
	At        string    `json:"at"`
	Snapshot  eventSnap `json:"snapshot"`
}

type state struct {
	NextEvent int      `json:"next_event"`
	Events    []event  `json:"events"`
	Devices   []device `json:"devices"`
	// Fired maps "<window>:<threshold>" to the resets_at of the window instance
	// it fired for. A different resets_at means a new window, which re-arms.
	Fired map[string]string `json:"fired"`
	// LastReset maps a window to the resets_at last seen, so a change is a reset.
	LastReset map[string]string `json:"last_reset"`

	path string
}

func statePath() string {
	dir := os.Getenv("XDG_STATE_HOME")
	if dir == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return ""
		}
		dir = filepath.Join(home, ".local", "state")
	}
	return filepath.Join(dir, "aiusagebar", "state.json")
}

// loadState never fails into a broken bridge: an unreadable or corrupt state
// file yields an empty state, which only costs a repeated alert.
func loadState(path string) *state {
	st := &state{path: path, Fired: map[string]string{}, LastReset: map[string]string{}}
	data, err := os.ReadFile(path)
	if err != nil {
		return st
	}
	if err := json.Unmarshal(data, st); err != nil {
		return &state{path: path, Fired: map[string]string{}, LastReset: map[string]string{}}
	}
	st.path = path
	if st.Fired == nil {
		st.Fired = map[string]string{}
	}
	if st.LastReset == nil {
		st.LastReset = map[string]string{}
	}
	return st
}

// save writes atomically so a crash mid-write can't truncate the token store.
func (s *state) save() error {
	if s.path == "" {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

func (s *state) appendEvent(e event) event {
	s.NextEvent++
	e.ID = eventID(s.NextEvent)
	s.Events = append(s.Events, e)
	if len(s.Events) > maxEvents {
		s.Events = append([]event(nil), s.Events[len(s.Events)-maxEvents:]...)
	}
	return e
}

// eventID is zero-padded so lexical order matches issue order, which is what
// ?since= compares on.
func eventID(n int) string {
	const digits = "0123456789"
	buf := []byte("evt_000000000000")
	for i := len(buf) - 1; i >= 4 && n > 0; i-- {
		buf[i] = digits[n%10]
		n /= 10
	}
	return string(buf)
}
