package com.snote

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.snote.core.settings.ChoiceStore
import com.snote.feature.share.ShareReceiver
import com.snote.ui.AppRoot
import com.snote.ui.theme.Accent
import com.snote.ui.theme.SnoteTheme
import com.snote.ui.theme.ThemePreset

class MainActivity : ComponentActivity() {

    /**
     * 处理分享进来的内容。
     *
     * ## 为什么在 onCreate 里同步做完再接 UI
     *
     * 导入附件要读 content:// 流、算哈希、写库 —— 可能几百 MB。放后台线程、
     * 让界面先出来，用户会看到一篇空笔记，几秒后内容才「长」出来；
     * 更糟的是他可能在这期间就把它删了。分享的语义是「我已经给你了」，
     * 所以先落地、再显示。
     *
     * 代价是冷启动接大文件时会多等一会 —— 用 Toast 告知正在导入，
     * 而不是让界面静默无响应。
     */
    private fun handleShare(intent: Intent?) {
        if (!ShareReceiver.isShare(intent)) return
        Toast.makeText(this, "正在导入分享内容…", Toast.LENGTH_SHORT).show()
        val r = ShareReceiver.handle(this, intent!!)
        Toast.makeText(this, r.message, if (r.ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 必须在 setContent 之前：导入过程要访问数据库，先让内容落定，
        // 首帧渲染时列表就已经能看到新笔记，不会出现「保存了但列表是空的」。
        handleShare(intent)
        enableEdgeToEdge()
        setContent {
            val choices = remember { ChoiceStore(applicationContext) }

            /**
             * 配色状态提到这一层。
             *
             * 主题必须包住 AppRoot，所以状态不能在 AppRoot 里面；而设置页要能改它，
             * 于是用回调往下传。
             *
             * 存的是枚举**名**而不是序号：将来枚举顺序调整时，已存的用户偏好
             * 不会跟着错位到别的颜色上。
             */
            var preset by remember {
                mutableStateOf(
                    runCatching {
                        ThemePreset.valueOf(choices.get(ChoiceStore.THEME_PRESET) ?: "")
                    }.getOrDefault(ThemePreset.LIGHT)
                )
            }
            var accent by remember {
                mutableStateOf(
                    runCatching {
                        Accent.valueOf(choices.get(ChoiceStore.THEME_ACCENT) ?: "")
                    }.getOrDefault(Accent.SKY)
                )
            }

            SnoteTheme(preset = preset, accent = accent) {
                AppRoot(
                    preset = preset,
                    accent = accent,
                    onThemeChange = { p, a ->
                        preset = p
                        accent = a
                        choices.put(ChoiceStore.THEME_PRESET, p.name)
                        choices.put(ChoiceStore.THEME_ACCENT, a.name)
                    },
                )
            }
        }
    }

    /**
     * App 已在前台/后台时又收到一次分享。
     *
     * 不处理的话：用户看完一篇再分享第二篇，第二篇会被静默丢掉 ——
     * Activity 复用不会重走 onCreate。用 singleTop 启动模式让这里能被调到。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }
}
