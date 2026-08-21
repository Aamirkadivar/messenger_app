package e2ee

import (
	"encoding/binary"
	"encoding/json"
	"errors"
)

// EM1 inner plaintext: magic "EM1\n" || u16be metaLen || JSON || payload.
var em1Magic = []byte{'E', 'M', '1', '\n'}

// FN1 per-device fan-out: magic "FN1\n" || u16be n || (u8 idLen || id || u32be blobLen || blob)*n
var fn1Magic = []byte{'F', 'N', '1', '\n'}

type EnvelopeMeta struct {
	FileName       string `json:"fn,omitempty"`
	ForwardedFrom  string `json:"fwd,omitempty"`
	DurationMs     int64  `json:"dur,omitempty"`
	FileSize       int64  `json:"sz,omitempty"`
	ThumbnailURL   string `json:"th,omitempty"`
	FileURL        string `json:"fu,omitempty"`
}

func WrapEM1(payload []byte, meta EnvelopeMeta) []byte {
	raw, _ := json.Marshal(meta)
	if len(raw) > 0xffff {
		return payload
	}
	out := make([]byte, 0, 6+len(raw)+len(payload))
	out = append(out, em1Magic...)
	out = append(out, byte(len(raw)>>8), byte(len(raw)))
	out = append(out, raw...)
	out = append(out, payload...)
	return out
}

func UnwrapEM1(data []byte) (payload []byte, meta EnvelopeMeta) {
	payload = data
	if len(data) < 6 || data[0] != 'E' || data[1] != 'M' || data[2] != '1' || data[3] != '\n' {
		return
	}
	n := int(data[4])<<8 | int(data[5])
	if n < 0 || len(data) < 6+n {
		return
	}
	if err := json.Unmarshal(data[6:6+n], &meta); err != nil {
		return data, EnvelopeMeta{}
	}
	return data[6+n:], meta
}

type FanoutPart struct {
	DeviceID string
	Blob     []byte
}

func WrapFN1(parts []FanoutPart) ([]byte, error) {
	if len(parts) == 0 {
		return nil, errors.New("empty fan-out")
	}
	if len(parts) > 0xffff {
		return nil, errors.New("too many devices")
	}
	out := append([]byte{}, fn1Magic...)
	out = append(out, byte(len(parts)>>8), byte(len(parts)))
	for _, p := range parts {
		id := []byte(p.DeviceID)
		if len(id) > 255 {
			return nil, errors.New("device id too long")
		}
		out = append(out, byte(len(id)))
		out = append(out, id...)
		var ln [4]byte
		binary.BigEndian.PutUint32(ln[:], uint32(len(p.Blob)))
		out = append(out, ln[:]...)
		out = append(out, p.Blob...)
	}
	return out, nil
}

func UnwrapFN1(data []byte) (parts []FanoutPart, ok bool) {
	if len(data) < 6 || data[0] != 'F' || data[1] != 'N' || data[2] != '1' || data[3] != '\n' {
		return nil, false
	}
	n := int(data[4])<<8 | int(data[5])
	off := 6
	for i := 0; i < n; i++ {
		if off >= len(data) {
			return nil, false
		}
		idLen := int(data[off])
		off++
		if off+idLen+4 > len(data) {
			return nil, false
		}
		id := string(data[off : off+idLen])
		off += idLen
		blobLen := int(binary.BigEndian.Uint32(data[off : off+4]))
		off += 4
		if blobLen < 0 || off+blobLen > len(data) {
			return nil, false
		}
		blob := data[off : off+blobLen]
		off += blobLen
		parts = append(parts, FanoutPart{DeviceID: id, Blob: blob})
	}
	return parts, true
}

func PickFN1(data []byte, deviceID string) (blob []byte, fanout bool) {
	parts, ok := UnwrapFN1(data)
	if !ok {
		return data, false
	}
	for _, p := range parts {
		if p.DeviceID == deviceID {
			return p.Blob, true
		}
	}
	return nil, true
}
