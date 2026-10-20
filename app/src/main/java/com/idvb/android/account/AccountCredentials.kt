package com.idvb.android.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Passwords are never persisted. Tokens are encrypted and excluded from backup/device transfer. */
internal class AccountCredentials(context: Context) : TokenStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "account-session.enc"))
    private val keys get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun encryptionKey(): SecretKey {
        (keys.getKey("idvb.account.token.v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("idvb.account.token.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    @Synchronized override fun load(): String? = runCatching {
        if (!file.baseFile.exists()) return null
        val data = file.openRead().use { it.readBytes() }
        require(data.size in 29..4096)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
    }.getOrNull()

    @Synchronized override fun save(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val bytes = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    @Synchronized override fun clear() { file.delete() }

    @Synchronized fun prove(operation: String, fields: Map<String, String>): JSONObject {
        val alias = "idvb.account.installation.v1"
        if (!keys.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
        val issuedAt = System.currentTimeMillis() / 1000
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keys.getKey(alias, null) as PrivateKey)
            update(AuthProof.message(operation, fields, issuedAt))
        }.sign()
        return JSONObject(fields).put("device", JSONObject()
            .put("publicKeySpki", AuthProof.base64(keys.getCertificate(alias).publicKey.encoded))
            .put("issuedAt", issuedAt).put("signature", AuthProof.base64(AuthProof.p1363(signature))))
    }
}
