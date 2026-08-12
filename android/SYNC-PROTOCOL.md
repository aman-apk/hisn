# Hisn Sync Protocol — version 1

A LAN-only, internet-free synchronisation channel between the **Hisn Android app** (client,
initiator) and the **KeePassXC-based desktop app** (server, responder).

The desktop listens on a TCP port and displays a QR code. The phone scans it, connects, and the
two databases converge: the phone pushes its `.kdbx`, the desktop merges it into its own copy and
saves, then returns the merged database, which the phone merges back in.

The threat model is a home network that may contain hostile devices. Consequences:

* Everything after the 180-byte plaintext handshake (42 + 106 + 32) is encrypted and
  authenticated.
* An attacker who never saw the QR code cannot complete the handshake, even as an active
  man-in-the-middle.
* The desktop's long-term identity is pinned on first pairing (trust on first use); a later
  mismatch aborts the sync with a distinct error.
* Every length is validated before anything is allocated.

## 0. Conventions

| | |
|---|---|
| Integers | little endian unless a field explicitly says big endian |
| Hash | SHA-256 |
| Key agreement | X25519 (RFC 7748), 32-byte keys, 32-byte shared secrets |
| KDF | HKDF-SHA256 (RFC 5869) |
| AEAD | ChaCha20-Poly1305 (RFC 8439), 12-byte nonce, 16-byte tag |
| `MAGIC` | the 8 ASCII bytes `HISNSYN1` (`0x48 0x49 0x53 0x4E 0x53 0x59 0x4E 0x31`) |
| `VERSION` | `1` |

Constant-time comparison is required wherever this document says "compare in constant time"
(confirmation tags, fingerprints).

## 1. Pairing payload (the QR code)

The desktop generates a **fresh 32-byte pairing secret** each time it opens the sync window, and
holds a **persistent static X25519 key pair**. The QR code is one line of compact JSON:

```json
{"v":1,"n":"Laptop","h":"192.168.1.24","p":45781,"k":"<base64 32 bytes>","f":"<base64 32 bytes>"}
```

| key | type | meaning |
|---|---|---|
| `v` | int | protocol version, must equal `1` |
| `n` | string | desktop display name, ≤ 64 UTF-8 bytes |
| `h` | string | address the phone dials (the desktop's LAN address) |
| `p` | int | TCP port, 1…65535 |
| `k` | string | base64 of the 32-byte pairing secret |
| `f` | string | base64 of `SHA-256(server_static_public_key)` |

Base64 uses the standard alphabet; padding is accepted but not required; no line breaks.

Both sides store the pairing secret after a successful pairing and reuse it for every later sync
with that peer. The phone additionally stores `f` as the pinned fingerprint. Regenerating the QR
code with a new secret requires re-pairing — the desktop should therefore keep the secret it
issued to a device that has successfully paired.

## 2. Handshake

Three plaintext records of fixed size, no framing. Both sides abort on any mismatch.

### 2.1 `ClientHello` — client → server, exactly 42 bytes

| offset | size | field |
|---|---|---|
| 0 | 8 | `MAGIC` |
| 8 | 2 | version, u16 LE, `1` |
| 10 | 32 | client ephemeral X25519 public key |

### 2.2 `ServerHello` — server → client, exactly 106 bytes

| offset | size | field |
|---|---|---|
| 0 | 8 | `MAGIC` |
| 8 | 2 | version, u16 LE, `1` |
| 10 | 32 | server ephemeral X25519 public key |
| 42 | 32 | server **static** X25519 public key |
| 74 | 32 | `confirm_s` (§2.4) |

### 2.3 `ClientConfirm` — client → server, exactly 32 bytes

The raw value `confirm_c` (§2.4).

### 2.4 Key schedule

Both sides compute two agreements:

```
ee = X25519(client_ephemeral_private, server_ephemeral_public)
   = X25519(server_ephemeral_private, client_ephemeral_public)

es = X25519(client_ephemeral_private, server_static_public)
   = X25519(server_static_private,    client_ephemeral_public)
```

An all-zero result (a low-order peer key) **must** abort the connection.

```
transcript = MAGIC || version_u16le || client_eph_pub || server_eph_pub || server_static_pub
             (8 + 2 + 32 + 32 + 32 = 106 bytes)

salt = SHA-256(transcript)
IKM  = ee || es || pairing_secret            (96 bytes)
PRK  = HKDF-Extract(salt, IKM)

key_c2s   = HKDF-Expand(PRK, "hisn-sync/1 key client->server",   32)
key_s2c   = HKDF-Expand(PRK, "hisn-sync/1 key server->client",   32)
nonce_c2s = HKDF-Expand(PRK, "hisn-sync/1 nonce client->server",  4)
nonce_s2c = HKDF-Expand(PRK, "hisn-sync/1 nonce server->client",  4)
confirm_s = HKDF-Expand(PRK, "hisn-sync/1 confirm server",       32)
confirm_c = HKDF-Expand(PRK, "hisn-sync/1 confirm client",       32)
```

Info strings are ASCII with **no** trailing NUL. Implementations that only expose the combined
"extract then expand" HKDF may call it once per label with the same `salt` and `IKM` — the result
is identical.

`ee` binds the session to two fresh ephemeral keys (forward secrecy). `es` proves the server holds
the static private key whose hash is pinned. `pairing_secret` proves both sides saw the QR code,
which is what stops a hostile device on the same WiFi from pairing at all.

### 2.5 Confirmation and pinning

1. The client checks `MAGIC` and the version, then compares `SHA-256(server_static_public)`
   against the pinned fingerprint (from the QR code when pairing, from the stored device record
   afterwards) **in constant time**. Mismatch → abort: *the desktop's identity key does not match*.
2. The client recomputes `confirm_s` and compares it with the value in `ServerHello` in constant
   time. Mismatch → abort: *the pairing code does not match this desktop*.
3. The client sends `confirm_c`. The server compares in constant time and aborts on mismatch,
   without sending anything further.

`confirm_s` / `confirm_c` are derived from the same PRK as the traffic keys but under distinct
labels, so sending them in the clear reveals nothing about the traffic keys.

Both sides wipe `ee`, `es` and `IKM` from memory once the keys are derived.

## 3. Transport frames

Everything after `ClientConfirm` is framed and encrypted:

```
+--------------------+----------------------------------+
| length : u32 LE    | ciphertext || tag   (length B)    |
+--------------------+----------------------------------+
```

* `length` = plaintext length + 16. Receivers **must** check `17 ≤ length ≤ 1 048 592`
  (1 MiB + tag) *before* allocating a buffer.
* **AAD** = the four length bytes exactly as they appear on the wire.
* **nonce** = 4-byte direction prefix (`nonce_c2s` for client→server, `nonce_s2c` for
  server→client) followed by a **u64 big-endian counter**.
* The counter starts at `0` for the first frame in each direction and increments by one per frame.
  It is never reset, so a `(key, nonce)` pair is never reused. A counter that would wrap aborts the
  connection.
* The client encrypts with `key_c2s` and decrypts with `key_s2c`; the server does the opposite.

Frame plaintext:

```
+-------------+---------------------+
| type : u8   | body : remainder    |
+-------------+---------------------+
```

Any frame that fails authentication aborts the connection immediately. There is no renegotiation
and no re-keying: sessions are short.

## 4. Messages

| code | name | body |
|---|---|---|
| `0x01` | `HELLO` | `u16 LE version` · `u8 nameLen` (≤ 64) · `nameLen` bytes UTF-8 device name |
| `0x02` | `DB_OFFER` | `u64 LE size` (≤ 64 MiB) · `32` bytes SHA-256 of the whole database |
| `0x03` | `DB_DATA` | `u32 LE chunkIndex` · `u32 LE length` (1…65536) · `length` bytes |
| `0x04` | `DB_REQUEST` | empty |
| `0x05` | `MERGED_DB` | same layout as `DB_OFFER` |
| `0x06` | `ERROR` | `u16 LE length` (≤ 1024) · `length` bytes UTF-8 message |

Rules:

* `chunkIndex` starts at `0` and increases by exactly one per chunk of the transfer in progress.
  A gap or repeat is an error.
* Accumulated chunk bytes must never exceed the announced size, and the transfer ends exactly when
  they equal it.
* The receiver verifies the SHA-256 of the assembled database against the offer before using it.
* `ERROR` text is advisory. It is never trusted content: strip control characters and truncate
  before display.

## 5. Session flow

```
client                                    server
------                                    ------
ClientHello               ->
                          <-              ServerHello
ClientConfirm             ->
HELLO                     ->
                          <-              HELLO
DB_OFFER                  ->
DB_DATA × n               ->
DB_REQUEST                ->
                                          merge received DB into own DB (Merger),
                                          save atomically (temp file + rename, keep .bak)
                          <-              MERGED_DB
                          <-              DB_DATA × m
close
```

After receiving the merged database the phone:

1. verifies the SHA-256 from `MERGED_DB`;
2. **opens it with its own composite key** — if that fails, the local vault is left untouched and
   the sync reports a failure;
3. merges it into the local database and saves atomically, keeping the previous file as `.bak`.

Merging rather than overwriting is deliberate: it preserves anything the user changed on the phone
while the transfer was in flight, and it converges to the same content because the desktop already
merged the phone's copy into its own.

### Pairing verification

A client that is only verifying a pairing (QR scan) may close the connection immediately after the
`HELLO` exchange. The server must treat that as a successful pairing, not as an error.

## 6. Limits and timeouts

| | |
|---|---|
| handshake timeout | 10 s |
| per-frame read timeout | 30 s |
| chunk size | 64 KiB |
| max frame plaintext | 1 MiB |
| max database | 64 MiB |
| max device name | 64 bytes |
| max error message | 1 KiB |

## 7. Error handling

* Any violation of this document — bad magic, wrong version, bad length, failed tag, wrong message
  type, oversized transfer, chunk out of order, checksum mismatch — aborts the session. Send
  `ERROR` with a short explanation if the transport is still usable, then close the socket.
* Receiving `ERROR` aborts the session and surfaces the (sanitised) text to the user.
* A failed sync never modifies the local database. Both sides write with the temp-file-plus-rename
  pattern and keep exactly one `.bak`.
* The server should accept one connection at a time and drop connections that stall mid-handshake.

## 8. Security notes for implementers

* The pairing secret must come from a CSPRNG and be shown only in the QR code, never logged.
* Do not fall back to an unauthenticated handshake when no fingerprint is stored; refuse to sync
  instead.
* Do not reuse an ephemeral key pair across connections.
* Zero shared secrets, session keys and the decrypted database buffer when done.
* The database bytes on the wire are already encrypted with the user's KDBX key; the session
  encryption is a second, independent layer that also hides file size patterns from casual
  observers and prevents tampering in flight.
