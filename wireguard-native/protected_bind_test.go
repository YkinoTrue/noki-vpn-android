package main

import (
	"errors"
	"testing"

	"golang.zx2c4.com/wireguard/conn"
)

type bindFixture struct {
	opens       int
	closes      int
	fd4         int
	fd6         int
	receivers   int
	peekErr     error
	panicOnIPv6 bool
}

func (b *bindFixture) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	b.opens++
	count := b.receivers
	if count == 0 {
		count = 2
	}
	fns := make([]conn.ReceiveFunc, count)
	for i := range fns {
		fns[i] = func([][]byte, []int, []conn.Endpoint) (int, error) { return 0, nil }
	}
	return fns, port, nil
}
func (b *bindFixture) Close() error                                { b.closes++; return nil }
func (b *bindFixture) SetMark(uint32) error                        { return nil }
func (b *bindFixture) Send([][]byte, conn.Endpoint) error          { return nil }
func (b *bindFixture) ParseEndpoint(string) (conn.Endpoint, error) { return nil, nil }
func (b *bindFixture) BatchSize() int                              { return 1 }
func (b *bindFixture) PeekLookAtSocketFd4() (int, error)           { return b.fd4, b.peekErr }
func (b *bindFixture) PeekLookAtSocketFd6() (int, error) {
	if b.panicOnIPv6 {
		panic("socket family unavailable")
	}
	return b.fd6, b.peekErr
}

func TestProtectedBindProtectsEverySocketBeforeOpenAndAfterRebind(t *testing.T) {
	b := &bindFixture{fd4: 41, fd6: 61}
	var protected []int
	bind := newProtectedBind(b, b, func(fd int) bool {
		protected = append(protected, fd)
		return true
	})
	for attempt := 0; attempt < 2; attempt++ {
		if _, _, err := bind.Open(51820); err != nil {
			t.Fatalf("open %d: %v", attempt, err)
		}
		if err := bind.Close(); err != nil {
			t.Fatal(err)
		}
	}
	if b.opens != 2 || b.closes != 2 {
		t.Fatalf("underlying lifecycle: opens=%d closes=%d", b.opens, b.closes)
	}
	want := []int{41, 61, 41, 61}
	for i, fd := range want {
		if i >= len(protected) || protected[i] != fd {
			t.Fatalf("protect sequence=%v, want=%v", protected, want)
		}
	}
	if len(protected) != len(want) {
		t.Fatalf("protect sequence=%v, want=%v", protected, want)
	}
}

func TestProtectedBindClosesSocketsWhenProtectionFails(t *testing.T) {
	b := &bindFixture{fd4: 41, fd6: 61}
	bind := newProtectedBind(b, b, func(fd int) bool { return fd != 61 })
	if _, _, err := bind.Open(51820); err == nil {
		t.Fatal("expected protection failure")
	}
	if b.closes != 1 {
		t.Fatalf("unprotected socket was left open: closes=%d", b.closes)
	}
}

func TestProtectedBindFailsClosedWhenSocketCannotBeInspected(t *testing.T) {
	b := &bindFixture{fd4: 41, fd6: 61, peekErr: errors.New("socket unavailable")}
	bind := newProtectedBind(b, b, func(int) bool { return true })
	if _, _, err := bind.Open(51820); err == nil {
		t.Fatal("expected inspection failure")
	}
	if b.closes != 1 {
		t.Fatalf("socket left open after inspection failure: closes=%d", b.closes)
	}
}

func TestProtectedBindClosesSocketsWhenFamilyPeekPanics(t *testing.T) {
	b := &bindFixture{fd4: 41, fd6: 61, panicOnIPv6: true}
	bind := newProtectedBind(b, b, func(int) bool { return true })
	if _, _, err := bind.Open(51820); err == nil {
		t.Fatal("expected inspection panic to become a closed error")
	}
	if b.closes != 1 {
		t.Fatalf("socket left open after inspection panic: closes=%d", b.closes)
	}
}

func TestProtectedBindAcceptsOnlyOpenedIPv4Socket(t *testing.T) {
	b := &bindFixture{fd4: 41, receivers: 1, panicOnIPv6: true}
	var protected []int
	bind := newProtectedBind(b, b, func(fd int) bool {
		protected = append(protected, fd)
		return true
	})
	if _, _, err := bind.Open(51820); err != nil {
		t.Fatalf("IPv4-only bind failed: %v", err)
	}
	if len(protected) != 1 || protected[0] != 41 || b.closes != 0 {
		t.Fatalf("unexpected IPv4-only protection: fds=%v closes=%d", protected, b.closes)
	}
}
