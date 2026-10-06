package com.sukisu.ultra.ui.license

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Card key licensing, verified entirely on the phone.
 *
 * A card key looks like   base64url(payload) "." base64url(signature)
 * and the payload is JSON:  {"u":"name","t":"pro","exp":"2027-01-01"}
 *
 * The developer keeps an ECDSA P-256 private key and signs payloads with it. The app carries only
 * the matching public key and checks the signature locally, which means:
 *   - the app works with no network at all,
 *   - nothing about the phone is ever sent anywhere,
 *   - the developer's server being down cannot lock anybody out.
 *
 * Keys are produced with the tooling in tools/license (see its README).
 */
object LicenseManager {
    private const val TAG = "PaperSULicense"
    private const val PREFS = "papersu_license"
    private const val KEY_CARD = "card_key"

    /**
     * The public key that matches the private key used to sign card keys.
     * Paste the base64 of an X.509 SubjectPublicKeyInfo here; tools/license/genkey.py prints it.
     * The matching private key stays on the developer machine and signs the card keys.
     */
    private const val PUBLIC_KEY_BASE64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEUK6tHfC0jM90XtZmpXJtyUsabvvlxpNlxCQFvAUj9d3hfyFIsZ5MrETEFClhHzxGYxq6tEDN8BUHiw6Kqv2ZhQ=="

    data class License(
        val user: String,
        val tier: String,
        val expiry: String,
        val cardKey: String,
    ) {
        val isPro: Boolean
            get() = tier.equals("pro", true) ||
                tier.equals("vip", true) ||
                tier.equals("premium", true)
    }

    sealed interface Outcome {
        data class Ok(val license: License) : Outcome
        data class Bad(val reason: String) : Outcome
    }

    // ------------------------------------------------------------------ storage

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The licence currently in force, or null when nothing valid is stored. */
    fun current(context: Context): License? {
        val stored = prefs(context).getString(KEY_CARD, null) ?: return null
        return when (val parsed = verify(stored)) {
            is Outcome.Ok -> parsed.license
            is Outcome.Bad -> {
                Log.i(TAG, "stored card key no longer valid: ${parsed.reason}")
                null
            }
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_CARD).apply()
    }

    // ------------------------------------------------------------------ activation

    /**
     * Check a card key and remember it when it is good.
     * Purely local: verifies the signature, reads the payload, rejects it if it has expired.
     */
    fun activate(context: Context, cardKey: String): Outcome {
        val trimmed = cardKey.trim()
        if (trimmed.isEmpty()) return Outcome.Bad("卡密是空的")
        return when (val result = verify(trimmed)) {
            is Outcome.Ok -> {
                prefs(context).edit().putString(KEY_CARD, trimmed).apply()
                Log.i(TAG, "activated for ${result.license.user} until ${result.license.expiry}")
                result
            }

            is Outcome.Bad -> result
        }
    }

    /** Verify a card key without touching storage. */
    fun verify(cardKey: String): Outcome {
        if (PUBLIC_KEY_BASE64.length < 40) {
            return Outcome.Bad("还没有内置公钥：先用 tools/license/genkey.py 生成密钥对，再把公钥填进 LicenseManager")
        }
        val dot = cardKey.lastIndexOf('.')
        if (dot <= 0 || dot == cardKey.length - 1) {
            return Outcome.Bad("卡密格式不对，应该是 载荷.签名")
        }

        val payloadPart = cardKey.substring(0, dot)
        val signaturePart = cardKey.substring(dot + 1)

        val payloadBytes = runCatching { base64UrlDecode(payloadPart) }.getOrElse {
            return Outcome.Bad("卡密载荷不是合法的 base64")
        }
        val signatureBytes = runCatching { base64UrlDecode(signaturePart) }.getOrElse {
            return Outcome.Bad("卡密签名不是合法的 base64")
        }

        val signatureOk = runCatching {
            val keyBytes = Base64.decode(PUBLIC_KEY_BASE64, Base64.DEFAULT)
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(payloadBytes)
            verifier.verify(signatureBytes)
        }.getOrElse {
            Log.w(TAG, "signature check blew up", it)
            return Outcome.Bad("验签出错：${it.message}")
        }
        if (!signatureOk) return Outcome.Bad("签名对不上，这个卡密不是本应用签发的")

        val payload = String(payloadBytes, Charsets.UTF_8)
        val obj = runCatching { JSONObject(payload) }.getOrElse {
            return Outcome.Bad("载荷不是合法 JSON")
        }

        val user = obj.optString("u", "")
        val tier = obj.optString("t", "").ifBlank { "pro" }
        val expiry = obj.optString("exp", "")

        if (expiry.isNotBlank() && isExpired(expiry)) {
            return Outcome.Bad("卡密已于 $expiry 到期")
        }
        return Outcome.Ok(License(user, tier, expiry, cardKey))
    }

    /** Expiry is an ISO date, yyyy-MM-dd, so a plain string compare matches date order. */
    private fun isExpired(expiry: String): Boolean {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        return expiry.trim() < today
    }

    private fun base64UrlDecode(text: String): ByteArray {
        val padded = when (text.length % 4) {
            2 -> "$text=="
            3 -> "$text="
            else -> text
        }
        return Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
    }
}