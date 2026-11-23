@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.android.purebilibili.feature.plugin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.purebilibili.core.input.gamepad.GamepadConfig
import com.android.purebilibili.core.input.gamepad.GamepadControlEngine
import com.android.purebilibili.core.input.gamepad.GamepadControlMode
import com.android.purebilibili.core.input.gamepad.GamepadLiveTelemetry
import com.android.purebilibili.core.ui.AppContentColors
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.AppSurfaceTokens
import com.android.purebilibili.core.ui.ContainerLevel
import com.android.purebilibili.core.ui.components.AppFilterChip
import com.android.purebilibili.core.ui.components.AppHorizontalDivider
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppSlider
import com.android.purebilibili.core.ui.components.AppSurface
import com.android.purebilibili.core.ui.components.AppSwitchPreference
import com.android.purebilibili.core.ui.components.AppText
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GamepadControlSettings(
    plugin: GamepadControlPlugin,
    modifier: Modifier = Modifier,
) {
    var uiConfig by remember { mutableStateOf(plugin.snapshotConfig()) }
    val uiState by GamepadControlEngine.uiState.collectAsStateWithLifecycle()
    val telemetry by GamepadControlEngine.telemetry.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        plugin.loadConfigSuspend()
        uiConfig = plugin.snapshotConfig()
    }

    fun update(next: GamepadConfig) {
        uiConfig = next
        plugin.commitConfig(next)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // 连接状态与实时摇杆测试卡片
        GamepadStatusAndTesterCard(
            connected = uiState.connected,
            controllerName = uiState.controllerName,
            activeMode = uiState.mode,
            telemetry = telemetry,
        )

        Spacer(modifier = Modifier.height(14.dp))
        AppText(
            text = "默认操控模式",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = AppContentColors.accent(),
        )
        AppText(
            text = "随时可按手柄 View（返回）键在两种模式之间一键切换。",
            style = MaterialTheme.typography.bodySmall,
            color = AppSurfaceTokens.onSurfaceVariantSummary(),
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                GamepadControlMode.CURSOR to "智能光标（推荐）",
                GamepadControlMode.FOCUS to "焦点导航",
            ).forEach { (mode, label) ->
                AppFilterChip(
                    selected = uiConfig.defaultMode == mode,
                    onClick = { update(uiConfig.copy(defaultMode = mode)) },
                    label = { AppText(text = label, maxLines = 1, overflow = TextOverflow.Clip) },
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        AppText(
            text = "摇杆与速度",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = AppContentColors.accent(),
            modifier = Modifier.padding(bottom = 8.dp),
        )

        GamepadSliderRow(
            icon = Icons.Outlined.SportsEsports,
            title = "左摇杆光标灵敏度",
            valueLabel = "${(uiConfig.cursorSensitivity * 100).roundToInt()}%",
            value = uiConfig.cursorSensitivity,
            valueRange = 0.5f..2.2f,
            onValueChange = { uiConfig = uiConfig.copy(cursorSensitivity = it) },
            onValueChangeFinished = { update(uiConfig) },
        )
        GamepadSliderRow(
            icon = Icons.Outlined.Tune,
            title = "右摇杆滚动速度",
            valueLabel = "${(uiConfig.scrollSensitivity * 100).roundToInt()}%",
            value = uiConfig.scrollSensitivity,
            valueRange = 0.5f..2.2f,
            onValueChange = { uiConfig = uiConfig.copy(scrollSensitivity = it) },
            onValueChangeFinished = { update(uiConfig) },
        )
        GamepadSliderRow(
            icon = Icons.Outlined.Tune,
            title = "摇杆防漂移死区",
            valueLabel = "${(uiConfig.stickDeadzone * 100).roundToInt()}%",
            value = uiConfig.stickDeadzone,
            valueRange = 0.08f..0.32f,
            onValueChange = { uiConfig = uiConfig.copy(stickDeadzone = it) },
            onValueChangeFinished = { update(uiConfig) },
        )

        Spacer(modifier = Modifier.height(10.dp))
        AppText(
            text = "播放页按住 RT 倍速",
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(1.5f, 2.0f, 2.5f, 3.0f).forEach { speed ->
                AppFilterChip(
                    selected = kotlin.math.abs(uiConfig.rtBoostSpeed - speed) < 0.05f,
                    onClick = { update(uiConfig.copy(rtBoostSpeed = speed)) },
                    label = { AppText(text = "${speed}x", maxLines = 1, overflow = TextOverflow.Clip) },
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        AppHorizontalDivider(color = AppSurfaceTokens.divider())
        AppSwitchPreference(
            icon = Icons.Outlined.Movie,
            title = "播放页手柄快捷操控",
            subtitle = "开启后在详情页与全屏支持按住 RT 倍速、LB/RB 快进退、X 弹幕、Y 全屏/长按三连",
            checked = uiConfig.playerShortcutsEnabled,
            onCheckedChange = { update(uiConfig.copy(playerShortcutsEnabled = it)) },
        )
        AppSwitchPreference(
            icon = Icons.Outlined.Visibility,
            title = "底部键位提示 HUD",
            subtitle = "连接手柄后在屏幕底部显示当前场景键位提示（可随时按 R3 开关）",
            checked = uiConfig.showLegendHud,
            onCheckedChange = { update(uiConfig.copy(showLegendHud = it)) },
        )
        AppSwitchPreference(
            icon = Icons.Outlined.Speed,
            title = "光标悬停卡片高亮",
            subtitle = "移动光标经过视频卡片时触发悬浮抬升反馈",
            checked = uiConfig.hoverHighlightEnabled,
            onCheckedChange = { update(uiConfig.copy(hoverHighlightEnabled = it)) },
        )
        AppSwitchPreference(
            icon = Icons.Outlined.TouchApp,
            title = "触屏与静止自动隐藏光标",
            subtitle = "手指触屏或 4.5 秒无摇杆操作时淡出光标，不遮挡观看",
            checked = uiConfig.autoHideCursor,
            onCheckedChange = {
                update(uiConfig.copy(autoHideCursor = it, hideCursorOnTouch = it))
            },
        )
        AppSwitchPreference(
            icon = Icons.Outlined.Tune,
            title = "反转右摇杆滚动方向",
            subtitle = "默认向下推右摇杆向下浏览列表，开启后变为自然拖动方向",
            checked = uiConfig.invertRightStickScroll,
            onCheckedChange = { update(uiConfig.copy(invertRightStickScroll = it)) },
        )

        Spacer(modifier = Modifier.height(16.dp))
        GamepadKeymapReferenceCard()
    }
}

@Composable
private fun GamepadStatusAndTesterCard(
    connected: Boolean,
    controllerName: String?,
    activeMode: GamepadControlMode,
    telemetry: GamepadLiveTelemetry,
) {
    val accent = AppContentColors.accent()
    val container = if (connected) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        AppSurfaceTokens.cardContainer()
    }
    AppSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.container(ContainerLevel.Card),
        color = container,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIcon(
                    imageVector = Icons.Outlined.SportsEsports,
                    contentDescription = null,
                    tint = if (connected) accent else AppSurfaceTokens.onSurfaceVariantSummary(),
                    modifier = Modifier.size(24.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    AppText(
                        text = if (connected) {
                            controllerName ?: "已连接游戏手柄"
                        } else {
                            "未检测到游戏手柄"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    AppText(
                        text = if (connected) {
                            val modeLabel = if (activeMode == GamepadControlMode.CURSOR) "智能光标模式" else "焦点导航模式"
                            "当前：$modeLabel · 最近按键：${telemetry.lastButtonLabel}"
                        } else {
                            "通过蓝牙或 USB 连接 Xbox 手柄后即可免配置操控全软件"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = AppSurfaceTokens.onSurfaceVariantSummary(),
                    )
                }
            }

            if (connected) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StickPreviewDial(label = "左摇杆 (LS)", x = telemetry.leftX, y = telemetry.leftY, accent = accent)
                    StickPreviewDial(label = "右摇杆 (RS)", x = telemetry.rightX, y = telemetry.rightY, accent = accent)
                }
            }
        }
    }
}

@Composable
private fun StickPreviewDial(
    label: String,
    x: Float,
    y: Float,
    accent: Color,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(modifier = Modifier.size(56.dp)) {
            val radius = size.minDimension / 2f - 4.dp.toPx()
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                color = accent.copy(alpha = 0.25f),
                radius = radius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx()),
            )
            val dotCenter = Offset(
                x = center.x + x * (radius - 6.dp.toPx()),
                y = center.y + y * (radius - 6.dp.toPx()),
            )
            drawCircle(
                color = accent,
                radius = 5.5.dp.toPx(),
                center = dotCenter,
            )
        }
        AppText(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AppSurfaceTokens.onSurfaceVariantSummary(),
        )
    }
}

@Composable
private fun GamepadSliderRow(
    icon: ImageVector,
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppIcon(
                imageVector = icon,
                contentDescription = null,
                tint = AppContentColors.accent(),
                modifier = Modifier.size(20.dp),
            )
            AppText(title, style = MaterialTheme.typography.bodyLarge)
        }
        AppText(
            text = valueLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = AppSurfaceTokens.onSurfaceVariantSummary(),
        )
    }
    AppSlider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GamepadKeymapReferenceCard() {
    val rows = listOf(
        "左摇杆 (LS)" to "移动虚拟光标（焦点模式下移动焦点）",
        "右摇杆 (RS)" to "全局平滑滚动页面/评论区（全屏隐藏光标时调亮度/进度）",
        "A 键" to "点击选择 / 按住配合左摇杆拖拽（进度条、下拉刷新）",
        "B 键" to "全局回退（优先关闭弹窗/全屏，再返回上一页）",
        "X 键" to "浏览页打开搜索 / 播放页开关弹幕",
        "Y 键" to "浏览页回顶刷新 / 播放页短按全屏、长按一键三连",
        "LB / RB" to "浏览页切换底栏页面 / 播放页快退快进 10 秒（按住 LT 切分 P）",
        "LT / RT" to "按住 LT 光标慢速精准瞄准 / 播放页按住 RT 倍速播放",
        "十字键" to "焦点微调步进 / 播放页左右 ±5 秒、上下调音量",
        "Start / View" to "Start：设置或播停 · View：切换光标/焦点模式",
        "L3 / R3" to "L3：隐藏或唤出光标 · R3：开关底部键位提示条",
    )
    AppSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.container(ContainerLevel.Card),
        color = AppSurfaceTokens.cardContainer(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppText(
                text = "Xbox 手柄键位说明",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = AppContentColors.accent(),
            )
            rows.forEach { (keyName, desc) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    AppText(
                        text = keyName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(0.32f),
                    )
                    AppText(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppSurfaceTokens.onSurfaceVariantSummary(),
                        modifier = Modifier.weight(0.68f),
                    )
                }
            }
        }
    }
}
