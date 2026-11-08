package io.github.arnavdugad.arnavisland.link

import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Revision 13 (island 0.30): unlocking the PC with this phone (query 7 and notice 9). The PC's unlock component has its own
 * P-256 key pair (cp); this phone keeps a 32-byte secret K for it, sealed by Android's Keystore behind a strong biometric.
 * When its owner says yes, K goes to the PC sealed to cp: a fresh ephemeral key pair, the ECDH secret's X coordinate,
 * key = SHA-256("arnav-unlock-1" ‖ that X ‖ nonce), and AES-256-GCM with a random 12-byte IV over K with the nonce and op
 * as associated data. Only cp's private key can open it. Plain JVM code: the tests open what it seals as the PC would.
 */
object UnlockWire {
    const val ENROL = 0; const val UNLOCK = 1; const val FORGET = 2
    /** Notice 9's states; and query 7's answers (1 asked, 3 not set up for this PC). */
    const val YES = 1; const val DECLINED = 2; const val NOT_SET_UP = 3; const val TIMED_OUT = 4
    const val ASKED = 1
    const val LABEL = "arnav-unlock-1"
    const val POINT = 65; const val NONCE = 32; const val SECRET = 32; const val IV = 12; const val SEALED = SECRET + 16
    /** How long a question stays good for an answer. */
    const val WINDOW_MS = 60_000L

    class Request(val op: Int, val cpPublic: ByteArray?, val pcName: String, val nonce: ByteArray?)
    /** A question's payload, each part checked (cp must be a P-256 point, the nonce 32 bytes); null when it's malformed. */
    fun request(payload: ByteArray): Request? {
        val r = Reader(payload)
        return when (val op = r.u8()) {
            ENROL -> { val cp = r.blob(POINT) ?: return null; val name = r.string(1024) ?: return null; val nonce = r.blob(NONCE) ?: return null
                if (publicKey(cp) == null || nonce.size != NONCE) return null; Request(op, cp, cleanName(name), nonce) }
            UNLOCK -> { val nonce = r.blob(NONCE) ?: return null; val name = r.string(1024) ?: return null
                if (nonce.size != NONCE) return null; Request(op, null, cleanName(name), nonce) }
            FORGET -> Request(op, null, "", null)
            else -> null
        }
    }
    fun answer(state: Int) = byteArrayOf(state.toByte())

    /** An uncompressed point (0x04, X, Y) into a key, only when it is on P-256. */
    fun publicKey(point: ByteArray): PublicKey? = if (point.size == POINT && point[0].toInt() == 4) Crypto.publicKey(point.copyOfRange(1, POINT)) else null
    fun point(key: PublicKey): ByteArray = byteArrayOf(4) + Crypto.xy(key)

    class Envelope(val ephemeral: ByteArray, val iv: ByteArray, val sealed: ByteArray)
    private fun key(shared: ByteArray, nonce: ByteArray) = Crypto.sha256(LABEL, fixed(shared), nonce)
    private fun fixed(x: ByteArray): ByteArray = when { x.size == 32 -> x; x.size > 32 -> x.copyOfRange(x.size - 32, x.size); else -> ByteArray(32 - x.size) + x }
    private fun aad(nonce: ByteArray, op: Int) = nonce + byteArrayOf(op.toByte())

    /**
     * K sealed to the PC's unlock key [cpPublic] for this [nonce] and [op]: a fresh ephemeral key pair and IV each time.
     * [ephemeral] and [iv] are given only by tests (a fixed vector); never reuse either for real.
     */
    fun seal(op: Int, nonce: ByteArray, cpPublic: ByteArray, k: ByteArray, random: SecureRandom = SecureRandom(), ephemeral: KeyPair? = null, iv: ByteArray? = null): Envelope? {
        if (k.size != SECRET || nonce.size != NONCE || (iv != null && iv.size != IV)) return null
        val cp = publicKey(cpPublic) ?: return null
        val eph: KeyPair = ephemeral ?: Crypto.newKeyPair()
        val shared = KeyAgreement.getInstance("ECDH").run { init(eph.private); doPhase(cp, true); generateSecret() }
        val key = key(shared, nonce); shared.fill(0)
        val used = iv ?: ByteArray(IV).also { random.nextBytes(it) }
        val sealed = try { Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, used)); updateAAD(aad(nonce, op)); doFinal(k) } } finally { key.fill(0) }
        return Envelope(point(eph.public), used, sealed)
    }
    /** What the PC's unlock component does: K, or null when anything doesn't match (another nonce, op or key). */
    fun open(op: Int, nonce: ByteArray, e: Envelope, cpPrivate: PrivateKey): ByteArray? = runCatching {
        if (e.iv.size != IV || e.sealed.size != SEALED) return null
        val eph = publicKey(e.ephemeral) ?: return null
        val shared = KeyAgreement.getInstance("ECDH").run { init(cpPrivate); doPhase(eph, true); generateSecret() }
        val key = key(shared, nonce)
        Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, e.iv)); updateAAD(aad(nonce, op)); doFinal(e.sealed) }
    }.getOrNull()

    /** Notice 9: [0x30, 9, op, state, blob nonce] and, said yes, the envelope (blob ephemeral, blob iv, blob sealed). */
    fun notice(op: Int, state: Int, nonce: ByteArray, e: Envelope? = null): ByteArray {
        val b = Bytes().u8(Proto.FRAME_NOTICE).u8(Proto.NOTICE_UNLOCK).u8(op).u8(state).blob(nonce)
        if (state == YES && e != null) b.blob(e.ephemeral).blob(e.iv).blob(e.sealed)
        return b.build()
    }
    /** Notice 9 read back from its op on (as the PC hands it to its unlock component): op, state, nonce and the envelope. */
    fun parseNotice(fromOp: ByteArray): Pair<Triple<Int, Int, ByteArray>, Envelope?>? {
        val r = Reader(fromOp); val op = r.u8() ?: return null; val state = r.u8() ?: return null; val nonce = r.blob(NONCE) ?: return null
        if (state != YES) return Triple(op, state, nonce) to null
        val e = Envelope(r.blob(POINT) ?: return null, r.blob(IV) ?: return null, r.blob(SEALED) ?: return null)
        return Triple(op, state, nonce) to e
    }

    // The PC's side, for tests.
    fun enrolRequest(cpPublic: ByteArray, pcName: String, nonce: ByteArray) = Bytes().u8(ENROL).blob(cpPublic).string(pcName).blob(nonce).build()
    fun unlockRequest(nonce: ByteArray, pcName: String) = Bytes().u8(UNLOCK).blob(nonce).string(pcName).build()
    fun forgetRequest() = byteArrayOf(FORGET.toByte())
}
