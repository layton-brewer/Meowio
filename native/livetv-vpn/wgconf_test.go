package main

import (
	"strings"
	"testing"
)

const sampleKey = "YEocP0e1fBhbOI9tQ1fAcIv7fG1nMNqZkxPYo7n2e0A="

func sample(extra string) string {
	return "[Interface]\nPrivateKey = " + sampleKey + "\nAddress = 10.2.0.2/32\nDNS = 10.2.0.1\n\n[Peer]\nPublicKey = " +
		sampleKey + "\nAllowedIPs = 0.0.0.0/0\nEndpoint = 203.0.113.5:51820\nPersistentKeepalive = 25\n" + extra
}

func TestParseWireGuardAccepts(t *testing.T) {
	p, err := parseWireGuard(sample(""))
	if err != nil {
		t.Fatal(err)
	}
	u, err := p.uapi()
	if err != nil || !strings.Contains(u, "endpoint=203.0.113.5:51820") || !strings.Contains(u, "allowed_ip=0.0.0.0/0") ||
		!strings.Contains(u, "persistent_keepalive_interval=25") {
		t.Fatalf("uapi %q %v", u, err)
	}
	if got := p.plainAddresses(); len(got) != 1 || got[0] != "10.2.0.2" {
		t.Fatal(got)
	}
}

func TestParseWireGuardRejects(t *testing.T) {
	for name, text := range map[string]string{
		"two peers":   sample("[Peer]\nPublicKey = " + sampleKey + "\nAllowedIPs = 0.0.0.0/0\nEndpoint = 1.2.3.4:1\n"),
		"no dns":      strings.Replace(sample(""), "DNS = 10.2.0.1\n", "", 1),
		"search dns":  strings.Replace(sample(""), "DNS = 10.2.0.1", "DNS = 10.2.0.1, example.com", 1),
		"split route": strings.Replace(sample(""), "0.0.0.0/0", "10.0.0.0/8", 1),
		"bad key":     strings.Replace(sample(""), sampleKey, "nope", 1),
		"no endpoint": strings.Replace(sample(""), "Endpoint = 203.0.113.5:51820\n", "", 1),
		"garbage":     "hello",
	} {
		if _, err := parseWireGuard(text); err == nil {
			t.Errorf("%s accepted", name)
		}
	}
	if _, err := parseWireGuard(strings.Replace(sample(""), sampleKey, "nope", 1)); err != nil && strings.Contains(err.Error(), "nope") {
		t.Fatal("error leaks the key")
	}
}
