package protocol

// Pool is the response of GET /pool?family=claude|codex: the pooled, effective
// limit across every account of a provider family, with recorded history and a
// projection. All percentages are in "account-percent": one account is 100, so
// a pool of two accounts has capacity_pct 200.
type Pool struct {
	Family string `json:"family"`
	// Accounts is how many accounts contribute data (0 when none do).
	Accounts    int     `json:"accounts"`
	CapacityPct float64 `json:"capacity_pct"`
	// RemainingPct is the weekly pool: the sum over accounts of 100 minus the
	// seven_day utilization.
	RemainingPct float64 `json:"remaining_pct"`
	// FiveHourRemainingPct is the same sum over the five_hour windows.
	FiveHourRemainingPct float64 `json:"five_hour_remaining_pct"`
	// EffectiveRemainingPct is what can be used right now: per account the
	// smaller of its two windows' remaining, summed.
	EffectiveRemainingPct float64 `json:"effective_remaining_pct"`
	// BurnPctPerHour is how fast RemainingPct drained over the last three
	// hours (never negative); null when there is too little history.
	BurnPctPerHour *float64   `json:"burn_pct_per_hour"`
	NextReset      *PoolReset `json:"next_reset"`
	// FirstEmptyAt and PoolEmptyAt are RFC 3339 times, or null when the event
	// is not projected within seven days.
	FirstEmptyAt *string      `json:"first_empty_at"`
	PoolEmptyAt  *string      `json:"pool_empty_at"`
	Members      []PoolMember `json:"members"`
	History      []PoolSample `json:"history"`
	Projection   []PoolPoint  `json:"projection"`
	GeneratedAt  string       `json:"generated_at"`
	// SourceError is set when no account in the family has data.
	SourceError string `json:"source_error"`
}

// PoolReset is the soonest weekly reset among the accounts and what it adds back.
type PoolReset struct {
	At      string  `json:"at"`
	GainPct float64 `json:"gain_pct"`
}

// PoolMember is one account in the pool. Provider is the name /usage accepts
// (claude, claude2, codex).
type PoolMember struct {
	Provider    string  `json:"provider"`
	Account     string  `json:"account"`
	FiveHour    *Window `json:"five_hour"`
	SevenDay    *Window `json:"seven_day"`
	SourceError string  `json:"source_error"`
}

// PoolSample is one recorded point of the pool. T is Unix seconds.
type PoolSample struct {
	T                     int64   `json:"t"`
	RemainingPct          float64 `json:"remaining_pct"`
	FiveHourRemainingPct  float64 `json:"five_hour_remaining_pct"`
	EffectiveRemainingPct float64 `json:"effective_remaining_pct"`
	// FiveHourRemaining is each account's own five-hour remaining (0-100),
	// keyed by provider (claude, claude2, codex), and FiveHourResetsAt when
	// that window resets (Unix seconds), so clients can draw per-account
	// session lines and mark past resets. Only accounts that report a
	// five_hour window are present; both are omitted on older samples.
	FiveHourRemaining map[string]float64 `json:"five_hour_remaining,omitempty"`
	FiveHourResetsAt  map[string]int64   `json:"five_hour_resets_at,omitempty"`
}

// PoolPoint is one projected point of RemainingPct. A weekly reset shows up as
// two points with the same T: the value just before it, then the value after
// it with Reset true.
type PoolPoint struct {
	T            int64   `json:"t"`
	RemainingPct float64 `json:"remaining_pct"`
	Reset        bool    `json:"reset"`
}
