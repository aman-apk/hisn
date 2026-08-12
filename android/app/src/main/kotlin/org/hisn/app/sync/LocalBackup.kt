package org.hisn.app.sync

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.hisn.app.data.VaultRepository
import org.hisn.app.data.VaultState
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The no-network path: hand the vault to another device as a file.
 *
 * Export writes the encrypted .kdbx to any location the storage picker offers — a USB stick, a
 * shared folder, an SD card — and import merges such a file back in. Nothing is decrypted on the
 * way out, and an imported file is opened with this vault's own key before a single byte of the
 * local database changes.
 */
class LocalBackup(context: Context, private val repo: VaultRepository) {

    private val appContext = context.applicationContext

    /** Copies the encrypted database to [uri]. The vault does not need to be unlocked. */
    suspend fun export(uri: Uri): Result<Unit> = repo.exportBackup(uri)

    /**
     * Reads a .kdbx from [uri] and merges it into the open vault.
     *
     * @return added, updated and deleted counts.
     */
    suspend fun importAndMerge(uri: Uri): Result<Triple<Int, Int, Int>> {
        if (repo.status.value.state != VaultState.Unlocked) {
            return Result.failure(IllegalStateException("Unlock the vault before merging a backup into it"))
        }
        val bytes = withContext(Dispatchers.IO) {
            runCatching { readCapped(uri) }
        }.getOrElse { return Result.failure(it) }

        return repo.mergeFrom(bytes)
    }

    /** e.g. "hisn-2026-08-12-1830.kdbx" — a name the picker can pre-fill. */
    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
        return "hisn-$stamp.kdbx"
    }

    /** Refuses anything larger than a plausible database instead of reading it into memory. */
    private fun readCapped(uri: Uri): ByteArray {
        val stream = try {
            appContext.contentResolver.openInputStream(uri)
        } catch (e: SecurityException) {
            throw IOException("Permission to read the selected file was denied", e)
        } ?: throw IOException("The selected file could not be opened")

        val bytes = stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size().toLong() + read > SyncWire.MAX_DATABASE_BYTES) {
                    throw IOException("That file is too large to be a Hisn database")
                }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
        if (bytes.isEmpty()) throw IOException("That file is empty")
        return bytes
    }

    companion object {
        /** MIME type KeePass databases are published under; used for the create-document picker. */
        const val MIME_TYPE = "application/x-keepass2"

        /**
         * Types accepted when opening: pickers and cloud providers label .kdbx inconsistently, so
         * the generic types have to be offered too.
         */
        val OPEN_MIME_TYPES = arrayOf(MIME_TYPE, "application/octet-stream", "*/*")
    }
}
