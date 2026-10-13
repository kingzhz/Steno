package com.snote.feature.llm

import kotlinx.coroutines.flow.Flow

/**
 * 一条对话消息。
 *
 * toolCallId / toolCallsJson 只有工具循环用得上（batch 2），纯聊天时留空。
 * 现在就加上是为了避免那时候再改一遍接口。
 */
data class LlmMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolCallsJson: String? = null,
    /**
     * 随这条消息一起发出的图片（data URL 形态，见 [ImageEncoder]）。
     *
     * 刻意与 [content] 分开存而不是把 content 改成多模态结构：
     * 工具循环、历史持久化、待审摘要等处都按「content 是纯文本」在用，
     * 改它的类型会牵动一大片。有图时由 CodeBuddyClient.buildBody 组装成
     * 服务端要的数组形态 —— 协议细节只在那一个地方。
     */
    val images: List<String> = emptyList(),
)

/** 流式返回的一段增量。 */
sealed interface LlmChunk {
    data class Delta(val text: String) : LlmChunk
    data class ToolCall(val id: String, val name: String, val argumentsJson: String) : LlmChunk
    data class Failed(val message: String) : LlmChunk
    data object Done : LlmChunk
}

/**
 * LLM 调用抽象。
 *
 * 必须从第一天就是接口 —— 目前只有一个实现（CodeBuddyGateway），
 * 但契约按「会有多个」设计：
 *   1. CodeBuddy 网关（本文件旁，复用官方 IDE 的登录态与模型池）
 *   2. 将来若接本机直连 provider，只加实现不动上层
 *   3. 测试用桩实现
 * 如果第一版写成裸 fetch，将来插第二个后端就是大手术。
 */
interface LlmGateway {

    /** 该网关是否可用（凭据是否就绪等）。 */
    suspend fun isReady(): Boolean

    /** 可用的模型 id 列表。 */
    suspend fun listModels(): List<String>

    /**
     * 流式对话。
     * [tools] 为可选的工具声明（JSON Schema 字符串列表），为空表示纯聊天。
     */
    fun chat(
        model: String,
        messages: List<LlmMessage>,
        tools: List<String> = emptyList()
    ): Flow<LlmChunk>
}
