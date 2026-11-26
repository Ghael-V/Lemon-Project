// SPDX-FileCopyrightText: Copyright 2026 Iron Lemon
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.ironlemon.ironlemon_emulator.online

import android.annotation.SuppressLint
import android.provider.Settings
import dev.ironlemon.ironlemon_emulator.YuzuApplication
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * The Lemon Online identity (docs/PROTOCOL.md section 1 in LemonNetwork): a friend code and a
 * secret worked out from the device's ANDROID_ID, which itself never leaves the device. There is
 * no account and nothing to remember. The secret is a credential, and so is the ANDROID_ID:
 * never log either of them.
 */
object OnlineIdentity {
    class Identity(val code: String, val secret: String) {
        override fun toString() = "Identity(code=$code)"
    }

    private val CODE_MESSAGE = "lemon-online/friend-code/v1".toByteArray(Charsets.US_ASCII)
    private val SECRET_SALT = "lemon-online/secret/v1".toByteArray(Charsets.US_ASCII)

    /**
     * Attempt 1 uses the id as it is; when the service says the code is taken, attempt n uses
     * "<id>#n" for both values. Slow on purpose (Argon2id, 64 MiB): call it off the UI thread.
     */
    fun derive(androidId: String, attempt: Int): Identity {
        val input = if (attempt <= 1) androidId else "$androidId#$attempt"
        val bytes = input.toByteArray(Charsets.US_ASCII)
        try {
            return Identity(friendCode(bytes), secret(bytes))
        } finally {
            bytes.fill(0)
        }
    }

    /** First 8 bytes of HMAC-SHA256(key = id, "lemon-online/friend-code/v1"), big-endian, mod 10^12. */
    fun friendCode(id: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(id, "HmacSHA256"))
        val digest = mac.doFinal(CODE_MESSAGE)
        var value = 0UL
        for (i in 0 until 8) {
            value = (value shl 8) or digest[i].toUByte().toULong()
        }
        return (value % 1_000_000_000_000UL).toString().padStart(12, '0')
    }

    /** Argon2id(id, salt "lemon-online/secret/v1", t=3, m=64 MiB, p=1, 32 bytes) as lowercase hex. */
    fun secret(id: ByteArray): String {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(SECRET_SALT)
            .withIterations(3)
            .withMemoryAsKB(65536)
            .withParallelism(1)
            .build()
        val out = ByteArray(32)
        val generator = Argon2BytesGenerator()
        generator.init(params)
        generator.generateBytes(id, out)
        return out.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** "LM-4821-9930-1175" on screen; the service only sees the 12 digits. */
    fun display(code: String) = "LM-" + code.chunked(4).joinToString("-")

    @SuppressLint("HardwareIds")
    fun androidId(): String? = Settings.Secure.getString(
        YuzuApplication.appContext.contentResolver,
        Settings.Secure.ANDROID_ID
    )?.takeIf { it.isNotBlank() }
}
