package main

import (
	"encoding/json"
	"errors"
)

// Starts the tunnel for a WireGuard file: the proxy on 127.0.0.1 is protected by [token]. Used by the
// desktop helper and the iPhone library; the Android build gets its settings from the app instead.
func startFromRequest(config string, protectHTTPS bool, token string) (*httpTunnel, error) {
	profile, err := parseWireGuard(config)
	if err != nil {
		return nil, err
	}
	uapi, err := profile.uapi()
	if err != nil {
		return nil, err
	}
	encoded, _ := json.Marshal(tunnelInput{UAPI: uapi, Addresses: profile.plainAddresses(), DNS: profile.dns,
		MTU: profile.mtu, Token: token, TunnelHTTPS: protectHTTPS})
	tunnel, err := newHTTPTunnel(string(encoded))
	if err != nil {
		return nil, errors.New("The Live TV VPN could not connect.")
	}
	return tunnel, nil
}
