package cloud.dopp.homes.teilen

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What the phone keeps for „Teilen per Berührung“ (owner 2026-11-28: „Ja,
 * aber auch Karten die ich ausgelesen habe“): the kept tags and the person's
 * own card, encrypted with an AES key that never leaves Android's Keystore
 * (AES-GCM, a fresh IV per write) — not the deprecated
 * EncryptedSharedPreferences. Nothing of it goes to Homes, into a backup
 * (the app allows none) or into a log; without the key it cannot be opened.
 */
object Tresor {

    private const val PREFS = "tresor"
    private const val ALIAS = "homes_tresor"
    private const val GCM_TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** The key; in tests one made in memory (Robolectric has no Keystore). */
    @VisibleForTesting
    @Volatile
    var schluessel: () -> SecretKey = ::keystoreSchluessel

    private fun keystoreSchluessel(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** [text] kept under [name], encrypted; null removes it. */
    fun schreiben(ctx: Context, name: String, text: String?) {
        if (text == null) return p(ctx).edit { remove(name) }
        p(ctx).edit { putString(name, verschluesseln(text.toByteArray(Charsets.UTF_8))) }
    }

    /** What is kept under [name]; null when nothing is, or it cannot be opened (the key gone). */
    fun lesen(ctx: Context, name: String): String? =
        p(ctx).getString(name, null)?.let { runCatching { String(entschluesseln(it), Charsets.UTF_8) }.getOrNull() }

    /** IV and ciphertext, Base64. */
    fun verschluesseln(klar: ByteArray): String {
        val c = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, schluessel()) }
        return Base64.encodeToString(c.iv + c.doFinal(klar), Base64.NO_WRAP)
    }

    fun entschluesseln(text: String): ByteArray {
        val alles = Base64.decode(text, Base64.NO_WRAP)
        val iv = alles.copyOf(IV)
        val c = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, schluessel(), GCMParameterSpec(GCM_TAG_BITS, iv)) }
        return c.doFinal(alles, IV, alles.size - IV)
    }

    /** GCM's IV as the Keystore makes it. */
    private const val IV = 12
}
