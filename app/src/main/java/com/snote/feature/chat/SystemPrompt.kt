package com.snote.feature.chat

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 对话的 system 提示词。
 *
 * ## 为什么必须自己下发
 *
 * 在此之前，应用**从不**给模型任何 system 内容 —— CodeBuddyClient 只在调用方没给时
 * 补一句硬编码的 "You are a helpful assistant."。后果实测（2026-10-11，真机直连）：
 * 问「今天是几号」，模型连续三次答出 2025 年 2 月的不同日期，全是编造。
 * 它没有时钟，也没人告诉它今天是哪天。
 *
 * ## 日期必须在**每次请求时**重新生成
 *
 * 不能做成常量：常量在进程启动时求值一次，App 常驻后台几天后就会拿旧日期去回答。
 * 因此这里是个函数，由 [build] 每次调用现取。
 *
 * ## 为什么不写更多指令
 *
 * 只写「身份 + 日期 + 能力边界」。模型本身已经在用工具，不需要在这里重复教它
 * 怎么调 kb_search —— 那由工具声明里的 description 承担，写两遍只会互相矛盾。
 */
object SystemPrompt {

    /** 当前日期，形如「2026年10月11日 星期日」。 */
    fun today(now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA).format(Date(now))

    /**
     * 组装一次请求用的 system 提示词。
     *
     * @param webEnabled 联网搜索开关。开着时告诉模型可以搜；关着时**明确说没有联网能力**——
     *   这句不是客套：没有它模型会凭训练记忆编造「最新」信息（实测过，见下面日期那段），
     *   有它才会老实说「查不到」。
     */
    fun build(
        now: Long = System.currentTimeMillis(),
        webEnabled: Boolean = false,
        /** 长期记忆那一段（由 MemoryStore.promptSection 生成）。空串表示没有记忆。 */
        memorySection: String = "",
    ): String =
        "你是 SNOTE 的知识库助手，帮用户在他的私人笔记里检索、整理和记录内容。\n\n" +
            "今天是 " + today(now) + "。\n\n" +
            // 记忆放在「身份」之后、「能力」之前：先告诉它「你在跟谁说话」，
            // 再说「你能做什么」。顺序反了会让模型先想着能调什么工具，
            // 再看到用户偏好时就容易被当成可忽略的补充信息。
            memorySection +
            "你可以调用工具访问用户的知识库：读操作（检索、列文档、读文档）会直接执行；" +
            "写操作（新建、修改、删除）会先进入待审队列，等用户在界面上点「同意」才真正落库。" +
            "所以你提出写操作后，告诉用户「已在待审区等你确认」即可，不要以为失败了而重复请求。\n\n" +
            webHint(webEnabled) +
            "用户提到的日期以本提示词给出的今天为准 —— 他说的「今天」「昨天」都据此换算，" +
            "不要用你记忆里的年份去否定他。"

    /**
     * 能力边界那一段。**必须与工具声明一致**：关了联网却在这里说「你能搜」，
     * 模型会去调一个不存在的工具；开着却在这里说「你不能搜」，它宁可编也不调用。
     */
    private fun webHint(webEnabled: Boolean): String = if (webEnabled)
        "你**能**联网：需要最新信息（今日新闻、实时价格、当前版本、刚发生的事）时，调用 " +
            "web_search 去搜，不要凭记忆回答。搜索结果里有链接，引用事实时请带上来源链接，" +
            "并只复述结果里确实写了的内容。\n\n"
    else
        "你**没有**联网能力，只能看到知识库和本提示词。遇到需要实时信息（今日天气、最新价格、" +
            "刚发生的新闻）的问题，直接说明你查不到，不要让用户误以为你能上网，" +
            "也不要用记忆里的旧数据冒充最新数据。\n\n"
}
