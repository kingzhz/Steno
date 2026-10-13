package com.snote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.snote.core.settings.ChoiceStore
import com.snote.ui.AppRoot
import com.snote.ui.theme.Accent
import com.snote.ui.theme.SnoteTheme
import com.snote.ui.theme.ThemePreset

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
}
