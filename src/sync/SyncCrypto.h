/*
 *  Copyright (C) 2026 Hisn Project
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 or (at your option)
 *  version 3 of the License.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

#ifndef HISN_SYNCCRYPTO_H
#define HISN_SYNCCRYPTO_H

#include <QByteArray>

/**
 * The primitives used by the Hisn LAN sync protocol (see android/SYNC-PROTOCOL.md), kept in one
 * place so that the labels, lengths and failure behaviour cannot drift away from the phone side.
 *
 * Every function reports failure by returning false or an empty result instead of throwing:
 * these are called from a worker thread that drives Qt signals, and exceptions must not cross
 * that boundary.
 *
 * Requires Botan 3.
 */
namespace SyncCrypto
{
    constexpr int KeyBytes = 32;
    constexpr int NoncePrefixBytes = 4;
    constexpr int NonceBytes = 12;
    constexpr int TagBytes = 16;
    constexpr int HashBytes = 32;

    /** Cryptographically secure random bytes. */
    QByteArray randomBytes(int size);

    QByteArray sha256(const QByteArray& data);

    /** Comparison that does not leak where two buffers differ. Different lengths compare false. */
    bool constantTimeEquals(const QByteArray& a, const QByteArray& b);

    bool isAllZero(const QByteArray& data);

    /**
     * Scrubs and clears @p data. Only effective while the caller holds the only reference to the
     * buffer, which is why key material is never handed around as a shared QByteArray.
     */
    void wipe(QByteArray& data);

    /** Generates a fresh X25519 key pair. Both outputs are 32 raw bytes. */
    bool generateKeyPair(QByteArray& privateKey, QByteArray& publicKey);

    /** Recomputes the public key belonging to a stored 32-byte X25519 private scalar. */
    bool derivePublicKey(const QByteArray& privateKey, QByteArray& publicKey);

    /**
     * X25519 agreement. Fails on a malformed peer key and on an all-zero shared secret, which is
     * what a low-order peer key produces.
     */
    bool agree(const QByteArray& privateKey, const QByteArray& peerPublicKey, QByteArray& sharedSecret);

    /** HKDF-SHA256: extract with @p salt over @p ikm, then expand @p info to @p length bytes. */
    QByteArray hkdf(const QByteArray& salt, const QByteArray& ikm, const QByteArray& info, int length);

    /** ChaCha20-Poly1305. @p out receives ciphertext || 16-byte tag. */
    bool seal(const QByteArray& key,
              const QByteArray& nonce,
              const QByteArray& aad,
              const QByteArray& plaintext,
              QByteArray& out);

    /** ChaCha20-Poly1305. Fails when the tag does not authenticate. */
    bool open(const QByteArray& key,
              const QByteArray& nonce,
              const QByteArray& aad,
              const QByteArray& ciphertext,
              QByteArray& out);

    /** Frame nonce: 4-byte direction prefix followed by a big-endian u64 counter. */
    QByteArray frameNonce(const QByteArray& prefix, quint64 counter);
} // namespace SyncCrypto

#endif // HISN_SYNCCRYPTO_H
