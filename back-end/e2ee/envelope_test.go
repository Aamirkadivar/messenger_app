package e2ee

import (
	"bytes"
	"encoding/hex"
	"testing"
)

func TestEM1RoundTrip(t *testing.T) {
	payload := []byte("hello")
	wrapped := WrapEM1(payload, EnvelopeMeta{FileName: "a.bin", DurationMs: 1200, FileSize: 9, ForwardedFrom: "Ada"})
	got, meta := UnwrapEM1(wrapped)
	if !bytes.Equal(got, payload) {
		t.Fatalf("payload %q", got)
	}
	if meta.FileName != "a.bin" || meta.DurationMs != 1200 || meta.FileSize != 9 || meta.ForwardedFrom != "Ada" {
		t.Fatalf("meta %+v", meta)
	}
	legacy := []byte("raw-old")
	p2, m2 := UnwrapEM1(legacy)
	if !bytes.Equal(p2, legacy) || m2.FileName != "" {
		t.Fatal("legacy unwrap")
	}
}

func TestFN1Pick(t *testing.T) {
	wire, err := WrapFN1([]FanoutPart{
		{DeviceID: "phone", Blob: []byte{1, 2, 3}},
		{DeviceID: "desk", Blob: []byte{9}},
	})
	if err != nil {
		t.Fatal(err)
	}
	b, ok := PickFN1(wire, "desk")
	if !ok || !bytes.Equal(b, []byte{9}) {
		t.Fatalf("pick desk %v %v", ok, b)
	}
	missing, ok := PickFN1(wire, "tablet")
	if !ok || missing != nil {
		t.Fatal("missing device should be fanout with nil blob")
	}
	raw := []byte{0xAA, 0xBB}
	legacy, fanout := PickFN1(raw, "desk")
	if fanout || !bytes.Equal(legacy, raw) {
		t.Fatal("non-fanout")
	}
}

func TestVectorEM1FN1(t *testing.T) {
	inner := WrapEM1([]byte("hi"), EnvelopeMeta{FileName: "x.txt", DurationMs: 1})
	fan, err := WrapFN1([]FanoutPart{{DeviceID: "devA", Blob: inner}})
	if err != nil {
		t.Fatal(err)
	}
	writeOrCheckJSON(t, "wire-em1-fn1.json", map[string]any{
		"em1":     "EM1\\n || u16be metaLen || json || payload",
		"fn1":     "FN1\\n || u16be n || (u8 idLen || id || u32be blobLen || blob)*n",
		"em1_hex": hex.EncodeToString(inner),
		"fn1_hex": hex.EncodeToString(fan),
	})
}
