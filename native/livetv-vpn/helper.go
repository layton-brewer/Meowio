//go:build !android

package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strings"
)

// The desktop (Mac and Windows) Live TV VPN: a small helper program the app starts. It runs the
// same WireGuard engine as the phone and TV apps on a private network stack (no system VPN, no
// driver, no administrator rights) and answers only on a password-protected proxy on 127.0.0.1.
//
//	meowio-vpn check   reads a WireGuard file on stdin; prints "ok" or the reason it cannot be used.
//	meowio-vpn run     reads {"config","protectHttps","token"} on one stdin line, starts the tunnel,
//	                   prints {"port":N}, then answers "probe" and "egress" lines until stdin closes.
func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: meowio-vpn check|run")
		os.Exit(2)
	}
	switch os.Args[1] {
	case "check":
		text, _ := io.ReadAll(io.LimitReader(os.Stdin, 1<<20))
		if _, err := parseWireGuard(string(text)); err != nil {
			fmt.Println(err.Error())
			os.Exit(1)
		}
		fmt.Println("ok")
	case "run":
		os.Exit(runHelper(os.Stdin, os.Stdout))
	default:
		os.Exit(2)
	}
}

type helperRequest struct {
	Config       string `json:"config"`
	ProtectHTTPS bool   `json:"protectHttps"`
	Token        string `json:"token"`
}

func fail(out io.Writer, reason string) int {
	_ = json.NewEncoder(out).Encode(map[string]string{"error": reason})
	return 1
}

func runHelper(in io.Reader, out io.Writer) int {
	reader := bufio.NewReaderSize(in, 1<<20)
	line, err := reader.ReadString('\n')
	if err != nil && line == "" {
		return fail(out, "no request")
	}
	var request helperRequest
	if json.Unmarshal([]byte(line), &request) != nil {
		return fail(out, "bad request")
	}
	profile, err := parseWireGuard(request.Config)
	if err != nil {
		return fail(out, err.Error())
	}
	uapi, err := profile.uapi()
	if err != nil {
		return fail(out, err.Error())
	}
	encoded, _ := json.Marshal(tunnelInput{UAPI: uapi, Addresses: profile.plainAddresses(), DNS: profile.dns,
		MTU: profile.mtu, Token: request.Token, TunnelHTTPS: request.ProtectHTTPS})
	tunnel, err := newHTTPTunnel(string(encoded))
	if err != nil {
		return fail(out, "The Live TV VPN could not connect.")
	}
	defer tunnel.close()
	_ = json.NewEncoder(out).Encode(map[string]int{"port": tunnel.port()})
	for {
		command, err := reader.ReadString('\n')
		switch strings.TrimSpace(command) {
		case "probe":
			if tunnel.probe() {
				fmt.Fprintln(out, "1")
			} else {
				fmt.Fprintln(out, "0")
			}
		case "egress":
			fmt.Fprintln(out, tunnel.egress())
		case "quit":
			return 0
		}
		if err != nil {
			return 0
		}
	}
}
