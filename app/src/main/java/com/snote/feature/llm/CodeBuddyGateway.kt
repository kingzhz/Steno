package com.snote.feature.llm

import com.snote.core.credential.CodeBuddyAccount
import com.snote.core.credential.CodeBuddyClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * 把 CodeBuddy 接进统一的 LlmGateway 抽象。
 *
 * 存在的意义是让上层不必知道「这条路由是 CodeBuddy」—— 对话页、以后的
 * agent 工具循环都只面对 LlmGateway，换供应商不影响它们。
 *
 * 模型列表走一次网络请求，因此 listModels 是 suspend 的；而 chat 直接转发。
 */
class CodeBuddyGateway(
    private val client: CodeBuddyClient,
    private val accountProvider: () -> CodeBuddyAccount?,
    private val onRefreshed: (CodeBuddyAccount) -> Unit = {},
) : LlmGateway {

    override suspend fun isReady(): Boolean = accountProvider()?.accessToken?.isNotBlank() == true

    /** 模型清单：优先 scoped + config 并集；全失败时给插件兜底表，至少能选。 */
    override suspend fun listModels(): List<String> {
        val account = accountProvider() ?: return FALLBACK_MODELS
        val live = withContext(Dispatchers.IO) { client.listModels(account) }
        return live.ifEmpty { FALLBACK_MODELS }
    }

    override fun chat(
        model: String,
        messages: List<LlmMessage>,
        tools: List<String>,
    ): Flow<LlmChunk> = flow {
        val account = accountProvider()
        if (account == null) {
            emit(LlmChunk.Failed("尚未登录 CodeBuddy。到「设置」里添加账号。"))
            emit(LlmChunk.Done)
            return@flow
        }
        // 过期就先续期，省得让用户看到一条注定 401 的失败。
        val use = if (account.isExpiringSoon && account.hasRefreshToken) {
            withContext(Dispatchers.IO) { client.refresh(account) }?.also(onRefreshed) ?: account
        } else {
            account
        }
        client.chat(use, model, messages, tools).collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    companion object {
        /**
         * 静态兜底模型表。
         *
         * 取自插件实测的 CodeBuddy 兜底目录 —— 只在两个远端端点都失败时使用。
         * 刻意保留 default-model 这类别名：它们是服务端 agent 实际引用的 id。
         */
        val FALLBACK_MODELS: List<String> = listOf(
            "hy4-preview",
            "hy4-preview-x",
            "hy3",
            "hy3-x",
            "glm-5.3",
            "glm-5.3-flash",
            "deepseek-v4-flash",
            "kimi-k2.8-preview",
        )
    }
}