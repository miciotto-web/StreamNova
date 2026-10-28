package com.example.data.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Contratto per cifrare/decifrare le chiavi API prima della persistenza.
 *
 * *** NON è un meccanismo di condivisione ***: il ciphertext resta nel DataStore
 * dell'app e può essere decifrato solo da questa app con la chiave protetta dal
 * Keystore del dispositivo.
 *
 * Nota per i test: le implementazioni reali usano l'Android Keystore, che non esiste
 * sulla JVM — i test passano un finto [ApiKeyCipher] ad [AppSettingsRepository.init].
 */
interface ApiKeyCipher {
  /** Cifra il testo in chiaro: restituisce un blob prefissato da [ApiKeyCipherFormat.MARKER]. */
  fun encrypt(plain: String): String

  /** Decifra un blob prodotto da [encrypt]; null se il blob non è decifrabile. */
  fun decrypt(stored: String): String?
}

/** Formato/etichette del blob cifrato salvato nelle preferenze (logica pura, testabile su JVM). */
object ApiKeyCipherFormat {

  /**
   * Prefisso che contraddistingue una chiave API salvata CIFRATA. Un valore historic
   * senza prefisso (scritto da una build precedente) resta leggibile come testo in
   * chiaro e viene ricifrato al primo salvataggio successivo.
   */
  const val MARKER: String = "osenc1:"

  fun isEncrypted(stored: String): Boolean = stored.startsWith(MARKER)

  fun payload(stored: String): String = stored.removePrefix(MARKER)
}

/**
 * Cifratura reale con Android Keystore: AES/GCM/NoPadding con chiave non esportabile
 * all'alias [KEY_ALIAS]. Il vettore di inizializzazione è casuale per ogni cifratura
 * e viene anteposto al ciphertext nel blob in Base64.
 *
 * Nota onesta: protegge la chiave AT REST dalla lettura diretta del file DataStore
 * (root/backup). Non difende da un attaccante che esegue codice nel processo app.
 */
class AndroidKeystoreApiKeyCipher(
  private val keyAlias: String = DEFAULT_KEY_ALIAS
) : ApiKeyCipher {

  private fun obtainKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    generator.init(
      KeyGenParameterSpec.Builder(
        keyAlias,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
      )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    )
    return generator.generateKey()
  }

  override fun encrypt(plain: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
    val iv = cipher.iv
    val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
    val blob = ByteArray(iv.size + ciphertext.size)
    iv.copyInto(blob)
    ciphertext.copyInto(blob, iv.size)
    return ApiKeyCipherFormat.MARKER + Base64.encodeToString(blob, Base64.NO_WRAP)
  }

  override fun decrypt(stored: String): String? {
    if (!ApiKeyCipherFormat.isEncrypted(stored)) return null
    return try {
      val blob = Base64.decode(ApiKeyCipherFormat.payload(stored), Base64.NO_WRAP)
      if (blob.size <= IV_SIZE_BYTES) return null
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(
        Cipher.DECRYPT_MODE,
        obtainKey(),
        GCMParameterSpec(GCM_TAG_BITS, blob, 0, IV_SIZE_BYTES)
      )
      String(cipher.doFinal(blob, IV_SIZE_BYTES, blob.size - IV_SIZE_BYTES), Charsets.UTF_8)
    } catch (e: Exception) {
      // Chiave del Keystore invalidata (es. cancellazione utente, cambio lock screen):
      // il blob non è più leggibile → trattato come "non configurata".
      Log.w(TAG, "Blob chiave API non decifrabile: ${e.javaClass.simpleName}")
      null
    }
  }

  companion object {
    private const val TAG = "ApiKeyCipher"
    const val DEFAULT_KEY_ALIAS = "streamnova_os_api_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE_BYTES = 12
    private const val GCM_TAG_BITS = 128
  }
}
