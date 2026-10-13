package com.snote.core.feature

/** 重建代价分级。 */
enum class FeatureCost {
    /** 纯本地，随时可重建。 */
    FREE,

    /** 本地但耗时（缩略图、FTS 重扫）。 */
    LOCAL_HEAVY,

    /** 要调外部 API，重建 = 花钱，开关必须弹预算门。 */
    PAID_API
}

/**
 * 一个功能模块的定义。
 *
 * 开关不是布尔值，而是这套声明：依赖谁 / 关掉卸载什么 / 重建要多久多少钱。
 * 关功能 = 卸载派生物，绝不等于删源数据。
 */
data class FeatureDef(
    val id: String,
    val title: String,
    val summary: String,
    /** 前置功能 id；关掉本功能不影响它们，但关掉它们会让本功能一并停用。 */
    val requires: Set<String> = emptySet(),
    /** 本功能拥有的派生物 kind（对应 derived.kind / 独立表 / 独立文件目录）。 */
    val derivedKinds: Set<String> = emptySet(),
    val cost: FeatureCost = FeatureCost.FREE,
    /** 重建的人话描述，用于预算门弹窗。 */
    val rebuildNote: String? = null,
    val defaultEnabled: Boolean = true,
) {
    val isPaid: Boolean get() = cost == FeatureCost.PAID_API
}
