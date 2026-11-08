package io.github.arnavdugad.arnavisland

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.arnavdugad.arnavisland.link.UnlockWire
import io.github.arnavdugad.arnavisland.link.hex
import io.github.arnavdugad.arnavisland.link.unhex
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 1.13 (island 0.30, query 7 and notice 9): unlock your PC with this phone. Setting it up (asked by the PC, confirmed
 * here with a fingerprint) makes a 32-byte secret K for that PC, kept here only sealed by an Android Keystore key that
 * opens for a strong biometric each time it's used (and is gone if the fingerprints change). Each unlock asks here
 * (a notification, then a fingerprint) and sends K sealed to the PC's unlock key for that one question (UnlockWire). A
 * question nobody answers within a minute is answered "timed out". K is never logged, kept unsealed or sent otherwise.
 */
object Unlock {
    /** A question waiting for its owner's answer: what was asked, by which PC, and when. */
    class Pending(val pc: String, val op: Int, val nonce: ByteArray, val cpPublic: ByteArray, val pcName: String, val at: Long) {
        /** Setting up: the Keystore entry the new key went to (the old one stays until the fingerprint says yes). */
        @Volatile var alias: String? = null
    }
    private val pending = HashMap<String, Pending>()
    /** The PCs set up here (their ids and names), for Settings. */
    val pcs = MutableStateFlow<List<Pair<String, String>>>(emptyList())

    private const val CHANNEL = "unlock"
    private const val NOTIFY = 90_000
    private val store get() = Hub.app.getSharedPreferences("unlock", Context.MODE_PRIVATE)
    /** A PC's key in the Keystore: "unlock-<pc>", or its spare (a new key is made there and kept only once confirmed). */
    private fun alias(pc: String) = store.getString("$pc.alias", null) ?: "unlock-$pc"
    private fun spare(pc: String) = if (alias(pc) == "unlock-$pc") "unlock-$pc-b" else "unlock-$pc"

    fun init(context: Context) {
        Hub.init(context); refresh()
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Unlocking your PC", NotificationManager.IMPORTANCE_HIGH).apply { description = "When a PC asks this phone to unlock it, or to set that up" })
    }
    private fun refresh() { pcs.value = store.all.keys.filter { it.endsWith(".cp") }.map { it.removeSuffix(".cp") }.map { it to (store.getString("$it.name", null) ?: "Your PC") }.sortedBy { it.second.lowercase() } }
    fun setUp(pc: String) = store.contains("$pc.cp") && store.contains("$pc.k")
    private fun shownName(pc: String, asked: String) = Hub.peers.value.firstOrNull { it.id == pc }?.name ?: store.getString("$pc.name", null) ?: asked.ifBlank { "Your PC" }

    /** A PC's question (query 7): asked (the owner is asked here), not set up for it, or forgotten. Null when malformed. */
    fun answer(context: Context, pc: String, payload: ByteArray): ByteArray? {
        if (pc.unhex()?.size != 16) return null
        val r = UnlockWire.request(payload) ?: return null
        init(context)
        return when (r.op) {
            UnlockWire.FORGET -> { forget(pc); UnlockWire.answer(UnlockWire.ASKED) }
            UnlockWire.UNLOCK -> {
                if (!setUp(pc)) return UnlockWire.answer(UnlockWire.NOT_SET_UP)
                val cp = store.getString("$pc.cp", null)?.unhex() ?: return UnlockWire.answer(UnlockWire.NOT_SET_UP)
                ask(context, Pending(pc, r.op, r.nonce!!, cp, shownName(pc, r.pcName), System.currentTimeMillis()))
                UnlockWire.answer(UnlockWire.ASKED)
            }
            else -> {
                ask(context, Pending(pc, r.op, r.nonce!!, r.cpPublic!!, Hub.peers.value.firstOrNull { it.id == pc }?.name ?: r.pcName, System.currentTimeMillis()))
                UnlockWire.answer(UnlockWire.ASKED)
            }
        }
    }

    /** Asks the owner: a notification that opens the fingerprint prompt; unanswered after a minute, the PC hears "timed out". */
    private fun ask(context: Context, p: Pending) {
        synchronized(pending) { pending[p.pc] = p }
        val enrol = p.op == UnlockWire.ENROL
        val open = PendingIntent.getActivity(context, p.pc.hashCode(), Intent(context, UnlockActivity::class.java).putExtra("pc", p.pc).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val decline = PendingIntent.getBroadcast(context, p.pc.hashCode(), Intent(context, UnlockReceiver::class.java).putExtra("pc", p.pc), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle(if (enrol) "Set up unlock for ${p.pcName}?" else "Unlock ${p.pcName}?")
            .setContentText(if (enrol) "Tap, then confirm with your fingerprint" else "Tap to unlock it with your fingerprint")
            .setCategory(NotificationCompat.CATEGORY_STATUS).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
            .setContentIntent(open).addAction(0, "Not now", decline).setTimeoutAfter(UnlockWire.WINDOW_MS).build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFY + (p.pc.hashCode() and 0xFFF), n) }
        Hub.scope.launch { delay(UnlockWire.WINDOW_MS); if (take(p.pc, p) != null) { cancelNotice(context, p.pc); dropSpare(p); tell(p, UnlockWire.TIMED_OUT) } }
    }
    private fun cancelNotice(context: Context, pc: String) = runCatching { NotificationManagerCompat.from(context).cancel(NOTIFY + (pc.hashCode() and 0xFFF)) }

    /** The question waiting for [pc] while it's still good (null once answered or too old). */
    fun waiting(pc: String): Pending? = synchronized(pending) { pending[pc]?.takeIf { System.currentTimeMillis() - it.at < UnlockWire.WINDOW_MS } }
    /** Takes the question off the list (so it's answered once); [only]: just that one. */
    private fun take(pc: String, only: Pending? = null): Pending? = synchronized(pending) {
        val p = pending[pc] ?: return null; if (only != null && p !== only) return null
        pending.remove(pc); p.takeIf { System.currentTimeMillis() - it.at <= UnlockWire.WINDOW_MS + 5_000 }
    }
    /** Sends the PC its answer (notice 9). */
    private fun tell(p: Pending, state: Int, envelope: UnlockWire.Envelope? = null) {
        val frame = UnlockWire.notice(p.op, state, p.nonce, envelope)
        Hub.scope.launch { Hub.link?.notice(p.pc, frame) }
    }
    /** The owner said no (or couldn't confirm). */
    fun decline(context: Context, pc: String) { val p = take(pc) ?: return; cancelNotice(context, pc); dropSpare(p); tell(p, UnlockWire.DECLINED) }
    /** A key made for setting up that wasn't confirmed is deleted (the one in use stays). */
    private fun dropSpare(p: Pending) { val a = p.alias ?: return; if (a != alias(p.pc)) runCatching { keystore().deleteEntry(a) } }
    /** Set up again elsewhere, or its key gone: the PC hears it isn't set up. */
    fun notSetUp(context: Context, pc: String) { val p = take(pc) ?: return; cancelNotice(context, pc); tell(p, UnlockWire.NOT_SET_UP) }
    /** The fingerprints changed since it was set up, so its key is gone for good: everything for it is forgotten. */
    fun invalidated(context: Context, pc: String) { notSetUp(context, pc); forget(pc) }

    // ---- the Keystore ----
    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    /** A fresh AES-256 key under [alias] that opens only for a strong biometric, each use. */
    private fun newKey(alias: String): SecretKey {
        runCatching { keystore().deleteEntry(alias) }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256)
            .setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true)
            .apply { @Suppress("DEPRECATION") if (Build.VERSION.SDK_INT >= 30) setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG) else setUserAuthenticationValidityDurationSeconds(-1) }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }
    /**
     * The cipher the fingerprint prompt unlocks for this question: a new key's, sealing a fresh K (setting up), or the
     * stored one's, opening K (unlocking). Null when there's no key (the PC then hears it isn't set up).
     * Throws KeyPermanentlyInvalidatedException when the fingerprints changed since it was set up.
     */
    fun cipher(p: Pending): Cipher? {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        if (p.op == UnlockWire.ENROL) { val a = spare(p.pc); p.alias = a; c.init(Cipher.ENCRYPT_MODE, newKey(a)); return c }
        val sealed = store.getString("${p.pc}.k", null)?.unhex()?.takeIf { it.size > 12 + 16 } ?: return null
        val key = keystore().getKey(alias(p.pc), null) as? SecretKey ?: return null
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
        return c
    }
    /** The fingerprint said yes: K (made now, or opened), sealed to the PC for this question and sent. False when it couldn't be. */
    fun confirmed(context: Context, p: Pending, c: Cipher): Boolean {
        val now = take(p.pc, p) ?: return false
        cancelNotice(context, p.pc)
        var k: ByteArray? = null
        try {
            if (now.op == UnlockWire.ENROL) {
                k = io.github.arnavdugad.arnavisland.link.Crypto.random(UnlockWire.SECRET)
                val sealed = c.doFinal(k)
                val old = alias(now.pc); val made = now.alias ?: old
                store.edit().putString("${now.pc}.k", (c.iv + sealed).hex()).putString("${now.pc}.cp", now.cpPublic.hex()).putString("${now.pc}.name", now.pcName).putString("${now.pc}.alias", made).commit()
                if (old != made) runCatching { keystore().deleteEntry(old) }
                refresh()
            } else {
                val stored = store.getString("${now.pc}.k", null)?.unhex() ?: run { tell(now, UnlockWire.NOT_SET_UP); return false }
                k = c.doFinal(stored.copyOfRange(12, stored.size))
            }
            val envelope = UnlockWire.seal(now.op, now.nonce, now.cpPublic, k!!) ?: run { tell(now, UnlockWire.DECLINED); return false }
            tell(now, UnlockWire.YES, envelope)
            return true
        } catch (e: Exception) {
            android.util.Log.w("ArnavUnlock", "unlock failed: ${e.javaClass.simpleName}")
            dropSpare(now); tell(now, UnlockWire.DECLINED); return false
        } finally { k?.fill(0) }
    }
    /** Forgets everything kept for [pc]: its key, K and its unlock key. */
    fun forget(pc: String) {
        runCatching { keystore().run { deleteEntry("unlock-$pc"); deleteEntry("unlock-$pc-b") } }
        store.edit().remove("$pc.k").remove("$pc.cp").remove("$pc.name").remove("$pc.alias").commit()
        synchronized(pending) { pending.remove(pc) }
        refresh()
    }
}

/** "Not now" on an unlock notification: the PC hears no. */
class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Hub.init(context)
        intent.getStringExtra("pc")?.let { Unlock.decline(context, it) }
    }
}
