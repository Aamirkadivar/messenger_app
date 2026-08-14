package handlers

import (
	"sync"
	"time"
)

const (
	maxLoginFails = 8
	loginLockFor  = 5 * time.Minute
)

type loginFailState struct {
	n         int
	lockUntil time.Time
}

var (
	loginMu    sync.Mutex
	loginFails = map[string]loginFailState{}
)

func loginLocked(email string) bool {
	loginMu.Lock()
	defer loginMu.Unlock()
	st, ok := loginFails[email]
	if !ok {
		return false
	}
	if time.Now().Before(st.lockUntil) {
		return true
	}
	if !st.lockUntil.IsZero() && time.Now().After(st.lockUntil) {
		delete(loginFails, email)
	}
	return false
}

func noteLoginFailure(email string) {
	loginMu.Lock()
	defer loginMu.Unlock()
	st := loginFails[email]
	st.n++
	if st.n >= maxLoginFails {
		st.lockUntil = time.Now().Add(loginLockFor)
	}
	loginFails[email] = st
}

func noteLoginSuccess(email string) {
	loginMu.Lock()
	delete(loginFails, email)
	loginMu.Unlock()
}
