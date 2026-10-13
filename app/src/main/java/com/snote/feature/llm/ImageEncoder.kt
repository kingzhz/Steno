package com.snote.feature.llm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 把用户选的图片转成能发给模型的形式。
 *
 * ## 为什么必须缩放，不能直接发原图
 *
 * 腾讯网关按「单次请求图片视觉 token 总量」计价，上限约 100,000 token，
 * 折算比例实测约 617 像素 = 1 token（见 codearts 插件的 image-budget.ts）。
 * 一张 4,000,000 像素的手机截图约 6,600 token —— 十五张就顶穿上限，
 * 此后该会话每一轮都失败。所以发出前一律缩到长边 MAX_EDGE。
 *
 * ## 为什么用 Base64 data URL 而不是上传图床
 *
 * 实测（2026-10-11）：CodeBuddy 直接接受 data URL 形态的图片，模型能正确
 * 认出内容（纯红图答出「红色」）。既然内联可用，就不必引入上传、存储、
 * 过期清理这一整条链路 —— 少一层依赖就少一处失败点。
 *
 * ## 线程
 *
 * 解码与编码要跑几百毫秒到几秒，函数本身不切线程，调用方必须放在
 * 主线程之外。
 */
object ImageEncoder {

    /**
     * 一张待发送的图片。
     *
     * [dataUrl] 是发给模型的图；[thumbDataUrl] 是同一张的小图，**存进对话历史用**。
     *
     * 为什么要两份：模型需要 1568 长边才看得清细节，而历史里只需要「看得出是
     * 哪张图」。同一张图存原尺寸会让历史表迅速膨胀（一张 1568px 的 JPEG
     * 约 200KB，一百条带图消息就是 20MB），而缩略图只有几 KB，滚动列表也更快。
     */
    data class Encoded(
        val dataUrl: String,
        val thumbDataUrl: String,
        val width: Int,
        val height: Int,
        val bytes: Int,
    )

    /**
     * 从 content Uri 读图并编码。
     *
     * @return 编码结果；读不到（Uri 失效、不是图片、内存不足）时返回 null，
     *   由调用方提示用户，而不是抛异常炸掉整轮发送。
     */
    fun fromUri(context: Context, uri: Uri): Encoded? {
        val bitmap = try {
            decodeScaled(context, uri)
        } catch (t: Throwable) {
            null
        } ?: return null
        return try {
            encode(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 两段式解码：先只读尺寸，算出采样率，再真正解码。
     *
     * 一步到位地解码会把整张原图读进内存 —— 用户拍的一张 4000x3000 图
     * 就是 48MB 位图，在低端机上直接 OOM。
     */
    private fun decodeScaled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(context, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        val opts = BitmapFactory.Options().apply {
            // inSampleSize 只能是 2 的幂。这里让它一次降到接近目标，
            // 后面再用 createScaledBitmap 精确收口 —— 只靠采样会缩过头。
            inSampleSize = sampleSizeFor(w, h)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = open(context, uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        return scaleTo(raw)
    }

    private fun open(context: Context, uri: Uri): InputStream? =
        try {
            context.contentResolver.openInputStream(uri)
        } catch (t: Throwable) {
            null
        }

    /** 采样率：把长边降到 MAX_EDGE 附近所需的最小 2 的幂。 */
    private fun sampleSizeFor(w: Int, h: Int): Int {
        var sample = 1
        var longEdge = maxOf(w, h)
        while (longEdge / 2 >= MAX_EDGE) {
            longEdge /= 2
            sample *= 2
        }
        return sample
    }

    /** 精确缩放：采样后长边仍超过 MAX_EDGE 才真的缩放。 */
    private fun scaleTo(src: Bitmap): Bitmap {
        val longEdge = maxOf(src.width, src.height)
        if (longEdge <= MAX_EDGE) return src
        val ratio = MAX_EDGE.toFloat() / longEdge
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        val out = Bitmap.createScaledBitmap(src, w, h, true)
        if (out !== src) src.recycle()
        return out
    }

    /**
     * 编码成 JPEG data URL。
     *
     * 选 JPEG 而不是 PNG：截图类图片 PNG 更大，而模型识别质量几乎无差别。
     * 质量取 QUALITY —— 再高只是让请求体变大，对识别没有可见收益。
     */
    private fun encode(bitmap: Bitmap): Encoded? {
        val bytes = ByteArrayOutputStream()
        val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, bytes)
        if (!ok || bytes.size() == 0) return null
        val raw = bytes.toByteArray()
        // NO_WRAP：Base64 默认每 76 字符插一个换行，那会让 JSON 里出现
        // 裸换行符 —— 服务端解析失败，而报错信息完全看不出是这里的问题。
        val full = DATA_PREFIX + Base64.encodeToString(raw, Base64.NO_WRAP)

        // 顺手产一张缩略图：此时位图已在内存里，再缩一次几乎不花时间；
        // 等历史里需要时再解原图反而要重新解码一遍。
        val thumbBitmap = scaleToEdge(bitmap, THUMB_EDGE)
        val thumb = try {
            compressToDataUrl(thumbBitmap, THUMB_QUALITY, THUMB_PREFIX)
        } finally {
            if (thumbBitmap !== bitmap) thumbBitmap.recycle()
        }

        return Encoded(
            dataUrl = full,
            // 缩略图生成失败不该让整张图作废 —— 退化成「历史里也没有缩略图」，
            // 那种情况下界面显示占位符，但发送与识别完全不受影响。
            thumbDataUrl = thumb ?: full,
            width = bitmap.width,
            height = bitmap.height,
            bytes = raw.size,
        )
    }

    /**
     * 从已经编码好的 data URL 补生成缩略图。
     *
     * 用在「历史里只有原尺寸图、没有缩略图」的补救路径上（老数据）。
     * 正常路径由 [encode] 一次产出两份，不必走这里。
     */
    fun thumbFromDataUrl(dataUrl: String): String? {
        val raw = try {
            Base64.decode(dataUrl.substringAfter(",", ""), Base64.DEFAULT)
        } catch (t: Throwable) {
            return null
        }
        val src = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        return try {
            val small = scaleToEdge(src, THUMB_EDGE)
            try {
                compressToDataUrl(small, THUMB_QUALITY, THUMB_PREFIX)
            } finally {
                if (small !== src) small.recycle()
            }
        } finally {
            src.recycle()
        }
    }

    /** 把位图缩到长边不超过 [edge]（已够小则原样返回）。 */
    private fun scaleToEdge(src: Bitmap, edge: Int): Bitmap {
        val longEdge = maxOf(src.width, src.height)
        if (longEdge <= edge) return src
        val ratio = edge.toFloat() / longEdge
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /** 压成 JPEG data URL。压缩失败返回 null。 */
    private fun compressToDataUrl(src: Bitmap, quality: Int, prefix: String): String? {
        val out = ByteArrayOutputStream()
        val ok = src.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (!ok || out.size() == 0) return null
        return prefix + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private const val DATA_PREFIX = "data:image/jpeg;base64,"

    /**
     * 长边上限。
     *
     * 1568 是多个网关的通行值，按 token 预算反推也贴合：
     * 1568×1568 约 2.46M 像素约 3,980 token，单张占 100K 预算的 4%，
     * 一次挂 4 张仅用掉约 16%。再大收益递减、风险递增。
     */
    private const val MAX_EDGE = 1568

    private const val QUALITY = 85

    private const val THUMB_PREFIX = "data:image/jpeg;base64,"

    /**
     * 缩略图长边。
     *
     * 320 够看出「是哪张图」，且在手机列表里显示不会糊。再大对大图与滚动
     * 都没有可见收益 —— 历史里可能同时有一百条带图消息。
     */
    private const val THUMB_EDGE = 320

    /** 缩略图质量。小图上 70 与 85 肉眼无差，体积差一倍。 */
    private const val THUMB_QUALITY = 70
}
