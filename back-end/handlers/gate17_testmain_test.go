package handlers

import (
	"os"
	"testing"
)

// TestMain supplies the secrets Gate 17 refuses to start without.
//
// config.Validate rejects an empty JWT_SECRET, REFRESH_PEPPER or
// RESPONSE_CACHE_KEY, and LoadConfig no longer provides defaults for them - the
// published default was itself the vulnerability. The suite therefore has to
// set them, and it sets FIXED values because LoadConfig is called per request:
// a per-call random secret would mint tokens no later call could verify.
//
// These are test values. They are not defaults, and nothing outside the test
// binary can reach them.
func TestMain(m *testing.M) {
	if os.Getenv("JWT_SECRET") == "" {
		_ = os.Setenv("JWT_SECRET", "gate17-test-jwt-secret-value-not-for-production-use")
	}
	if os.Getenv("REFRESH_PEPPER") == "" {
		_ = os.Setenv("REFRESH_PEPPER", "gate17-test-refresh-pepper-not-for-production-use")
	}
	if os.Getenv("RESPONSE_CACHE_KEY") == "" {
		// 32 bytes, base64.
		_ = os.Setenv("RESPONSE_CACHE_KEY", "Z2F0ZTE3LXRlc3QtcmVzcG9uc2UtY2FjaGUta2V5ISE=")
	}
	os.Exit(m.Run())
}
