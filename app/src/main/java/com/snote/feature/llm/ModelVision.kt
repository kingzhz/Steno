package com.snote.feature.llm

/**
 * 模型能不能识图。
 *
 * ## 为什么不能只看服务端的声明
 *
 * 服务端**两个端点对同一个模型会给出互相矛盾的声明**（实测 2026-09 留存于
 * codearts 插件的源码注释）：
 *
 *   glm-5.1  scoped 端点 /console/enterprises/personal/models  →  supportsImages: false
 *   glm-5.1  /v3/config                                        →  supportsImages: true
 *   真实请求（纯红图 + 问颜色）                                 →  答出「红色」
 *
 * 若只信 scoped 端点，glm-5.1 会被判成纯文本，用户贴图时被本地拦下 ——
 * 而图片本来能正常送到上游。插件的解法是**显式白名单**而不是「兜底 true 优先」：
 * 通用规则会让白名单永久压过远端，某模型真下线时用户会被放行后撞上游错误，
 * 错误更晚、更难懂。
 *
 * ## 本项目的做法：只覆盖已实测确认的个案
 *
 * 其余一律**放行**。依据是另一条实测（2026-10-11）：非识图模型收到图片时
 * **不报错**，而是模型自己礼貌拒绝（hunyuan-chat 答「我目前无法查看图片，
 * 请换用支持图片的多模态模型」）。既然最坏结果只是一句礼貌拒绝，
 * 提前拦截的收益就很小、误伤的风险却很大。
 */
object ModelVision {

    /**
     * 已知**确定支持**图片的模型。
     *
     * 取自服务端 /v3/config 与 scoped 端点的 supportsImages=true 集合
     * （2026-10-11 实测），并保留 hy4-preview 这一实测跑通过的老条目。
     * 这张表只用于「默认识图模型」这类需要确定性的场景，不作为拦截依据。
     */
    private val KNOWN_SUPPORTED: Set<String> = setOf(
        "hy4-preview", "hy4-preview-x", "hy3", "hy3-x",
        "deepseek-v4.1-flash", "deepseek-v4-flash", "deepseek-v4-pro",
        "glm-5.3", "glm-5.3-flash", "glm-5.2", "glm-5.1",
        "glm-5v-turbo", "glm-4.6v",
        "kimi-k3-1", "kimi-k2.8-preview", "kimi-k2.7", "kimi-k2.6", "kimi-k2.5",
        "minimax-m3", "step-5-preview", "space-bunny",
    )

    /**
     * 远端明确说 supportsImages=false、但**实测能看图**的模型。
     *
     * 用白名单而不是「兜底表 true 优先」的通用规则：通用规则会让本表永久压过
     * 远端，模型真下线时用户被放行后才撞错误，比提前拦下更难排查。
     * 新增条目必须先有一次真实请求的证据。
     */
    private val OVERRIDES: Set<String> = setOf(
        // scoped 端点误报 false，实测发纯红图能答出颜色。
        "glm-5.1",
    )

    /**
     * 该模型「确定」能识图吗。
     *
     * 返回 false **不代表不能用图片** —— 只是「不确定」。调用方据此决定
     * 要不要提示，而不是据此拦截（理由见类注释）。
     */
    fun certainlySupports(model: String): Boolean =
        model in OVERRIDES || model in KNOWN_SUPPORTED

    /** 从候选里挑一个确定能识图的，用作识图模型的下拉选项。 */
    fun visionCandidates(models: List<String>): List<String> =
        models.filter { certainlySupports(it) }
}
