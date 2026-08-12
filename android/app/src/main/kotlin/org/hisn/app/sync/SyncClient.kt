package org.hisn.app.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hisn.app.data.VaultRepository
import org.hisn.app.data.VaultState
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** A desktop this phone has paired with. The pairing secret is deliberately not exposed here. */
data class PairedDevice(val name: String, val host: String, val port: Int, val fingerprint: String)

sealed class SyncProgress {
    object Idle : SyncProgress()
    data class Connecting(val host: String) : SyncProgress()
    object Handshaking : SyncProgress()
    data class Transferring(val bytesDone: Long, val bytesTotal: Long) : SyncProgress()

    /**
     * The merge is running. [changes] is 0 while it is in flight — the real breakdown is only
     * known once the merge finishes and arrives with [Done].
     */
    data class Merging(val changes: Int) : SyncProgress()
    data class Done(val added: Int, val updated: Int, val deleted: Int) : SyncProgress()
    data class Failed(val message: String) : SyncProgress()
}

private val Context.hisnSyncStore: DataStore<Preferences> by preferencesDataStore(name = "hisn_sync")

/**
 * Talks to the desktop over the LAN using the protocol documented in [SyncProtocol.kt] and
 * SYNC-PROTOCOL.md.
 *
 * The phone always drives: it sends its database, the desktop merges and answers with the merged
 * result, and the phone merges that back in. Nothing leaves the device unencrypted, and nothing
 * touches the internet.
 */
class SyncClient(context: Context, private val repo: VaultRepository) {

    private val appContext = context.applicationContext
    private val store = appContext.hisnSyncStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _progress = MutableStateFlow<SyncProgress>(SyncProgress.Idle)
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<PairedDevice>>(emptyList())
    val pairedDevices: StateFlow<List<PairedDevice>> = _pairedDevices.asStateFlow()

    /** Full records including pairing secrets; never leaves this class. */
    private val records = MutableStateFlow<List<StoredDevice>>(emptyList())

    init {
        scope.launch {
            store.data
                .catch { cause ->
                    // An unreadable device list must not crash sync; the user can pair again.
                    if (cause is IOException) emit(emptyPreferences()) else throw cause
                }
                .collect { prefs ->
                    val parsed = decodeRecords(prefs[KEY_DEVICES])
                    records.value = parsed
                    _pairedDevices.value = parsed.map { it.toPublic() }
                }
        }
    }

    /**
     * Validates a scanned QR payload by actually completing a handshake with the desktop, then
     * remembers the device. Pairing therefore fails immediately — with a real reason — when the
     * code is stale, the desktop is not listening, or someone is impersonating it.
     */
    suspend fun pairFromQrPayload(payload: String): Result<PairedDevice> = resultOf {
        val parsed = PairingPayload.parse(payload)
        withContext(Dispatchers.IO) {
            connect(parsed.host, parsed.port).use { socket ->
                val session = SyncSession.clientHandshake(
                    socket.getInputStream(),
                    socket.getOutputStream(),
                    parsed.secret,
                    parsed.fingerprint,
                )
                try {
                    session.send(SyncWire.MSG_HELLO, SyncMessages.encodeHello(deviceName()))
                    // The desktop's own name wins over the one printed in the QR code.
                    val peerName = SyncMessages.decodeHello(session.expect(SyncWire.MSG_HELLO).body)
                    val record = StoredDevice(
                        name = peerName.ifBlank { parsed.name },
                        host = parsed.host,
                        port = parsed.port,
                        fingerprint = SyncCrypto.encodeBase64(parsed.fingerprint),
                        secret = SyncCrypto.encodeBase64(parsed.secret),
                        lastSyncedAt = 0L,
                    )
                    upsert(record)
                    record.toPublic()
                } finally {
                    session.close()
                }
            }
        }
    }.onFailure { _progress.value = SyncProgress.Failed(describe(it)) }

    /**
     * Runs a full two-way sync. Progress is published on [progress]; the returned [Result] carries
     * the same failure so a caller can react without watching the flow.
     */
    suspend fun syncWith(device: PairedDevice): Result<Unit> = resultOf {
        if (repo.status.value.state != VaultState.Unlocked) {
            throw IllegalStateException("Unlock the vault before syncing")
        }
        val record = records.value.firstOrNull { it.fingerprint == device.fingerprint }
            ?: throw IllegalStateException("This device is not paired any more — scan its QR code again")
        val secret = SyncCrypto.decodeBase64(record.secret)
            ?: throw IllegalStateException("The stored pairing secret is unreadable — pair with the desktop again")
        val fingerprint = SyncCrypto.decodeBase64(record.fingerprint)
            ?: throw IllegalStateException("The stored device fingerprint is unreadable — pair with the desktop again")

        val localBytes = withContext(Dispatchers.IO) { repo.currentBytes() }
            ?: throw IllegalStateException("There is no database to sync yet")

        try {
            _progress.value = SyncProgress.Connecting(device.host)
            withContext(Dispatchers.IO) {
                connect(device.host, device.port).use { socket ->
                    _progress.value = SyncProgress.Handshaking
                    val session = SyncSession.clientHandshake(
                        socket.getInputStream(),
                        socket.getOutputStream(),
                        secret,
                        fingerprint,
                    )
                    try {
                        exchange(session, localBytes)
                    } catch (e: Throwable) {
                        session.sendError(e.message ?: "sync failed on the phone")
                        throw e
                    } finally {
                        session.close()
                    }
                }
            }
            upsert(record.copy(host = device.host, port = device.port, lastSyncedAt = System.currentTimeMillis()))
        } catch (e: Throwable) {
            _progress.value = SyncProgress.Failed(describe(e))
            throw e
        } finally {
            SyncCrypto.wipe(secret)
        }
    }

    /** Removes the device and its pairing secret. */
    fun forget(device: PairedDevice) {
        scope.launch {
            val remaining = records.value.filterNot { it.fingerprint == device.fingerprint }
            persist(remaining)
        }
    }

    /** Clears a [SyncProgress.Failed] or [SyncProgress.Done] banner. */
    fun resetProgress() {
        _progress.value = SyncProgress.Idle
    }

    // ------------------------------------------------------------------- session

    private suspend fun exchange(session: SyncSession, localBytes: ByteArray) {
        session.send(SyncWire.MSG_HELLO, SyncMessages.encodeHello(deviceName()))
        SyncMessages.decodeHello(session.expect(SyncWire.MSG_HELLO).body)

        // 1. push our database
        val total = localBytes.size.toLong()
        if (total > SyncWire.MAX_DATABASE_BYTES) {
            throw IOException("This database is too large to sync (${total / (1024 * 1024)} MiB)")
        }
        session.send(SyncWire.MSG_DB_OFFER, SyncMessages.encodeOffer(total, sha256(localBytes)))
        _progress.value = SyncProgress.Transferring(0, total)

        var offset = 0
        var index = 0
        while (offset < localBytes.size) {
            coroutineContext.ensureActive()
            val length = minOf(SyncWire.CHUNK_BYTES, localBytes.size - offset)
            session.send(SyncWire.MSG_DB_DATA, SyncMessages.encodeChunk(index, localBytes, offset, length))
            offset += length
            index++
            _progress.value = SyncProgress.Transferring(offset.toLong(), total)
        }
        session.send(SyncWire.MSG_DB_REQUEST)

        // 2. pull the merged database back
        val offer = SyncMessages.decodeOffer(session.expect(SyncWire.MSG_MERGED_DB).body)
        _progress.value = SyncProgress.Transferring(0, offer.size)
        val buffer = ByteArrayOutputStream(offer.size.toInt().coerceAtMost(1 shl 20))
        var received = 0L
        var expectedIndex = 0
        while (received < offer.size) {
            coroutineContext.ensureActive()
            val (chunkIndex, data) = SyncMessages.decodeChunk(session.expect(SyncWire.MSG_DB_DATA).body)
            if (chunkIndex != expectedIndex) {
                throw IOException("The desktop sent chunk $chunkIndex where $expectedIndex was expected")
            }
            received += data.size
            if (received > offer.size) {
                throw IOException("The desktop sent more data than it announced")
            }
            buffer.write(data)
            expectedIndex++
            _progress.value = SyncProgress.Transferring(received, offer.size)
        }

        val merged = buffer.toByteArray()
        if (!MessageDigest.isEqual(sha256(merged), offer.sha256)) {
            throw IOException("The database received from the desktop is damaged (checksum mismatch)")
        }

        // 3. merge locally. mergeFrom opens the remote copy with our own key first and keeps a
        //    .bak, so a bad payload cannot destroy the local vault.
        _progress.value = SyncProgress.Merging(0)
        val (added, updated, deleted) = repo.mergeFrom(merged).getOrThrow()
        _progress.value = SyncProgress.Done(added, updated, deleted)
    }

    private fun connect(host: String, port: Int): Socket {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), SyncWire.HANDSHAKE_TIMEOUT_MS)
            socket.soTimeout = SyncWire.READ_TIMEOUT_MS
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
        return socket
    }

    private suspend fun deviceName(): String = repo.prefs.current().deviceName

    /** Like `runCatching`, but a cancelled sync stays cancelled instead of becoming a failure. */
    private suspend fun <T> resultOf(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        _progress.value = SyncProgress.Idle
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    /** Turns any failure into a sentence worth showing, never an empty or class-name-only string. */
    private fun describe(error: Throwable): String = when (error) {
        is SocketTimeoutException -> "The desktop did not answer in time — is Hisn sync still open on it?"
        is SyncProtocolException -> error.message ?: "The sync connection failed"
        is IOException -> error.message?.takeIf { it.isNotBlank() }
            ?: "The desktop could not be reached on this network"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Sync failed"
    }

    // ------------------------------------------------------------------ storage

    private suspend fun upsert(record: StoredDevice) {
        val current = records.value.filterNot { it.fingerprint == record.fingerprint }
        persist(current + record)
    }

    private suspend fun persist(devices: List<StoredDevice>) {
        val array = JSONArray()
        devices.forEach { array.put(it.toJson()) }
        store.edit { it[KEY_DEVICES] = array.toString() }
    }

    private fun decodeRecords(raw: String?): List<StoredDevice> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i -> StoredDevice.fromJson(array.optJSONObject(i)) }
        }.getOrDefault(emptyList())
    }

    /**
     * A paired desktop as stored on disk. The pairing secret lives in app-private storage next to
     * the encrypted vault; it authorises pushing a database to that desktop, not reading one.
     */
    private data class StoredDevice(
        val name: String,
        val host: String,
        val port: Int,
        val fingerprint: String,
        val secret: String,
        val lastSyncedAt: Long,
    ) {
        fun toPublic() = PairedDevice(name, host, port, fingerprint)

        fun toJson(): JSONObject = JSONObject()
            .put("name", name)
            .put("host", host)
            .put("port", port)
            .put("fingerprint", fingerprint)
            .put("secret", secret)
            .put("lastSyncedAt", lastSyncedAt)

        companion object {
            fun fromJson(json: JSONObject?): StoredDevice? {
                if (json == null) return null
                val fingerprint = json.optString("fingerprint")
                val secret = json.optString("secret")
                val host = json.optString("host")
                val port = json.optInt("port", -1)
                if (fingerprint.isBlank() || secret.isBlank() || host.isBlank() || port !in 1..65535) return null
                return StoredDevice(
                    name = json.optString("name").ifBlank { "KeePassXC" },
                    host = host,
                    port = port,
                    fingerprint = fingerprint,
                    secret = secret,
                    lastSyncedAt = json.optLong("lastSyncedAt", 0L),
                )
            }
        }
    }

    private companion object {
        val KEY_DEVICES = stringPreferencesKey("paired_devices")
    }
}
