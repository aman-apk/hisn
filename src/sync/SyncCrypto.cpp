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

#include "SyncCrypto.h"

#include "crypto/Random.h"

#include <botan/aead.h>
#include <botan/hash.h>
#include <botan/kdf.h>
#include <botan/mem_ops.h>
#include <botan/pubkey.h>
#include <botan/x25519.h>

namespace
{
    const uint8_t* asBytes(const QByteArray& data)
    {
        return reinterpret_cast<const uint8_t*>(data.constData());
    }

    QByteArray fromBytes(const uint8_t* data, size_t size)
    {
        return {reinterpret_cast<const char*>(data), static_cast<qsizetype>(size)};
    }

    void scrub(Botan::secure_vector<uint8_t>& data)
    {
        if (!data.empty()) {
            Botan::secure_scrub_memory(data.data(), data.size());
        }
    }
} // namespace

QByteArray SyncCrypto::randomBytes(int size)
{
    if (size <= 0) {
        return {};
    }
    return randomGen()->randomArray(size);
}

QByteArray SyncCrypto::sha256(const QByteArray& data)
{
    try {
        auto hash = Botan::HashFunction::create_or_throw("SHA-256");
        hash->update(asBytes(data), static_cast<size_t>(data.size()));
        const auto result = hash->final();
        return fromBytes(result.data(), result.size());
    } catch (std::exception& e) {
        qWarning("SyncCrypto: SHA-256 failed: %s", e.what());
        return {};
    }
}

bool SyncCrypto::constantTimeEquals(const QByteArray& a, const QByteArray& b)
{
    if (a.size() != b.size() || a.isEmpty()) {
        return false;
    }
    return Botan::constant_time_compare(asBytes(a), asBytes(b), static_cast<size_t>(a.size()));
}

bool SyncCrypto::isAllZero(const QByteArray& data)
{
    if (data.isEmpty()) {
        return true;
    }
    uint8_t accumulator = 0;
    for (const auto byte : data) {
        accumulator |= static_cast<uint8_t>(byte);
    }
    return accumulator == 0;
}

void SyncCrypto::wipe(QByteArray& data)
{
    if (!data.isEmpty()) {
        // data() detaches first, so a buffer that is still shared elsewhere is left alone.
        Botan::secure_scrub_memory(data.data(), static_cast<size_t>(data.size()));
    }
    data.clear();
}

bool SyncCrypto::generateKeyPair(QByteArray& privateKey, QByteArray& publicKey)
{
    try {
        Botan::X25519_PrivateKey key(*randomGen()->getRng());
        auto secret = key.raw_private_key_bits();
        const auto pub = key.public_value();
        privateKey = fromBytes(secret.data(), secret.size());
        publicKey = fromBytes(pub.data(), pub.size());
        scrub(secret);
    } catch (std::exception& e) {
        qWarning("SyncCrypto: X25519 key generation failed: %s", e.what());
        return false;
    }

    if (privateKey.size() != KeyBytes || publicKey.size() != KeyBytes) {
        wipe(privateKey);
        publicKey.clear();
        return false;
    }
    return true;
}

bool SyncCrypto::derivePublicKey(const QByteArray& privateKey, QByteArray& publicKey)
{
    if (privateKey.size() != KeyBytes) {
        return false;
    }

    try {
        Botan::secure_vector<uint8_t> scalar(privateKey.begin(), privateKey.end());
        Botan::X25519_PrivateKey key(scalar);
        scrub(scalar);
        const auto pub = key.public_value();
        publicKey = fromBytes(pub.data(), pub.size());
    } catch (std::exception& e) {
        qWarning("SyncCrypto: X25519 public key derivation failed: %s", e.what());
        return false;
    }

    return publicKey.size() == KeyBytes;
}

bool SyncCrypto::agree(const QByteArray& privateKey, const QByteArray& peerPublicKey, QByteArray& sharedSecret)
{
    sharedSecret.clear();
    if (privateKey.size() != KeyBytes || peerPublicKey.size() != KeyBytes) {
        return false;
    }

    try {
        Botan::secure_vector<uint8_t> scalar(privateKey.begin(), privateKey.end());
        Botan::X25519_PrivateKey key(scalar);
        scrub(scalar);

        Botan::PK_Key_Agreement agreement(key, *randomGen()->getRng(), "Raw");
        auto agreed = agreement.derive_key(0, asBytes(peerPublicKey), static_cast<size_t>(KeyBytes)).bits_of();
        sharedSecret = fromBytes(agreed.data(), agreed.size());
        scrub(agreed);
    } catch (std::exception& e) {
        qWarning("SyncCrypto: X25519 agreement failed: %s", e.what());
        wipe(sharedSecret);
        return false;
    }

    // A low-order peer public key yields an all-zero secret; that connection must not continue.
    if (sharedSecret.size() != KeyBytes || isAllZero(sharedSecret)) {
        wipe(sharedSecret);
        return false;
    }
    return true;
}

QByteArray SyncCrypto::hkdf(const QByteArray& salt, const QByteArray& ikm, const QByteArray& info, int length)
{
    if (length <= 0 || ikm.isEmpty()) {
        return {};
    }

    try {
        // Botan 3 exposes HKDF through the KDF registry rather than a public header.
        auto kdf = Botan::KDF::create_or_throw("HKDF(SHA-256)");
        const auto derived = kdf->derive_key(static_cast<size_t>(length),
                                             asBytes(ikm),
                                             static_cast<size_t>(ikm.size()),
                                             asBytes(salt),
                                             static_cast<size_t>(salt.size()),
                                             asBytes(info),
                                             static_cast<size_t>(info.size()));
        return fromBytes(derived.data(), derived.size());
    } catch (std::exception& e) {
        qWarning("SyncCrypto: HKDF failed: %s", e.what());
        return {};
    }
}

bool SyncCrypto::seal(const QByteArray& key,
                      const QByteArray& nonce,
                      const QByteArray& aad,
                      const QByteArray& plaintext,
                      QByteArray& out)
{
    out.clear();
    if (key.size() != KeyBytes || nonce.size() != NonceBytes) {
        return false;
    }

    try {
        auto aead = Botan::AEAD_Mode::create_or_throw("ChaCha20Poly1305", Botan::Cipher_Dir::Encryption);
        aead->set_key(asBytes(key), static_cast<size_t>(key.size()));
        aead->set_associated_data(asBytes(aad), static_cast<size_t>(aad.size()));
        aead->start(asBytes(nonce), static_cast<size_t>(nonce.size()));

        Botan::secure_vector<uint8_t> buffer(plaintext.begin(), plaintext.end());
        aead->finish(buffer);
        out = fromBytes(buffer.data(), buffer.size());
        scrub(buffer);
    } catch (std::exception& e) {
        qWarning("SyncCrypto: ChaCha20-Poly1305 encryption failed: %s", e.what());
        out.clear();
        return false;
    }

    return out.size() == plaintext.size() + TagBytes;
}

bool SyncCrypto::open(const QByteArray& key,
                      const QByteArray& nonce,
                      const QByteArray& aad,
                      const QByteArray& ciphertext,
                      QByteArray& out)
{
    out.clear();
    if (key.size() != KeyBytes || nonce.size() != NonceBytes || ciphertext.size() < TagBytes) {
        return false;
    }

    try {
        auto aead = Botan::AEAD_Mode::create_or_throw("ChaCha20Poly1305", Botan::Cipher_Dir::Decryption);
        aead->set_key(asBytes(key), static_cast<size_t>(key.size()));
        aead->set_associated_data(asBytes(aad), static_cast<size_t>(aad.size()));
        aead->start(asBytes(nonce), static_cast<size_t>(nonce.size()));

        Botan::secure_vector<uint8_t> buffer(ciphertext.begin(), ciphertext.end());
        aead->finish(buffer);
        out = fromBytes(buffer.data(), buffer.size());
        scrub(buffer);
    } catch (std::exception&) {
        // A failed tag is an expected outcome on a hostile network; do not log the details.
        out.clear();
        return false;
    }

    return true;
}

QByteArray SyncCrypto::frameNonce(const QByteArray& prefix, quint64 counter)
{
    if (prefix.size() != NoncePrefixBytes) {
        return {};
    }

    QByteArray nonce = prefix;
    nonce.reserve(NonceBytes);
    for (int i = 7; i >= 0; --i) {
        nonce.append(static_cast<char>((counter >> (8 * i)) & 0xFF));
    }
    return nonce;
}
