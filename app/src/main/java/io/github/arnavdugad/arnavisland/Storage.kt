package io.github.arnavdugad.arnavisland

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import io.github.arnavdugad.arnavisland.link.Inbox
import io.github.arnavdugad.arnavisland.link.LinkStore
import io.github.arnavdugad.arnavisland.link.Sink
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The phone's pairing identity, sealed with an AES key that never leaves the Android Keystore (so a copy of the app's
 * files is useless elsewhere), and the paired devices (their public keys and names only).
 */
class PhoneStore(private val context: Context) : LinkStore {
    private val identityFile get() = File(context.filesDir, "identity.sealed")
    private val peersFile get() = File(context.filesDir, "peers.tsv")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return generator.generateKey()
    }
    override fun loadIdentity(): ByteArray? = runCatching {
        val sealed = identityFile.takeIf { it.exists() }?.readBytes() ?: return null
        if (sealed.size < 12 + 16) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
        cipher.doFinal(sealed, 12, sealed.size - 12)
    }.getOrNull()
    override fun saveIdentity(bytes: ByteArray): Boolean = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(bytes)
        val temp = File(context.filesDir, "identity.sealed.new"); temp.writeBytes(out); temp.renameTo(identityFile)
    }.getOrDefault(false)
    override fun loadPeers(): String? = peersFile.takeIf { it.exists() }?.readText()
    override fun savePeers(text: String) { runCatching { val temp = File(context.filesDir, "peers.tsv.new"); temp.writeText(text); temp.renameTo(peersFile) } }
    companion object { private const val ALIAS = "arnav-island-identity" }
}

/**
 * Received files land in Downloads/Arnav Island (a folder keeps its tree there). Each is written as a pending entry
 * and only appears once it arrived whole. Handed-off songs are kept by the app itself.
 */
class DownloadsInbox(private val context: Context) : Inbox {
    private val root = "Arnav Island"
    private fun legacyRoot() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), root)

    override fun folder(name: String): String {
        var candidate = name; var n = 2
        while (folderExists(candidate) && n < 200) candidate = "$name (${n++})"
        return candidate
    }
    private fun folderExists(name: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return File(legacyRoot(), name).exists()
        return storeFolderExists(name)
    }
    @androidx.annotation.RequiresApi(29)
    private fun storeFolderExists(name: String): Boolean {
        val path = "${Environment.DIRECTORY_DOWNLOADS}/$root/$name/"
        return runCatching {
            context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$path%"), null)?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }
    override fun create(parts: List<String>, size: Long): Sink? = runCatching {
        val name = parts.last(); val folders = parts.dropLast(1)
        if (Build.VERSION.SDK_INT < 29) legacySink(File(legacyRoot(), folders.joinToString(File.separator)), name) else storeSink(folders, name)
    }.getOrNull()

    private fun mimeOf(name: String) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    @androidx.annotation.RequiresApi(29)
    private fun storeSink(folders: List<String>, name: String): Sink? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, (listOf(Environment.DIRECTORY_DOWNLOADS, root) + folders).joinToString("/") + "/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val stream = resolver.openOutputStream(uri, "w") ?: run { resolver.delete(uri, null, null); return null }
        return object : Sink {
            override val out: OutputStream = stream
            override fun commit(): String? = runCatching {
                stream.close(); resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null); uri.toString()
            }.getOrNull()
            override fun abort() { runCatching { stream.close() }; runCatching { resolver.delete(uri, null, null) } }
        }
    }
    private fun legacySink(dir: File, name: String): Sink? {
        dir.mkdirs(); val part = File(dir, "$name.arnavpart"); val stream = FileOutputStream(part)
        return object : Sink {
            override val out: OutputStream = stream
            override fun commit(): String? {
                stream.close()
                var target = File(dir, name); var n = 2
                val stem = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
                while (target.exists()) target = File(dir, if (ext.isEmpty()) "$stem (${n++})" else "$stem (${n++}).$ext")
                if (!part.renameTo(target)) { part.delete(); return null }
                MediaScannerConnection.scanFile(context, arrayOf(target.path), null, null)
                return FileProvider.getUriForFile(context, "${context.packageName}.files", target).toString()
            }
            override fun abort() { runCatching { stream.close() }; part.delete() }
        }
    }
    override fun song(name: String, size: Long): Sink? {
        val dir = File(context.filesDir, "handoff").apply { mkdirs() }
        // Only the latest few songs are kept.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
        val target = File(dir, name); val part = File(dir, "$name.arnavpart"); val stream = FileOutputStream(part)
        return object : Sink {
            override val out: OutputStream = stream
            override fun commit(): String? { stream.close(); target.delete(); return if (part.renameTo(target)) target.path else null }
            override fun abort() { runCatching { stream.close() }; part.delete() }
        }
    }
    override fun room(bytes: Long): Boolean = runCatching {
        val stat = StatFs(if (Build.VERSION.SDK_INT < 29) Environment.getExternalStorageDirectory().path else context.filesDir.path)
        stat.availableBytes > bytes + 64L * 1024 * 1024
    }.getOrDefault(true)

    companion object {
        /** A received file's content URI, for opening or sharing it. */
        fun uriOf(shown: String): Uri? = runCatching { Uri.parse(shown) }.getOrNull()?.takeIf { it.scheme == "content" }
    }
}
