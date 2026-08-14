package handlers

import (
	"reflect"
	"testing"
)

func TestTurnAdvertiseHosts(t *testing.T) {
	cases := []struct {
		cfg, req string
		want     []string
	}{
		{"localhost", "192.168.1.10", []string{"192.168.1.10"}},
		{"127.0.0.1", "10.0.0.5", []string{"10.0.0.5"}},
		{"turn.example.com", "192.168.1.10", []string{"turn.example.com", "192.168.1.10"}},
		{"localhost", "localhost", []string{"localhost"}},
		{"", "203.0.113.4", []string{"203.0.113.4"}},
	}
	for _, tc := range cases {
		got := turnAdvertiseHosts(tc.cfg, tc.req)
		if !reflect.DeepEqual(got, tc.want) {
			t.Fatalf("cfg=%q req=%q got %v want %v", tc.cfg, tc.req, got, tc.want)
		}
	}
}

func TestIsLoopbackHost(t *testing.T) {
	if !isLoopbackHost("localhost") || !isLoopbackHost("127.0.0.1") || !isLoopbackHost("::1") {
		t.Fatal("expected loopback")
	}
	if isLoopbackHost("192.168.1.1") || isLoopbackHost("example.com") {
		t.Fatal("expected non-loopback")
	}
}
