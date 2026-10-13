package com.snote.feature.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/** 一次识别的结果。 */
data class OcrResult(
    val text: String,
    val ok: Boolean,
    val message: String = "",
)

/**
 * 端上 OCR 引擎（Tesseract）。
 *
 * ## 为什么是本地引擎而不是云 API
 *
 * 本应用的核心承诺是「数据全存在设备上，不依赖任何云端账号」。把扫描件上传到
 * 别人的服务器做识别，等于直接违反这条 —— 而且扫描件往往正是身份证、合同、
 * 病历这类最敏感的东西。
 *
 * 顺带的好处：不花钱、离线可用、没有调用配额。
 *
 * ## 模型从 assets 解压，不随包直接读
 *
 * Tesseract 的原生层要按**文件路径**读 traineddata，而 assets 里的东西是压缩的
 * 容器内条目、没有真实路径。所以首次使用时解压到 filesDir/tessdata/。
 *
 * 只解压一次：按文件长度判断，不是每次启动都重来（chi_sim 2.4MB + eng 4.1MB）。
 *
 * ## 为什么用 LSTM_ONLY（engineMode = 3）
 *
 * 自带的模型来自 tessdata_fast，它是 8 位整数量化版，**只支持 LSTM 引擎**；
 * 用 legacy 模式会直接初始化失败。这不是偏好，是模型格式的硬约束。
 *
 * ## 线程纪律
 *
 * TessBaseAPI 不是线程安全的，而且 init 一次要几百毫秒 —— 既不能并发调，
 * 也不该每次识别都重建。所以进程内单例 + synchronized。
 */
object OcrEngine {

    /** 解压出来的模型放在这里（Tesseract 的 dataPath 要指向 tessdata 的**父目录**）。 */
    private fun dataRoot(context: Context): File = context.filesDir

    private fun tessdataDir(context: Context): File = File(dataRoot(context), "tessdata")

    /**
     * 随包携带的语言模型。
     *
     * chi_sim 必须有 —— 这是中文笔记应用，识别不了中文等于没有这功能。
     * eng 一起带是因为中英混排（技术文档、代码片段）非常常见，
     * 只给 chi_sim 会把英文单词识别成一堆错字。
     */
    private val ASSETS = listOf("chi_sim.traineddata", "eng.traineddata")

    /** Tesseract 的识别语言串。+ 号表示多语言混合。 */
    private const val LANGUAGES = "chi_sim+eng"

    /** 把 assets 里的模型解压到可读路径；已解压过则跳过。 */
    fun ensureAssets(context: Context): Boolean {
        val dir = tessdataDir(context)
        if (!dir.exists() && !dir.mkdirs()) return false

        for (name in ASSETS) {
            val target = File(dir, name)
            // 长度 > 0 即视为已解压。不做哈希校验：assets 是随包固定内容，
            // 每次启动算 6.5MB 的哈希是白费电。
            if (target.exists() && target.length() > 0) continue
            try {
                context.assets.open("tessdata/" + name).use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                }
            } catch (t: Throwable) {
                // 解压失败就把半截文件删掉，避免下次被「长度 > 0」误判为已完成。
                target.delete()
                return false
            }
        }
        return true
    }

    // ---------- 单例 ----------

    @Volatile private var api: TessBaseAPI? = null
    private val lock = Any()

    /** 取（必要时初始化）引擎。失败返回 null 并给出原因。 */
    private fun obtain(context: Context): Pair<TessBaseAPI?, String> {
        api?.let { return it to "" }

        synchronized(lock) {
            api?.let { return it to "" }

            val app = context.applicationContext
            if (!ensureAssets(app)) {
                return null to "语言模型解压失败，请检查存储空间后重试。"
            }

            val a = TessBaseAPI()
            val ok = try {
                // dataPath 是 tessdata 的父目录，不是 tessdata 本身 ——
                // 传错这一层是 Tesseract 最常见的初始化失败原因。
                a.init(dataRoot(app).absolutePath, LANGUAGES, TessBaseAPI.OEM_LSTM_ONLY)
            } catch (t: Throwable) {
                false
            }
            if (!ok) {
                runCatching { a.recycle() }
                return null to "OCR 引擎初始化失败（模型文件可能损坏，关掉功能再打开可重新解压）。"
            }
            api = a
            return a to ""
        }
    }

    /**
     * 识别一张图。
     *
     * @param onProgress 进度回调（0-100），可能在后台线程被调用。
     */
    fun recognize(context: Context, image: File, onProgress: (Int) -> Unit = {}): OcrResult {
        if (!image.exists() || image.length() == 0L) {
            return OcrResult("", false, "图片文件不存在或为空。")
        }

        /**
         * 先降采样再解码。
         *
         * 直接解码一张 4000x3000 的照片要占约 48MB 堆 —— 而 OCR 对超过约 2000px
         * 的文字并没有额外收益（Tesseract 内部还会再缩放一次）。
         * 这个策略与 AttachmentStore.thumbnail 一致，理由相同。
         */
        val bmp = decodeScaled(image, MAX_SIDE)
            ?: return OcrResult("", false, "图片解码失败（格式不支持或文件损坏）。")

        val (engine, err) = obtain(context)
        if (engine == null) {
            bmp.recycle()
            return OcrResult("", false, err)
        }

        return synchronized(lock) {
            try {
                engine.setImage(bmp)
                val text = engine.getUTF8Text() ?: ""
                onProgress(100)
                OcrResult(text.trim(), true)
            } catch (t: Throwable) {
                OcrResult("", false, "识别失败：" + (t.message ?: t.javaClass.simpleName))
            } finally {
                bmp.recycle()
            }
        }
    }

    /** 释放原生资源。关掉 OCR 功能时调用。 */
    fun release() {
        synchronized(lock) {
            runCatching { api?.recycle() }
            api = null
        }
    }

    /** 模型是否已经就绪（不触发解压与初始化）。 */
    fun assetsReady(context: Context): Boolean =
        ASSETS.all { File(tessdataDir(context), it).let { f -> f.exists() && f.length() > 0 } }

    /** 识别长边上限。见 recognize 里的注释。 */
    private const val MAX_SIDE = 2000

    private fun decodeScaled(file: File, maxSide: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            // 解不出尺寸说明不是图片或文件坏了。表达式体里不能提前 return，
            // 所以这里用块体（编译器对 expression body 里的 return 直接报错）。
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }
        } catch (t: Throwable) {
            null
        }
    }
}
