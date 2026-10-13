package com.snote.core.credential

import android.util.Base64
import org.json.JSONObject
import java.util.UUID

/**
 * 一个 CodeBuddy 账号的凭据。
 *
 * 字段与 PC 端探针落盘的 credential-buddy.json 一一对应，
 * 以便两端能用同一份数据互相对照排错。
 *
 * 注意 expiresAtMs 的来源：服务端**不返回绝对时间**（实测 expires_at 是空串），
 * 因此统一从 access_token 这个 JWT 的 exp 声明解析 —— 它才是权威值。
 */
data class CodeBuddyAccount(
    val id: String,
    val label: String,
    val accessToken: String,
    val refreshToken: String,
    /** 毫秒时间戳；解析不到时为 0（表示「未知」，不当作已过期）。 */
    val expiresAtMs: Long,
    val domain: String,
    val userId: String,
) {
    /** 提前 5 分钟视为过期，留出刷新余量。未知（0）时不算过期。 */
    val isExpiringSoon: Boolean
        get() = expiresAtMs > 0 && System.currentTimeMillis() >= expiresAtMs - 5 * 60 * 1000L

    val hasRefreshToken: Boolean get() = refreshToken.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("accessToken", accessToken)
        put("refreshToken", refreshToken)
        put("expiresAtMs", expiresAtMs)
        put("domain", domain)
        put("userId", userId)
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

        fun fromJson(o: JSONObject): CodeBuddyAccount = CodeBuddyAccount(
            id = o.optString("id"),
            label = o.optString("label"),
            accessToken = o.optString("accessToken"),
            refreshToken = o.optString("refreshToken"),
            expiresAtMs = o.optLong("expiresAtMs", 0L),
            domain = o.optString("domain"),
            userId = o.optString("userId"),
        )

        /**
         * 从 JWT 的 payload 读 exp（秒）并换算为毫秒。非 JWT 或解析失败返回 0。
         *
         * 只做 base64url 解码、**不验签** —— 这个值只用于展示与决定何时刷新，
         * 真正的有效性由服务端判定；验签在这里没有意义（密钥不在客户端）。
         */
        fun expiresAtFromJwt(token: String): Long {
            if (token.isBlank()) return 0L
            val parts = token.split('.')
            if (parts.size < 2) return 0L
            return try {
                val raw = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                val exp = JSONObject(String(raw, Charsets.UTF_8)).optLong("exp", 0L)
                if (exp > 0) exp * 1000 else 0L
            } catch (t: Throwable) {
                0L
            }
        }

        /** 从 JWT 读用户标识（uid / sub），用于给账号起个可辨认的名字。 */
        fun subjectFromJwt(token: String): String {
            val parts = token.split('.')
            if (parts.size < 2) return ""
            return try {
                val raw = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                val o = JSONObject(String(raw, Charsets.UTF_8))
                val uid = o.optString("uid", "")
                if (uid.isNotBlank()) uid else o.optString("sub", "")
            } catch (t: Throwable) {
                ""
            }
        }
    }
}