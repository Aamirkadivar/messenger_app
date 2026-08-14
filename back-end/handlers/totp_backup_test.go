package handlers

import (
	"encoding/json"
	"testing"

	"golang.org/x/crypto/bcrypt"
	"messenger-app/models"
)

func TestNormalizeBackupCode(t *testing.T) {
	if normalizeBackupCode("ab12-cd34") != "AB12CD34" {
		t.Fatal(normalizeBackupCode("ab12-cd34"))
	}
}

func TestConsumeBackupCode(t *testing.T) {
	hash, err := bcrypt.GenerateFromPassword([]byte("ABCD2345"), bcrypt.MinCost)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := json.Marshal([]string{string(hash)})
	u := &models.User{TotpBackupHashes: string(b)}
	if consumeBackupCode(u, "wrong-code") {
		t.Fatal("bad code")
	}
	if !consumeBackupCode(u, "ABCD-2345") {
		t.Fatal("good code")
	}
	if consumeBackupCode(u, "ABCD-2345") {
		t.Fatal("one-time")
	}
}
