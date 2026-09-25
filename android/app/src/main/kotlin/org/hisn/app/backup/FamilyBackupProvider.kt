package org.hisn.app.backup

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * عقد «باكأب أمان» (AmanBackup v1) لحصن — بقرار المالك 2026-08-24 بعد أن كان مستقلاً:
 * قاعدة كلمات المرور KDBX4 **مشفّرة أصلاً بكلمة صاحبها الرئيسية** لا بقفل الجهاز،
 * فنقلها كما هي آمنٌ ويفتحها على أي جهازٍ بكلمته (وملفِّ مفتاحه إن كان يستعمله).
 *
 * يُضم: hisn.kdbx (القاعدة) + keyfile.bin (اعتماد الفتح المحمول إن وُجد) + vault.json (اسمٌ ووسوم).
 * يُستثنى حتماً: secure/master.bio — غلاف البصمة مربوطٌ بعتاد هذا الجهاز، ونقله يفسد جهازاً آخر.
 *
 * الاستعادة على رقصة حصن الآمنة نفسها: الحالي إلى .bak، الوافد إلى .tmp ثم إعادة تسمية —
 * فلا قاعدة مبتورة مهما انقطع. ثم تُنهى العملية لتُفتح القاعدة نظيفةً عند الإقلاع التالي.
 */
class FamilyBackupProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun ctx(): Context = context!!.applicationContext
    private fun vaultDir(): File = File(ctx().filesDir, "vault")

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (!callerIsAmanStore()) return null
        return when (method) {
            "describe" -> Bundle().apply { putString("format", FORMAT) }
            "export" -> {
                val blob = (try { exportZip() } catch (e: Exception) { null }) ?: return null
                Bundle().apply {
                    putParcelable("pfd", pumpToPipe(blob))
                    putString("format", FORMAT)
                }
            }
            "import" -> {
                if (extras?.getString("format") != null && extras.getString("format") != FORMAT) {
                    return Bundle().apply { putBoolean("ok", false) }
                }
                val pfd = extras?.getParcelable<ParcelFileDescriptor>("pfd")
                    ?: return Bundle().apply { putBoolean("ok", false) }
                val ok = try {
                    val blob = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
                    importZip(blob)
                } catch (e: Exception) { false }
                Bundle().apply { putBoolean("ok", ok) }
            }
            else -> null
        }
    }

    private fun exportZip(): ByteArray? {
        val db = File(vaultDir(), DB_NAME)
        // لا قاعدةَ بعد = لا شيء يُنسخ — يتخطّاه المتجر بصراحة.
        if (!db.isFile || db.length() == 0L) return null
        return ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zip ->
                fun put(name: String, f: File) {
                    if (f.isFile) {
                        zip.putNextEntry(ZipEntry(name))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                put(DB_NAME, db)
                put("keyfile.bin", File(vaultDir(), "keyfile.bin"))
                put("vault.json", File(vaultDir(), "vault.json"))
            }
            bos.toByteArray()
        }
    }

    private fun importZip(blob: ByteArray): Boolean {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(blob)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) entries[e.name] = zip.readBytes()
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        val incoming = entries[DB_NAME] ?: return false
        val dir = vaultDir(); dir.mkdirs()
        val db = File(dir, DB_NAME)
        val bak = File(dir, "$DB_NAME.bak")
        val tmp = File(dir, "$DB_NAME.tmp")
        // رقصة حصن: الوافد إلى .tmp، الحالي إلى .bak، ثم إعادة التسمية — لا قاعدة مبتورة أبداً.
        tmp.outputStream().use { it.write(incoming) }
        if (db.isFile) {
            bak.delete()
            db.copyTo(bak, overwrite = true)
        }
        if (!tmp.renameTo(db)) {
            db.delete()
            if (!tmp.renameTo(db)) return false
        }
        entries["keyfile.bin"]?.let { File(dir, "keyfile.bin").outputStream().use { o -> o.write(it) } }
        entries["vault.json"]?.let { File(dir, "vault.json").outputStream().use { o -> o.write(it) } }
        // master.bio لا يُلمس: غلاف بصمة هذا الجهاز يبقى له، والفتح بعد الاستعادة بالكلمة الرئيسية.
        android.os.Handler(ctx().mainLooper).postDelayed({ Runtime.getRuntime().exit(0) }, 400)
        return true
    }

    private fun pumpToPipe(data: ByteArray): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        thread(name = "hisn-backup-pump") {
            try {
                FileOutputStream(pipe[1].fileDescriptor).use { it.write(data); it.flush() }
                pipe[1].close()
            } catch (_: Exception) {
                runCatching { pipe[1].closeWithError("pump failed") }
            }
        }
        return pipe[0]
    }

    private fun callerIsAmanStore(): Boolean {
        val caller = callingPackage ?: return false
        if (caller != STORE_PACKAGE) return false
        val c = context ?: return false
        val sig = try {
            val pm = c.packageManager
            val cert: ByteArray = if (Build.VERSION.SDK_INT >= 28) {
                val info = pm.getPackageInfo(caller, PackageManager.GET_SIGNING_CERTIFICATES)
                val si = info.signingInfo ?: return false
                val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
                sigs?.firstOrNull()?.toByteArray() ?: return false
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(caller, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()?.toByteArray() ?: return false
            }
            MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            return false
        }
        return sig.equals(STORE_SIGNER_SHA256, ignoreCase = true)
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, sa: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, sa: Array<out String>?): Int = 0

    companion object {
        private const val FORMAT = "hisn-1"
        private const val DB_NAME = "hisn.kdbx"
        private const val STORE_PACKAGE = "org.amanlabs.store"
        private const val STORE_SIGNER_SHA256 =
            "0d69c5d80e24e587559e9c96035dab56135a4e70dad172cda1dfb3080ee690af"
    }
}
