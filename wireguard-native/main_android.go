//go:build android

// SPDX-License-Identifier: Apache-2.0
// Thin Android FD adapter around unmodified upstream wireguard-go.
package main

/*
#cgo LDFLAGS: -llog
#include <stdlib.h>
extern int noki_protect_socket(int fd);
*/
import "C"

import (
	"fmt"
	"strings"
	"sync"
	"unsafe"

	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
)

var tunnels = struct {
	sync.Mutex
	next    int
	devices map[int]*device.Device
}{next: 1, devices: make(map[int]*device.Device)}

var quietLogger = &device.Logger{
	Verbosef: func(string, ...any) {},
	Errorf:   func(string, ...any) {},
}

//export nokiGenerateKeyPair
func nokiGenerateKeyPair(out *C.uchar) C.int {
	if out == nil {
		return 0
	}
	private, public, err := newWireGuardKeyPair()
	defer clear(private[:])
	defer clear(public[:])
	if err != nil {
		return 0
	}
	bytes := unsafe.Slice((*byte)(unsafe.Pointer(out)), 64)
	copy(bytes[:32], private[:])
	copy(bytes[32:], public[:])
	return 1
}

//export nokiTurnOn
func nokiTurnOn(tunFD C.int, config *C.char) C.int {
	if tunFD < 0 || config == nil {
		if tunFD >= 0 {
			_ = unix.Close(int(tunFD))
		}
		return -1
	}
	wgTun, _, err := tun.CreateUnmonitoredTUNFromFD(int(tunFD))
	if err != nil {
		_ = unix.Close(int(tunFD))
		return -1
	}
	base := conn.NewStdNetBind()
	peek, ok := base.(conn.PeekLookAtSocketFd)
	if !ok {
		_ = wgTun.Close()
		return -1
	}
	bind := newProtectedBind(base, peek, func(fd int) bool {
		return C.noki_protect_socket(C.int(fd)) == 1
	})
	wg := device.NewDevice(wgTun, bind, quietLogger)
	if err := wg.IpcSet(C.GoString(config)); err != nil {
		wg.Close()
		return -1
	}
	if err := wg.Up(); err != nil {
		wg.Close()
		return -1
	}
	tunnels.Lock()
	defer tunnels.Unlock()
	handle := tunnels.next
	tunnels.next++
	tunnels.devices[handle] = wg
	return C.int(handle)
}

//export nokiTurnOff
func nokiTurnOff(handle C.int) {
	tunnels.Lock()
	wg := tunnels.devices[int(handle)]
	delete(tunnels.devices, int(handle))
	tunnels.Unlock()
	if wg != nil {
		wg.Close()
	}
}

//export nokiStats
func nokiStats(handle C.int) *C.char {
	tunnels.Lock()
	wg := tunnels.devices[int(handle)]
	if wg == nil {
		tunnels.Unlock()
		return nil
	}
	// IpcGet includes private_key. Keep the raw value inside Go and return only
	// the three counters needed by the Android runtime.
	raw, err := wg.IpcGet()
	tunnels.Unlock()
	if err != nil {
		return nil
	}
	var rx, tx, handshake string
	for _, line := range strings.Split(raw, "\n") {
		switch {
		case strings.HasPrefix(line, "rx_bytes="):
			rx = strings.TrimPrefix(line, "rx_bytes=")
		case strings.HasPrefix(line, "tx_bytes="):
			tx = strings.TrimPrefix(line, "tx_bytes=")
		case strings.HasPrefix(line, "last_handshake_time_sec="):
			handshake = strings.TrimPrefix(line, "last_handshake_time_sec=")
		}
	}
	if rx == "" || tx == "" || handshake == "" {
		return nil
	}
	return C.CString(fmt.Sprintf("rx_bytes=%s\ntx_bytes=%s\nlast_handshake_time_sec=%s\n",
		rx, tx, handshake))
}

func main() {}
