package com.snote.core.feature

import android.content.Context
import com.snote.feature.attachments.AttachmentStore
import com.snote.feature.notes.NoteRepository

/** 一次开关动作的结果，用于给用户反馈。 */
data class FeatureActionResult(
    val ok: Boolean,
    val message: String,
)

/**
 * 功能开关的**真正执行者**。
 *
 * ## 为什么必须有这个类
 *
 * FeatureStateStore 只把「开/关」记进 setting 表。如果没人执行卸载与重建，
 * 开关就只是界面上动了一下 —— 用户关掉「全文检索」，FTS 表还在、
 * 搜索还照样能用，那这个开关就是骗人的。
 *
 * 本类把「开关状态」翻译成对派生物的实际动作：
 *
 *   search  → 关闭：dropFts() 整表删除 + VACUUM 还磁盘；开启：rebuildFts() 重扫
 *   thumbs  → 关闭：删掉 files/thumbs 下全部缩略图；开启：不预生成（按需生成）
 *   其余    → 无派生物，只记状态
 *
 * ## 顺序纪律
 *
 * 先执行动作、成功了再写状态。反过来的话，动作失败时状态已经是「已关闭」，
 * 界面上显示关了而实际没关，用户再也点不回来。
 */
class FeatureController(private val context: Context) {

    private val state = FeatureStateStore(context)
    private val repo = NoteRepository(context)
    private val attachments = AttachmentStore(context)

    /**
     * 切换一个功能。
     *
     * @return 执行结果；ok=false 时状态**未**改变。
     */
    fun setEnabled(def: FeatureDef, enabled: Boolean): FeatureActionResult {
        val action = try {
            if (enabled) enable(def) else disable(def)
        } catch (t: Throwable) {
            return FeatureActionResult(false, "操作失败：" + (t.message ?: t.javaClass.simpleName))
        }
        if (!action.ok) return action

        state.setEnabled(def.id, enabled)

        // 关掉某功能会让依赖它的功能被迫停用（FeatureRegistry 已算出传递闭包）。
        // 只改状态不执行它们的卸载 —— 依赖者本身没有独立派生物，
        // 它们的「不可用」由调用方读 requires 判断。
        return action
    }

    private fun disable(def: FeatureDef): FeatureActionResult = when (def.id) {
        FeatureRegistry.SEARCH.id -> {
            repo.dropFts()
            repo.dbHelper.vacuum()
            FeatureActionResult(true, "已删除全文索引并回收磁盘空间。卡片内容未动，重新打开会自动重建。")
        }
        FeatureRegistry.THUMBS.id -> {
            val n = attachments.clearThumbs()
            FeatureActionResult(true, "已删除 " + n + " 张缩略图。原图保留。")
        }
        else -> FeatureActionResult(true, "已关闭「" + def.title + "」。源数据未动。")
    }

    private fun enable(def: FeatureDef): FeatureActionResult = when (def.id) {
        FeatureRegistry.SEARCH.id -> {
            repo.rebuildFts()
            val rows = repo.ftsRowCount()
            FeatureActionResult(true, "已重建全文索引，共 " + rows + " 张卡片。")
        }
        FeatureRegistry.THUMBS.id ->
            // 刻意不预生成：几百份附件全量转码要几分钟，而用户可能只看其中几张。
            FeatureActionResult(true, "已打开。缩略图会在浏览时按需生成。")
        else -> FeatureActionResult(true, "已打开「" + def.title + "」。")
    }

    /** 某功能当前是否可用（自身开着，且它依赖的功能都开着）。 */
    fun isUsable(def: FeatureDef): Boolean {
        if (!state.isEnabled(def.id)) return false
        return def.requires.all { state.isEnabled(it) }
    }
}
