package com.snote.feature.llm

/**
 * 模型清单的内存缓存。
 *
 * 拉一次模型列表要发两个网络请求（scoped + config），而用户每次点进对话页都会
 * 重新组合一次 —— 不缓存的话，进一次对话页就白烧两次请求。清单本身一天也不会变，
 * 所以缓存到进程结束，只在用户显式点「刷新」时失效。
 *
 * 缓存的是**结果**而不是 gateway 实例：登录态变化时调用方负责 invalidate()。
 */
class ModelCatalog(private val gatewayProvider: () -> LlmGateway?) {

    @Volatile
    private var cache: List<String>? = null

    /**
     * 取模型清单。
     *
     * [force] 为 true 时忽略缓存重新拉取（对应界面上的「刷新」）。
     * 拉取失败或为空时回落到 [CodeBuddyGateway.FALLBACK_MODELS]，
     * 保证选择器永远有东西可选 —— 没网时也不该是个空菜单。
     */
    suspend fun models(force: Boolean = false): List<String> {
        if (!force) cache?.let { return it }
        val gw = gatewayProvider()
        val live = try {
            gw?.listModels().orEmpty()
        } catch (t: Throwable) {
            emptyList()
        }
        val result = live.ifEmpty { CodeBuddyGateway.FALLBACK_MODELS }
        cache = result
        return result
    }

    /** 登录态变化（新增/切换/删除账号）后必须调用，否则会一直用上一个账号的清单。 */
    fun invalidate() {
        cache = null
    }
}
