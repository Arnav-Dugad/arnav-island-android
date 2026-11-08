package io.github.arnavdugad.arnavisland

import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.arnavdugad.arnavisland.link.UnlockWire

/**
 * 1.13: the fingerprint prompt for a PC's unlock question (opened from its notification), over a see-through screen.
 * The Keystore key opens only inside the prompt, for this one question; a cancel, an error or "Not now" tells the PC no.
 */
class UnlockActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Unlock.init(this)
        val pc = intent.getStringExtra("pc")
        val p = pc?.let { Unlock.waiting(it) }
        if (pc == null || p == null) { say("That request has expired. Try again on your PC"); finish(); return }
        val enrol = p.op == UnlockWire.ENROL
        if (BiometricManager.from(this).canAuthenticate(BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
            say("Add a fingerprint in Android's settings first"); Unlock.decline(this, pc); finish(); return
        }
        val cipher = try { Unlock.cipher(p) }
            catch (_: KeyPermanentlyInvalidatedException) { say("Your fingerprints changed, so set up unlock on ${p.pcName} again"); Unlock.invalidated(this, pc); finish(); return }
            catch (e: Exception) { android.util.Log.w("ArnavUnlock", "no key: ${e.javaClass.simpleName}"); say("This phone couldn't keep a key for that"); Unlock.decline(this, pc); finish(); return }
        if (cipher == null) { Unlock.notSetUp(this, pc); finish(); return }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val c = result.cryptoObject?.cipher
                val ok = c != null && Unlock.confirmed(this@UnlockActivity, p, c)
                if (c == null) Unlock.decline(this@UnlockActivity, pc)
                say(if (!ok) "That didn't work. Try again on your PC" else if (enrol) "Unlock is set up for ${p.pcName}" else "Unlocking ${p.pcName}")
                finish()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Unlock.decline(this@UnlockActivity, pc)
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON && errorCode != BiometricPrompt.ERROR_CANCELED) say(errString.toString())
                finish()
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (enrol) "Set up unlock for ${p.pcName}" else "Unlock ${p.pcName}")
            .setSubtitle(if (enrol) "Your fingerprint will unlock this PC from now on" else "Confirm it's you")
            .setNegativeButtonText("Cancel").setAllowedAuthenticators(BIOMETRIC_STRONG).setConfirmationRequired(false).build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
    private fun say(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
}
