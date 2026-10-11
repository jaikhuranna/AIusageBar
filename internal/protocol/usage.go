// Package protocol defines the usage API shared by the server and its clients.
package protocol

import "encoding/json"

// UnmarshalJSON preserves schema-1 defaults from bridges predating provider selection.
func (s *Snapshot) UnmarshalJSON(data []byte) error {
	type plain Snapshot
	w := struct {
		*plain
		CostAvailable *bool `json:"cost_available"`
	}{plain: (*plain)(s)}
	if err := json.Unmarshal(data, &w); err != nil {
		return err
	}
	if s.Provider == "" {
		s.Provider = "claude"
	}
	s.CostAvailable = s.Provider == "claude"
	if w.CostAvailable != nil {
		s.CostAvailable = *w.CostAvailable
	}
	return nil
}

// Window is one plan-limit window as sent over the wire. Remaining is
// included so clients don't have to know the 100-utilization convention.
type Window struct {
	WindowMinutes int    `json:"window_minutes,omitempty"`
	Utilization   int    `json:"utilization"`
	Remaining     int    `json:"remaining"`
	ResetsAt      string `json:"resets_at,omitempty"`
	ResetsInSec   int64  `json:"resets_in_sec"`
}

type Snapshot struct {
	Provider      string  `json:"provider"`
	SourceError   string  `json:"source_error,omitempty"`
	CostAvailable bool    `json:"cost_available"`
	Schema        int     `json:"schema"`
	FiveHour      *Window `json:"five_hour"`
	SevenDay      *Window `json:"seven_day"`
	CostToday     float64 `json:"cost_today"`
	CostSession   float64 `json:"cost_session"`
	CostWeek      float64 `json:"cost_week"`
	GeneratedAt   string  `json:"generated_at"`
	// CacheFetchedAt is when the provider last supplied these limits: the real
	// age of the numbers, as opposed to when the bridge re-read them.
	CacheFetchedAt string `json:"cache_fetched_at,omitempty"`
	Host           string `json:"host"`
}
