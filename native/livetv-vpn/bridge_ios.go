//go:build ios

package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"sync"
)

// The iPhone and iPad Live TV VPN: the same engine as a static library inside the app. Swift hands
// the functions below to Kotlin; WireGuard stays on a private network stack and is reachable only as
// a password-protected proxy on 127.0.0.1.

var iosMu sync.Mutex
var iosNext int64
var iosTunnels = map[int64]*httpTunnel{}

func iosTunnel(id int64) *httpTunnel {
	iosMu.Lock()
	defer iosMu.Unlock()
	return iosTunnels[id]
}

// meowioVpnCheck returns NULL when the WireGuard file can be used, otherwise the reason (free it).
//
//export meowioVpnCheck
func meowioVpnCheck(config *C.char) *C.char {
	if _, err := parseWireGuard(C.GoString(config)); err != nil {
		return C.CString(err.Error())
	}
	return nil
}

// meowioVpnStart starts the tunnel and returns a handle (0 on failure).
//
//export meowioVpnStart
func meowioVpnStart(config *C.char, protectHTTPS C.int, token *C.char) C.longlong {
	if len(C.GoString(token)) < 32 {
		return 0
	}
	tunnel, err := startFromRequest(C.GoString(config), protectHTTPS != 0, C.GoString(token))
	if err != nil {
		return 0
	}
	iosMu.Lock()
	iosNext++
	id := iosNext
	iosTunnels[id] = tunnel
	iosMu.Unlock()
	return C.longlong(id)
}

//export meowioVpnPort
func meowioVpnPort(id C.longlong) C.int {
	if tunnel := iosTunnel(int64(id)); tunnel != nil {
		return C.int(tunnel.port())
	}
	return 0
}

//export meowioVpnStop
func meowioVpnStop(id C.longlong) {
	iosMu.Lock()
	tunnel := iosTunnels[int64(id)]
	delete(iosTunnels, int64(id))
	iosMu.Unlock()
	if tunnel != nil {
		tunnel.close()
	}
}

//export meowioVpnProbe
func meowioVpnProbe(id C.longlong) C.int {
	if tunnel := iosTunnel(int64(id)); tunnel != nil && tunnel.probe() {
		return 1
	}
	return 0
}

func main() {}
