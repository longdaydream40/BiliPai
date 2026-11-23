package com.android.purebilibili.feature.plugin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.purebilibili.core.input.gamepad.GamepadControlEngine
import com.android.purebilibili.core.input.gamepad.GamepadControlMode
import com.android.purebilibili.core.input.gamepad.GamepadScene
import com.android.purebilibili.core.ui.components.AppText

/**
 * 全局手柄视觉覆盖层（挂载于 MainActivity 与 VideoActivity 最顶层，不拦截任何触控事件）：
 * 1. 绘制丝滑虚拟光标（支持按下拖拽高亮与 LT 慢速精准瞄准态）；
 * 2. 显示顶部轻提示条（手柄连接/断开、模式切换、倍速提示）；
 * 3. 可选底部紧凑 Xbox 键位提示 HUD（随浏览/视频详情/全屏场景自动切换）。
 */
@Composable
fun GamepadOverlayHost(
    modifier: Modifier = Modifier,
) {
    val state by GamepadControlEngine.uiState.collectAsStateWithLifecycle()
    val config by GamepadControlEngine.configFlow.collectAsStateWithLifecycle()
    if (!state.pluginEnabled) return

    val cursorAlpha by animateFloatAsState(
        targetValue = if (state.cursorVisible) 1f else 0f,
        label = "gamepad_cursor_alpha",
    )

    Box(modifier = modifier.fillMaxSize()) {
        // 1. 顶部状态轻提示
        AnimatedVisibility(
            visible = !state.statusBanner.isNullOrBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 12.dp),
        ) {
            val text = state.statusBanner.orEmpty()
            Box(
                modifier = Modifier
                    .background(
                        color = Color.Black.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(999.dp),
                    )
                    .border(
                        width = 0.8.dp,
                        color = Color.White.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(999.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                AppText(
                    text = text,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // 2. 底部紧凑 Xbox 键位提示条（仅连接手柄且开启 HUD 时展示）
        val showHud = state.connected && state.hudVisible && config.showLegendHud
        AnimatedVisibility(
            visible = showHud,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            val hints = remember(state.scene, state.mode, state.cursorVisible) {
                resolveLegendHints(
                    scene = state.scene,
                    mode = state.mode,
                    cursorVisible = state.cursorVisible,
                )
            }
            Row(
                modifier = Modifier
                    .background(
                        color = Color.Black.copy(alpha = 0.62f),
                        shape = RoundedCornerShape(999.dp),
                    )
                    .border(
                        width = 0.7.dp,
                        color = Color.White.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(999.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                hints.forEach { (keyBadge, actionLabel) ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .background(
                                    color = Color.White.copy(alpha = 0.18f),
                                    shape = RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        ) {
                            AppText(
                                text = keyBadge,
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        AppText(
                            text = actionLabel,
                            color = Color.White.copy(alpha = 0.88f),
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }

        // 3. 虚拟光标（纯绘制，不消费任何事件）
        if (cursorAlpha > 0.01f) {
            val center = Offset(state.cursorX, state.cursorY)
            val pressed = state.pointerPressed
            val precision = state.precisionAimHeld
            Canvas(modifier = Modifier.fillMaxSize()) {
                val outerRadius = when {
                    pressed -> 13.dp.toPx()
                    precision -> 11.dp.toPx()
                    else -> 15.dp.toPx()
                }
                val accentColor = when {
                    pressed -> Color(0xFFFB7299)
                    precision -> Color(0xFF38BDF8)
                    else -> Color.White
                }
                // 外圈暗影提升亮色背景下的可见度
                drawCircle(
                    color = if (pressed) {
                        Color(0xFFFB7299).copy(alpha = 0.28f * cursorAlpha)
                    } else {
                        Color.Black.copy(alpha = 0.24f * cursorAlpha)
                    },
                    radius = outerRadius + 3.dp.toPx(),
                    center = center,
                )
                drawCircle(
                    color = accentColor.copy(alpha = 0.94f * cursorAlpha),
                    radius = outerRadius,
                    center = center,
                    style = Stroke(width = 2.4.dp.toPx()),
                )
                drawCircle(
                    color = accentColor.copy(alpha = 0.96f * cursorAlpha),
                    radius = 2.8.dp.toPx(),
                    center = center,
                )
            }
        }
    }
}

private fun resolveLegendHints(
    scene: GamepadScene,
    mode: GamepadControlMode,
    cursorVisible: Boolean,
): List<Pair<String, String>> {
    return when (scene) {
        GamepadScene.VIDEO_FULLSCREEN -> if (cursorVisible) {
            listOf(
                "LS" to "光标",
                "A" to "点击/拖",
                "B" to "退出",
                "RT" to "按住倍速",
                "X" to "弹幕",
                "L3" to "隐光标",
            )
        } else {
            listOf(
                "A" to "播/停",
                "B" to "退出",
                "LB/RB" to "±10s",
                "RT" to "按住倍速",
                "X" to "弹幕",
                "L3" to "光标",
            )
        }
        GamepadScene.VIDEO_DETAIL -> listOf(
            "LS" to if (mode == GamepadControlMode.CURSOR) "光标" else "焦点",
            "RS" to "滚动",
            "A" to "选择",
            "B" to "返回",
            "Y" to "全屏/长按三连",
            "RT" to "倍速",
        )
        GamepadScene.BROWSING -> listOf(
            "LS" to if (mode == GamepadControlMode.CURSOR) "光标" else "焦点",
            "RS" to "滚动",
            "A" to "选择",
            "B" to "返回",
            "LB/RB" to "切页",
            "X" to "搜索",
            "View" to "切模式",
        )
    }
}
