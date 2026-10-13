package com.snote.core.credential

import android.content.Context
import org.json.JSONArray

/**
 * CodeBuddy 账号存储（多账号）。
 *
 * ## 为什么用 SharedPreferences 而不是知识库的 SQLite 表
 *
 * 凭据是**配置**，不是知识；它不该跟着「知识库导出 / 备份」一起走。
 * 放独立的 SharedPreferences 还省掉一次 DB 版本迁移 —— 迁移有风险，
 * 而这里没有任何查询需求（个人级账号，几十个封顶）。
 *
 * ## 明文存储的边界
 *
 * token 以明文存在应用私有目录。个人单机自用可接受；
 * **若将来要做云同步，这里必须先换成 Keystore 加密**，否则等同于把登录态上传。
 */
class CredentialStore(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<CodeBuddyAccount> {
        val raw = sp.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length())
                .mapNotNull { i -> arr.optJSONObject(i)?.let { CodeBuddyAccount.fromJson(it) } }
                .filter { it.accessToken.isNotBlank() }
        } catch (t: Throwable) {
            // 存储损坏时返回空表而不是崩溃：用户重新登录的成本远低于打不开 App。
            emptyList()
        }
    }

    private fun persist(accounts: List<CodeBuddyAccount>) {
        val arr = JSONArray()
        accounts.forEach { arr.put(it.toJson()) }
        sp.edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    /** 新增或按 id 覆盖。登录成功与刷新成功都走这里。 */
    fun upsert(account: CodeBuddyAccount) {
        val all = list().toMutableList()
        val idx = all.indexOfFirst { it.id == account.id }
        if (idx >= 0) all[idx] = account else all.add(account)
        persist(all)
        if (activeId() == null) setActive(account.id)
    }

    fun remove(id: String) {
        persist(list().filterNot { it.id == id })
        if (activeId() == id) {
            sp.edit().putString(KEY_ACTIVE, list().firstOrNull()?.id ?: "").apply()
        }
    }

    fun activeId(): String? = sp.getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() }

    fun setActive(id: String) {
        sp.edit().putString(KEY_ACTIVE, id).apply()
    }

    fun active(): CodeBuddyAccount? {
        val id = activeId() ?: return list().firstOrNull()
        return list().firstOrNull { it.id == id } ?: list().firstOrNull()
    }

    companion object {
        // 刻意用新的 prefs 名：旧的 workbuddy 账号与 codebuddy 不是同一套后端，
        // 复用会拿错产品的凭据去发请求。
        private const val PREFS = "snote.codebuddy"
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_ACTIVE = "activeId"
    }
}