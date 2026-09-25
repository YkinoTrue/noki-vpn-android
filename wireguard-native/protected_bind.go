package main

import (
	"fmt"

	"golang.zx2c4.com/wireguard/conn"
)

// protectedBind guards every socket created by WireGuard, including after
// network rebind. A failed Android VpnService.protect closes the new bind.
type protectedBind struct {
	conn.Bind
	sockets conn.PeekLookAtSocketFd
	protect func(int) bool
}

func newProtectedBind(bind conn.Bind, sockets conn.PeekLookAtSocketFd, protect func(int) bool) *protectedBind {
	return &protectedBind{Bind: bind, sockets: sockets, protect: protect}
}

func (b *protectedBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	if b.protect == nil {
		return nil, 0, fmt.Errorf("WireGuard socket protection unavailable")
	}
	receivers, actualPort, err := b.Bind.Open(port)
	if err != nil {
		return nil, 0, err
	}
	protected := 0
	for _, peek := range []func() (int, error){b.sockets.PeekLookAtSocketFd4, b.sockets.PeekLookAtSocketFd6} {
		fd, peekErr := inspectSocket(peek)
		if peekErr != nil || fd < 0 {
			continue
		}
		if !b.protect(fd) {
			_ = b.Bind.Close()
			return nil, 0, fmt.Errorf("WireGuard UDP socket was not protected")
		}
		protected++
	}
	// StdNetBind may have opened only one address family. The number of
	// successful inspections must still match every receiver it opened.
	if protected != len(receivers) || protected == 0 {
		_ = b.Bind.Close()
		return nil, 0, fmt.Errorf("WireGuard UDP socket inspection was incomplete")
	}
	return receivers, actualPort, nil
}

func inspectSocket(peek func() (int, error)) (fd int, err error) {
	defer func() {
		if recovered := recover(); recovered != nil {
			fd = -1
			err = fmt.Errorf("WireGuard socket inspection failed: %v", recovered)
		}
	}()
	return peek()
}
