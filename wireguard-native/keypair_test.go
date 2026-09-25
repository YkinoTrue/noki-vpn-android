package main

import (
	"bytes"
	"crypto/ecdh"
	"testing"
)

func TestNewWireGuardKeyPairIsClampedAndMatchesPublicKey(t *testing.T) {
	private, public, err := newWireGuardKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	if private[0]&7 != 0 || private[31]&0x80 != 0 || private[31]&0x40 == 0 {
		t.Fatal("private key is not WireGuard-clamped")
	}
	if bytes.Equal(private[:], make([]byte, 32)) || bytes.Equal(public[:], make([]byte, 32)) {
		t.Fatal("key pair contains an all-zero key")
	}
	key, err := ecdh.X25519().NewPrivateKey(private[:])
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(key.PublicKey().Bytes(), public[:]) {
		t.Fatal("public key does not match private key")
	}
}
