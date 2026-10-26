package com.mukho.ssammubot.utils

import org.springframework.core.io.ClassPathResource
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtil {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_LENGTH_BIT = 128

    private val secretKeySpec: SecretKeySpec = run {
        val rawSecret = try {
            val props = Properties()
            props.load(ClassPathResource("api.properties").inputStream)
            props.getProperty("nexon.api.key") ?: "ssammubot-default-secret-key"
        } catch (e: Exception) {
            "ssammubot-default-secret-key"
        }
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(rawSecret.toByteArray(Charsets.UTF_8))
        SecretKeySpec(keyBytes, "AES")
    }

    fun encrypt(plainText: String): String {
        val iv = ByteArray(IV_SIZE)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, GCMParameterSpec(TAG_LENGTH_BIT, iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)

        return Base64.getUrlEncoder().withoutPadding().encodeToString(combined)
    }

    fun decrypt(cipherText: String): String? {
        return try {
            val decoded = Base64.getUrlDecoder().decode(cipherText)
            if (decoded.size <= IV_SIZE) return null

            val iv = decoded.copyOfRange(0, IV_SIZE)
            val encrypted = decoded.copyOfRange(IV_SIZE, decoded.size)

            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, GCMParameterSpec(TAG_LENGTH_BIT, iv))
            val decrypted = cipher.doFinal(encrypted)

            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
