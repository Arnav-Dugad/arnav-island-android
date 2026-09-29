package io.github.arnavdugad.arnavisland.link

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The island's cryptography, byte for byte as Arnav Island for Windows does it (ShareService.cpp, Windows CNG):
 * static ECDH P-256 keys (public keys on the wire as X then Y, 32 bytes each, big-endian), the shared secret hashed
 * once with SHA-256, and AES-256-GCM with a 12-byte nonce made of a direction byte and a frame counter.
 */
object Crypto {
    private val random = SecureRandom()
    fun random(n: Int) = ByteArray(n).also { random.nextBytes(it) }
    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }
    fun sha256(text: String, vararg parts: ByteArray) = sha256(text.toByteArray(Charsets.UTF_8), *parts)

    private val curve: ECParameterSpec by lazy {
        (KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as ECPublicKey).params
    }
    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    /** A public key as the wire has it: X then Y. */
    fun xy(key: PublicKey): ByteArray { val w = (key as ECPublicKey).w; return fixed(w.affineX) + fixed(w.affineY) }
    private fun fixed(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }
    /** X then Y back into a key, only when the point is on P-256 (a key off the curve is refused). */
    fun publicKey(xy: ByteArray): PublicKey? {
        if (xy.size != 64) return null
        val x = BigInteger(1, xy.copyOfRange(0, 32)); val y = BigInteger(1, xy.copyOfRange(32, 64))
        val field = curve.curve.field as java.security.spec.ECFieldFp; val p = field.p
        if (x >= p || y >= p) return null
        val left = y.modPow(BigInteger.valueOf(2), p); val right = x.modPow(BigInteger.valueOf(3), p).add(curve.curve.a.multiply(x)).add(curve.curve.b).mod(p)
        if (left != right) return null
        return runCatching { KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), curve)) }.getOrNull()
    }
    fun privateKey(pkcs8: ByteArray): PrivateKey? = runCatching { KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8)) }.getOrNull()

    /** ECDH, then SHA-256 of the shared X coordinate (CNG's BCRYPT_KDF_HASH with SHA-256). */
    fun agree(mine: PrivateKey, theirs: ByteArray): ByteArray? {
        val key = publicKey(theirs) ?: return null
        return runCatching { KeyAgreement.getInstance("ECDH").run { init(mine); doPhase(key, true); val z = generateSecret(); sha256(z).also { z.fill(0) } } }.getOrNull()
    }
}

/**
 * One session's AES-256-GCM channel. The nonce is the direction (1 from the side that opened the connection, 2 back)
 * and a counter per direction, so no nonce repeats under a session key; the 16-byte tag follows the ciphertext.
 */
class Channel(key: ByteArray, initiator: Boolean) {
    private val secret = SecretKeySpec(key.copyOf(), "AES")
    private val sendDir = if (initiator) 1 else 2
    private val recvDir = if (initiator) 2 else 1
    private var sent = 0L
    private var received = 0L
    private fun nonce(dir: Int, n: Long) = ByteArray(12).also { it[0] = dir.toByte(); for (i in 0..7) it[4 + i] = (n ushr (8 * i)).toByte() }
    fun seal(plain: ByteArray): ByteArray {
        require(plain.isNotEmpty())
        return Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, nonce(sendDir, sent++))); doFinal(plain) }
    }
    fun open(frame: ByteArray): ByteArray? {
        if (frame.size <= 16) return null
        return runCatching { Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, nonce(recvDir, received++))); doFinal(frame) } }.getOrNull()
    }
}
