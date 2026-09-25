package main

import (
	"crypto/ecdh"
	"crypto/rand"
)

func newWireGuardKeyPair() (private, public [32]byte, err error) {
	if _, err = rand.Read(private[:]); err != nil {
		return private, public, err
	}
	private[0] &= 248
	private[31] = (private[31] & 127) | 64
	key, err := ecdh.X25519().NewPrivateKey(private[:])
	if err != nil {
		return private, public, err
	}
	copy(public[:], key.PublicKey().Bytes())
	return private, public, nil
}
