package main

import (
	"encoding/base64"
	"encoding/hex"
	"errors"
	"net"
	"net/netip"
	"strconv"
	"strings"
)

// A WireGuard profile as the Live TV VPN needs it, read from the provider's .conf file. The
// phone and TV apps get the same checks from the official Android parser; the desktop helper
// has no such library, so the same rules are applied here. Error texts never include key material.
type wgProfile struct {
	privateKey   string
	addresses    []string
	dns          []string
	mtu          int
	publicKey    string
	presharedKey string
	endpoint     string
	allowedIPs   []string
	keepalive    int
}

var errWgFormat = errors.New("WireGuard configuration is invalid.")

func parseWireGuard(text string) (*wgProfile, error) {
	profile := &wgProfile{mtu: 1280}
	section := ""
	peers := 0
	for _, raw := range strings.Split(text, "\n") {
		line := raw
		if i := strings.IndexByte(line, '#'); i >= 0 {
			line = line[:i]
		}
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			section = strings.ToLower(strings.TrimSpace(line[1 : len(line)-1]))
			if section == "peer" {
				peers++
			} else if section != "interface" {
				return nil, errWgFormat
			}
			continue
		}
		eq := strings.IndexByte(line, '=')
		if eq < 0 || section == "" {
			return nil, errWgFormat
		}
		key := strings.ToLower(strings.TrimSpace(line[:eq]))
		value := strings.TrimSpace(line[eq+1:])
		switch section {
		case "interface":
			switch key {
			case "privatekey":
				profile.privateKey = value
			case "address":
				profile.addresses = append(profile.addresses, splitList(value)...)
			case "dns":
				profile.dns = append(profile.dns, splitList(value)...)
			case "mtu":
				n, err := strconv.Atoi(value)
				if err != nil || n < 1280 || n > 9000 {
					return nil, errWgFormat
				}
				profile.mtu = n
			case "listenport", "table", "preup", "postup", "predown", "postdown", "saveconfig", "fwmark":
			default:
				return nil, errWgFormat
			}
		case "peer":
			if peers > 1 {
				continue
			}
			switch key {
			case "publickey":
				profile.publicKey = value
			case "presharedkey":
				profile.presharedKey = value
			case "endpoint":
				profile.endpoint = value
			case "allowedips":
				profile.allowedIPs = append(profile.allowedIPs, splitList(value)...)
			case "persistentkeepalive":
				if value == "off" {
					break
				}
				n, err := strconv.Atoi(value)
				if err != nil || n < 0 || n > 65535 {
					return nil, errWgFormat
				}
				profile.keepalive = n
			default:
				return nil, errWgFormat
			}
		}
	}
	if err := profile.validate(peers); err != nil {
		return nil, err
	}
	return profile, nil
}

func splitList(value string) []string {
	var out []string
	for _, part := range strings.Split(value, ",") {
		if part = strings.TrimSpace(part); part != "" {
			out = append(out, part)
		}
	}
	return out
}

func decodeKey(value string) (string, bool) {
	raw, err := base64.StdEncoding.DecodeString(value)
	if err != nil || len(raw) != 32 {
		return "", false
	}
	return hex.EncodeToString(raw), true
}

// The Live TV VPN's requirements, as in NuvioPlusIPTV: one peer with an endpoint, DNS server
// addresses, and default routes for every IP family the profile uses.
func (p *wgProfile) validate(peers int) error {
	if _, ok := decodeKey(p.privateKey); !ok {
		return errWgFormat
	}
	if _, ok := decodeKey(p.publicKey); !ok {
		return errWgFormat
	}
	if p.presharedKey != "" {
		if _, ok := decodeKey(p.presharedKey); !ok {
			return errWgFormat
		}
	}
	if len(p.addresses) == 0 {
		return errors.New("WireGuard profiles require an interface Address.")
	}
	if peers != 1 || p.endpoint == "" {
		return errors.New("WireGuard profiles require exactly one remote VPN peer with an Endpoint.")
	}
	if _, _, err := net.SplitHostPort(p.endpoint); err != nil {
		return errWgFormat
	}
	if len(p.dns) == 0 {
		return errors.New("WireGuard profiles require at least one DNS server IP address.")
	}
	v4, v6, defaultV4, defaultV6 := false, false, false, false
	mark := func(a netip.Addr) {
		if a.Is4() || a.Is4In6() {
			v4 = true
		} else {
			v6 = true
		}
	}
	for _, a := range p.addresses {
		if prefix, err := netip.ParsePrefix(a); err == nil {
			mark(prefix.Addr())
			continue
		}
		addr, err := netip.ParseAddr(a)
		if err != nil {
			return errWgFormat
		}
		mark(addr)
	}
	for _, d := range p.dns {
		addr, err := netip.ParseAddr(d)
		if err != nil {
			return errors.New("The Live TV VPN does not support DNS search domains. Use a profile with DNS server addresses only.")
		}
		mark(addr)
	}
	for _, a := range p.allowedIPs {
		prefix, err := netip.ParsePrefix(a)
		if err != nil {
			return errWgFormat
		}
		if prefix.Addr().Is4() {
			v4 = true
			if prefix.Bits() == 0 {
				defaultV4 = true
			}
		} else {
			v6 = true
			if prefix.Bits() == 0 {
				defaultV6 = true
			}
		}
	}
	if (!defaultV4 && !defaultV6) || (v4 && !defaultV4) || (v6 && !defaultV6) {
		return errors.New("WireGuard profiles require default AllowedIPs routes for every configured IP family (0.0.0.0/0 for IPv4 and ::/0 for IPv6).")
	}
	return nil
}

// The WireGuard userspace (UAPI) text for this profile. The endpoint name is resolved here.
func (p *wgProfile) uapi() (string, error) {
	priv, _ := decodeKey(p.privateKey)
	pub, _ := decodeKey(p.publicKey)
	host, port, err := net.SplitHostPort(p.endpoint)
	if err != nil {
		return "", errWgFormat
	}
	address := host
	if _, err := netip.ParseAddr(host); err != nil {
		ips, err := net.LookupIP(host)
		if err != nil || len(ips) == 0 {
			return "", errors.New("The WireGuard endpoint could not be found.")
		}
		chosen := ips[0]
		for _, ip := range ips {
			if ip.To4() != nil {
				chosen = ip
				break
			}
		}
		address = chosen.String()
	}
	var b strings.Builder
	b.WriteString("private_key=" + priv + "\n")
	b.WriteString("public_key=" + pub + "\n")
	if p.presharedKey != "" {
		psk, _ := decodeKey(p.presharedKey)
		b.WriteString("preshared_key=" + psk + "\n")
	}
	b.WriteString("endpoint=" + net.JoinHostPort(address, port) + "\n")
	if p.keepalive > 0 {
		b.WriteString("persistent_keepalive_interval=" + strconv.Itoa(p.keepalive) + "\n")
	}
	b.WriteString("replace_allowed_ips=true\n")
	for _, a := range p.allowedIPs {
		b.WriteString("allowed_ip=" + a + "\n")
	}
	return b.String(), nil
}

// The addresses without their prefix lengths, as the engine takes them.
func (p *wgProfile) plainAddresses() []string {
	out := make([]string, 0, len(p.addresses))
	for _, a := range p.addresses {
		if i := strings.IndexByte(a, '/'); i >= 0 {
			a = a[:i]
		}
		out = append(out, a)
	}
	return out
}
