package handlers

import "testing"

func TestLoginThrottleLocksAfterFails(t *testing.T) {
	email := "throttle-test@example.com"
	noteLoginSuccess(email)
	if loginLocked(email) {
		t.Fatal("fresh email locked")
	}
	for i := 0; i < maxLoginFails-1; i++ {
		noteLoginFailure(email)
		if loginLocked(email) {
			t.Fatalf("locked too early at %d", i+1)
		}
	}
	noteLoginFailure(email)
	if !loginLocked(email) {
		t.Fatal("expected lock")
	}
	noteLoginSuccess(email)
	if loginLocked(email) {
		t.Fatal("success should clear lock")
	}
}
