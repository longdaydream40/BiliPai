package com.android.purebilibili.core.input.gamepad

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

@Serializable
enum class GamepadControlMode {
    CURSOR,
    FOCUS,
}

enum class GamepadScene {
    BROWSING,
    VIDEO_DETAIL,
    VIDEO_FULLSCREEN,
}

enum class GamepadDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT,
}

@Serializable
data class GamepadConfig(
    val defaultMode: GamepadControlMode = GamepadControlMode.CURSOR,
    /** 光标基准移速倍率（0.5f..2.5f） */
    val cursorSensitivity: Float = 1.0f,
    /** 右摇杆滚动速度倍率（0.5f..2.5f） */
    val scrollSensitivity: Float = 1.0f,
    /** 摇杆径向死区（0.08f..0.35f） */
    val stickDeadzone: Float = 0.15f,
    /** 播放页按住 RT 时的倍速倍率 */
    val rtBoostSpeed: Float = 2.0f,
    /** 是否显示底部手柄键位提示条 */
    val showLegendHud: Boolean = true,
    /** 光标移动时是否向底层发送 Hover 事件以触发卡片悬浮反馈 */
    val hoverHighlightEnabled: Boolean = true,
    /** 静止一段时间后自动淡出光标 */
    val autoHideCursor: Boolean = true,
    /** 检测到真实触屏输入时自动隐藏光标 */
    val hideCursorOnTouch: Boolean = true,
    /** 视频页与全屏页启用手柄专属快捷操控 */
    val playerShortcutsEnabled: Boolean = true,
    /** 左/右摇杆反转垂直滚动方向 */
    val invertRightStickScroll: Boolean = false,
)

data class NormalizedStickVector(
    val x: Float = 0f,
    val y: Float = 0f,
) {
    val isActive: Boolean get() = x != 0f || y != 0f
}

/**
 * 对双轴摇杆应用径向死区与非线性响应曲线：
 * - 死区内严格归零，防止手柄漂移；
 * - 越过死区后从 0 平滑起跳，消除阶跃感；
 * - 幂曲线兼顾小幅微调精度与大屏快速跨屏。
 */
internal fun normalizeRadialStick(
    rawX: Float,
    rawY: Float,
    deadzone: Float,
    curveExponent: Float = 1.8f,
): NormalizedStickVector {
    val safeDeadzone = deadzone.coerceIn(0.05f, 0.45f)
    val clampedX = rawX.coerceIn(-1f, 1f)
    val clampedY = rawY.coerceIn(-1f, 1f)
    val magnitude = hypot(clampedX.toDouble(), clampedY.toDouble()).toFloat()
    if (magnitude <= safeDeadzone || magnitude <= 1e-5f) {
        return NormalizedStickVector()
    }
    val rescaled = ((magnitude - safeDeadzone) / (1f - safeDeadzone)).coerceIn(0f, 1f)
    val curved = rescaled.pow(curveExponent)
    val scale = curved / magnitude
    return NormalizedStickVector(
        x = (clampedX * scale).coerceIn(-1f, 1f),
        y = (clampedY * scale).coerceIn(-1f, 1f),
    )
}

/** 计算单帧光标位移（像素），按住 LT 时自动切入慢速精准瞄准。 */
internal fun computeCursorStepPx(
    vector: NormalizedStickVector,
    deltaSeconds: Float,
    density: Float,
    sensitivity: Float,
    precisionAimHeld: Boolean,
): Pair<Float, Float> {
    if (!vector.isActive || deltaSeconds <= 0f) return 0f to 0f
    val clampedDt = deltaSeconds.coerceAtMost(0.05f)
    val baseDpPerSec = 680f * sensitivity.coerceIn(0.4f, 3.0f)
    val precisionFactor = if (precisionAimHeld) 0.35f else 1.0f
    val pxPerSec = baseDpPerSec * density.coerceAtLeast(1f) * precisionFactor
    return (vector.x * pxPerSec * clampedDt) to (vector.y * pxPerSec * clampedDt)
}

/** 兼容不同 Xbox 蓝牙/USB 驱动上报的扳机轴。 */
internal fun resolveTriggerValue(primaryAxis: Float, fallbackAxis: Float): Float {
    return max(primaryAxis.coerceIn(0f, 1f), fallbackAxis.coerceIn(0f, 1f))
}

/** 带滞回的扳机数字开关判定，避免临界抖动反复触发。 */
internal fun resolveTriggerPressed(
    triggerValue: Float,
    currentlyPressed: Boolean,
    pressThreshold: Float = 0.55f,
    releaseThreshold: Float = 0.30f,
): Boolean {
    return if (currentlyPressed) {
        triggerValue >= releaseThreshold
    } else {
        triggerValue >= pressThreshold
    }
}

/**
 * 将连续的 HAT 轴或摇杆方向转换为带长按连发的离散方向脉冲。
 * 供蓝牙 Xbox 十字键（AXIS_HAT_X / AXIS_HAT_Y）和焦点模式左摇杆共用。
 */
internal class DirectionalRepeatTracker(
    private val initialDelayMs: Long = 320L,
    private val repeatIntervalMs: Long = 95L,
) {
    private var activeDirection: GamepadDirection? = null
    private var nextEmitAtMs: Long = 0L

    fun update(direction: GamepadDirection?, nowMs: Long): GamepadDirection? {
        if (direction == null) {
            activeDirection = null
            nextEmitAtMs = 0L
            return null
        }
        if (direction != activeDirection) {
            activeDirection = direction
            nextEmitAtMs = nowMs + initialDelayMs
            return direction
        }
        if (nowMs >= nextEmitAtMs) {
            nextEmitAtMs = nowMs + repeatIntervalMs
            return direction
        }
        return null
    }

    fun reset() {
        activeDirection = null
        nextEmitAtMs = 0L
    }
}

/** 将十字键 HAT 轴或摇杆二维值提取为主方向。 */
internal fun resolveDominantDirection(
    x: Float,
    y: Float,
    threshold: Float = 0.5f,
): GamepadDirection? {
    val absX = abs(x)
    val absY = abs(y)
    if (absX < threshold && absY < threshold) return null
    return if (absX >= absY) {
        if (x < 0f) GamepadDirection.LEFT else GamepadDirection.RIGHT
    } else {
        if (y < 0f) GamepadDirection.UP else GamepadDirection.DOWN
    }
}

sealed interface GamepadNavigationAction {
    data class SwitchBottomTabDelta(val delta: Int) : GamepadNavigationAction
    data object ReselectCurrentTab : GamepadNavigationAction
    data object OpenSearch : GamepadNavigationAction
    data object OpenSettings : GamepadNavigationAction
}

sealed interface GamepadPlayerAction {
    data object PlayPause : GamepadPlayerAction
    data class SeekRelative(val deltaMs: Long) : GamepadPlayerAction
    data class AdjustVolume(val direction: Int) : GamepadPlayerAction
    data class AdjustBrightness(val deltaFraction: Float) : GamepadPlayerAction
    data object ToggleMute : GamepadPlayerAction
    data object ToggleDanmaku : GamepadPlayerAction
    data object ToggleFullscreen : GamepadPlayerAction
    data object ToggleLike : GamepadPlayerAction
    data object TripleAction : GamepadPlayerAction
    data object ToggleScreenLock : GamepadPlayerAction
    data object PreviousPart : GamepadPlayerAction
    data object NextPart : GamepadPlayerAction
    data object ToggleControls : GamepadPlayerAction
    data class SpeedBoostChanged(val active: Boolean, val speed: Float) : GamepadPlayerAction
}
