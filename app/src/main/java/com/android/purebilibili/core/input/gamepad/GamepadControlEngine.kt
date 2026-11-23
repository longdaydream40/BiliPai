package com.android.purebilibili.core.input.gamepad

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import com.android.purebilibili.core.util.Logger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

private const val TAG = "GamepadControlEngine"
private const val CURSOR_AUTO_HIDE_MS = 4_500L
private const val HOVER_DISPATCH_INTERVAL_MS = 32L
private const val LONG_PRESS_Y_MS = 520L
private const val FULLSCREEN_STICK_SEEK_INTERVAL_MS = 320L

data class GamepadUiState(
    val pluginEnabled: Boolean = false,
    val connected: Boolean = false,
    val controllerName: String? = null,
    val mode: GamepadControlMode = GamepadControlMode.CURSOR,
    val scene: GamepadScene = GamepadScene.BROWSING,
    val cursorVisible: Boolean = false,
    val cursorX: Float = 0f,
    val cursorY: Float = 0f,
    val pointerPressed: Boolean = false,
    val precisionAimHeld: Boolean = false,
    val hudVisible: Boolean = true,
    val rtBoostActive: Boolean = false,
    val statusBanner: String? = null,
    val statusBannerId: Long = 0L,
)

data class GamepadLiveTelemetry(
    val leftX: Float = 0f,
    val leftY: Float = 0f,
    val rightX: Float = 0f,
    val rightY: Float = 0f,
    val leftTrigger: Float = 0f,
    val rightTrigger: Float = 0f,
    val lastButtonLabel: String = "等待输入",
)

private data class SceneRegistration(
    val token: Any,
    val scene: GamepadScene,
)

/**
 * Xbox / 通用手柄全局操控引擎（单例）。
 *
 * 特性：
 * - 双模式：默认「智能光标模式」（左摇杆移光标 + 右摇杆全局滚轮/滑动 + A 点击与拖拽 + B 回退），
 *   按 View 键可随时切换「焦点导航模式」；
 * - 场景感知：进入视频详情页或全屏播放器后自动解锁播放快捷键与按住 RT 倍速；
 * - 多窗口穿透：自动挂接 Compose Dialog / ModalBottomSheet 窗口并同步光标；
 * - 零空闲开销：仅在摇杆偏离死区或长按连发时运行 Choreographer 帧循环。
 */
object GamepadControlEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var inputManager: InputManager? = null
    private var deviceListenerRegistered = false

    private var config: GamepadConfig = GamepadConfig()
    private val _configFlow = MutableStateFlow(config)
    val configFlow: StateFlow<GamepadConfig> = _configFlow.asStateFlow()

    private val _uiState = MutableStateFlow(GamepadUiState())
    val uiState: StateFlow<GamepadUiState> = _uiState.asStateFlow()

    private val _telemetry = MutableStateFlow(GamepadLiveTelemetry())
    val telemetry: StateFlow<GamepadLiveTelemetry> = _telemetry.asStateFlow()

    private val _navigationActions = MutableSharedFlow<GamepadNavigationAction>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val navigationActions: SharedFlow<GamepadNavigationAction> = _navigationActions.asSharedFlow()

    private val _playerActions = MutableSharedFlow<GamepadPlayerAction>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val playerActions: SharedFlow<GamepadPlayerAction> = _playerActions.asSharedFlow()

    private val sceneStack = CopyOnWriteArrayList<SceneRegistration>()

    private val windowBridge = GamepadWindowBridge(
        onKeyEvent = ::handleKeyEvent,
        onGenericMotionEvent = ::handleGenericMotionEvent,
        onRealTouch = ::onRealTouchDetected,
    )

    private var cursorInitialized = false
    private var cursorX = 0f
    private var cursorY = 0f
    private var leftStick = NormalizedStickVector()
    private var rightStick = NormalizedStickVector()
    private var rawHatX = 0f
    private var rawHatY = 0f
    private var leftTriggerValue = 0f
    private var rightTriggerValue = 0f
    private var ltPressed = false
    private var rtPressed = false
    private var rtSpeedBoostActive = false

    private val hatRepeatTracker = DirectionalRepeatTracker()
    private val leftStickFocusRepeatTracker = DirectionalRepeatTracker(
        initialDelayMs = 280L,
        repeatIntervalMs = 115L,
    )

    private var frameLoopRunning = false
    private var lastFrameNanos = 0L
    private var lastHoverDispatchMs = 0L
    private var lastFullscreenSeekMs = 0L
    private var statusBannerSeq = 0L

    private var yLongPressFired = false
    private val yLongPressRunnable = Runnable {
        yLongPressFired = true
        val state = _uiState.value
        if (state.pluginEnabled && config.playerShortcutsEnabled && isVideoScene(state.scene)) {
            emitPlayerAction(GamepadPlayerAction.TripleAction)
            showBanner("一键三连")
        }
    }

    private val autoHideCursorRunnable = Runnable {
        if (!config.autoHideCursor) return@Runnable
        if (_uiState.value.pointerPressed) return@Runnable
        hideCursorInternal()
    }

    private val clearBannerRunnable = Runnable {
        _uiState.update { it.copy(statusBanner = null) }
    }

    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshConnectedControllers(notifyChange = true)
        override fun onInputDeviceRemoved(deviceId: Int) = refreshConnectedControllers(notifyChange = true)
        override fun onInputDeviceChanged(deviceId: Int) = refreshConnectedControllers(notifyChange = false)
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!frameLoopRunning) return
            val dtSeconds = if (lastFrameNanos == 0L) {
                1f / 60f
            } else {
                ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            }
            lastFrameNanos = frameTimeNanos
            stepFrame(dtSeconds)
            if (shouldKeepFrameLoopRunning()) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                stopFrameLoop()
            }
        }
    }

    fun initialize(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        inputManager = app.getSystemService(InputManager::class.java)
        if (!deviceListenerRegistered) {
            inputManager?.registerInputDeviceListener(inputDeviceListener, mainHandler)
            deviceListenerRegistered = true
        }
        refreshConnectedControllers(notifyChange = false)
    }

    fun setPluginEnabled(enabled: Boolean) {
        _uiState.update { current ->
            current.copy(
                pluginEnabled = enabled,
                cursorVisible = if (enabled) current.cursorVisible else false,
                pointerPressed = false,
                precisionAimHeld = false,
                rtBoostActive = false,
            )
        }
        if (!enabled) {
            resetLiveInputs()
            windowBridge.cancelActiveGestures()
            windowBridge.clearDialogCursorOverlay()
        } else {
            refreshConnectedControllers(notifyChange = false)
            windowBridge.syncSubWindowCallbacks()
        }
    }

    fun updateConfig(newConfig: GamepadConfig) {
        val previousMode = config.defaultMode
        config = newConfig
        _configFlow.value = newConfig
        _uiState.update { state ->
            state.copy(
                mode = if (previousMode != newConfig.defaultMode) newConfig.defaultMode else state.mode,
                hudVisible = newConfig.showLegendHud,
            )
        }
        if (!newConfig.autoHideCursor) {
            mainHandler.removeCallbacks(autoHideCursorRunnable)
        }
    }

    fun attachActivity(activity: ComponentActivity) {
        initialize(activity)
        windowBridge.attachActivity(activity)
        ensureCursorBoundsInitialized()
        refreshConnectedControllers(notifyChange = false)
    }

    fun detachActivity(activity: ComponentActivity) {
        if (windowBridge.currentActivity() === activity) {
            resetLiveInputs()
        }
        windowBridge.detachActivity(activity)
    }

    fun onActivityWindowFocusChanged(activity: ComponentActivity, hasFocus: Boolean) {
        if (windowBridge.currentActivity() !== activity) return
        // 当 Compose Dialog / ModalBottomSheet 弹出时，Activity 会立即失去窗口焦点，此时挂接弹窗回调。
        if (!hasFocus) {
            mainHandler.post { windowBridge.syncSubWindowCallbacks() }
        } else {
            windowBridge.clearDialogCursorOverlay()
        }
    }

    fun registerScene(token: Any, scene: GamepadScene) {
        sceneStack.removeAll { it.token === token }
        sceneStack.add(SceneRegistration(token, scene))
        publishEffectiveScene()
    }

    fun unregisterScene(token: Any) {
        sceneStack.removeAll { it.token === token }
        publishEffectiveScene()
    }

    /** Activity.dispatchTouchEvent 通知真实手指触屏，避免与合成点击混淆。 */
    fun observeActivityTouchEvent(event: MotionEvent) {
        if (windowBridge.isDispatchingSynthetic) return
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
        ) {
            onRealTouchDetected()
        }
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (windowBridge.isDispatchingSynthetic) return false
        if (!_uiState.value.pluginEnabled) return false
        if (!isGamepadKeyEvent(event)) return false

        val device = event.device
        if (device != null && isHardwareGamepadDevice(device) && !_uiState.value.connected) {
            refreshConnectedControllers(notifyChange = false)
        }

        // 输入法聚焦编辑框时仅拦截 B 键回退和摇杆按键，其余放行不干扰输入法选字。
        val keyCode = event.keyCode
        val isDown = event.action == KeyEvent.ACTION_DOWN
        val isUp = event.action == KeyEvent.ACTION_UP
        val isFirstDown = isDown && event.repeatCount == 0

        if (isFirstDown) {
            recordLastButton(keyCode)
        }

        val state = _uiState.value
        val inVideo = isVideoScene(state.scene) && config.playerShortcutsEnabled
        val inFullscreen = state.scene == GamepadScene.VIDEO_FULLSCREEN && config.playerShortcutsEnabled

        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (inFullscreen && !state.cursorVisible && state.mode == GamepadControlMode.CURSOR) {
                    if (isFirstDown) {
                        emitPlayerAction(GamepadPlayerAction.PlayPause)
                    }
                    return true
                }
                when (state.mode) {
                    GamepadControlMode.CURSOR -> {
                        if (isFirstDown) {
                            wakeCursor()
                            _uiState.update { it.copy(pointerPressed = true) }
                            syncDialogOverlayIfNeeded()
                            windowBridge.injectPointerDown(cursorX, cursorY)
                        } else if (isUp) {
                            _uiState.update { it.copy(pointerPressed = false) }
                            syncDialogOverlayIfNeeded()
                            windowBridge.injectPointerUp(cursorX, cursorY)
                            mainHandler.postDelayed({ windowBridge.syncSubWindowCallbacks() }, 120L)
                            scheduleCursorAutoHide()
                        }
                        true
                    }
                    GamepadControlMode.FOCUS -> {
                        if (isFirstDown) {
                            windowBridge.dispatchSyntheticKeyPress(KeyEvent.KEYCODE_DPAD_CENTER)
                        }
                        true
                    }
                }
            }

            KeyEvent.KEYCODE_BUTTON_B -> {
                if (isFirstDown) {
                    releasePointerIfPressed()
                    windowBridge.dispatchBack()
                }
                // 成对消费 DOWN 与 UP，避免 Android 将未消费的 BUTTON_B 再次回退成 KEYCODE_BACK。
                true
            }

            KeyEvent.KEYCODE_BUTTON_X -> {
                if (isFirstDown) {
                    if (inVideo) {
                        emitPlayerAction(GamepadPlayerAction.ToggleDanmaku)
                    } else {
                        _navigationActions.tryEmit(GamepadNavigationAction.OpenSearch)
                    }
                }
                true
            }

            KeyEvent.KEYCODE_BUTTON_Y -> {
                if (isFirstDown) {
                    yLongPressFired = false
                    if (inVideo) {
                        mainHandler.removeCallbacks(yLongPressRunnable)
                        mainHandler.postDelayed(yLongPressRunnable, LONG_PRESS_Y_MS)
                    } else {
                        _navigationActions.tryEmit(GamepadNavigationAction.ReselectCurrentTab)
                    }
                } else if (isUp) {
                    mainHandler.removeCallbacks(yLongPressRunnable)
                    if (inVideo && !yLongPressFired) {
                        emitPlayerAction(GamepadPlayerAction.ToggleFullscreen)
                    }
                    yLongPressFired = false
                }
                true
            }

            KeyEvent.KEYCODE_BUTTON_L1 -> {
                if (isFirstDown) {
                    if (inVideo) {
                        if (ltPressed) {
                            emitPlayerAction(GamepadPlayerAction.PreviousPart)
                        } else {
                            emitPlayerAction(GamepadPlayerAction.SeekRelative(-10_000L))
                        }
                    } else {
                        _navigationActions.tryEmit(GamepadNavigationAction.SwitchBottomTabDelta(-1))
                    }
                }
                true
            }

            KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (isFirstDown) {
                    if (inVideo) {
                        if (ltPressed) {
                            emitPlayerAction(GamepadPlayerAction.NextPart)
                        } else {
                            emitPlayerAction(GamepadPlayerAction.SeekRelative(10_000L))
                        }
                    } else {
                        _navigationActions.tryEmit(GamepadNavigationAction.SwitchBottomTabDelta(+1))
                    }
                }
                true
            }

            KeyEvent.KEYCODE_BUTTON_START -> {
                if (isFirstDown) {
                    if (inVideo) {
                        emitPlayerAction(GamepadPlayerAction.PlayPause)
                    } else {
                        _navigationActions.tryEmit(GamepadNavigationAction.OpenSettings)
                    }
                }
                true
            }

            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                if (isFirstDown) {
                    toggleControlMode()
                }
                true
            }

            KeyEvent.KEYCODE_BUTTON_THUMBL -> {
                if (isFirstDown) {
                    toggleCursorVisibility()
                }
                true
            }

            KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                if (isFirstDown) {
                    if (inFullscreen && ltPressed) {
                        emitPlayerAction(GamepadPlayerAction.ToggleScreenLock)
                    } else {
                        val nextHud = !_uiState.value.hudVisible
                        _uiState.update { it.copy(hudVisible = nextHud) }
                        showBanner(if (nextHud) "已显示手柄键位提示" else "已隐藏手柄键位提示")
                    }
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isDown) {
                    val direction = when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> GamepadDirection.UP
                        KeyEvent.KEYCODE_DPAD_DOWN -> GamepadDirection.DOWN
                        KeyEvent.KEYCODE_DPAD_LEFT -> GamepadDirection.LEFT
                        else -> GamepadDirection.RIGHT
                    }
                    dispatchDirectionAction(direction, fromDpad = true)
                }
                true
            }

            KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2 -> true

            else -> false
        }
    }

    fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (windowBridge.isDispatchingSynthetic) return false
        if (!_uiState.value.pluginEnabled) return false
        if (event.action != MotionEvent.ACTION_MOVE) return false
        val isJoystick = event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.isFromSource(InputDevice.SOURCE_GAMEPAD)
        if (!isJoystick) return false

        val device = event.device
        if (device != null && isHardwareGamepadDevice(device) && !_uiState.value.connected) {
            refreshConnectedControllers(notifyChange = false)
        }

        val rawLx = event.getAxisValue(MotionEvent.AXIS_X)
        val rawLy = event.getAxisValue(MotionEvent.AXIS_Y)
        val (rawRx, rawRy) = extractRightStickAxes(event, device)
        rawHatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        rawHatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        leftTriggerValue = resolveTriggerValue(
            primaryAxis = event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
            fallbackAxis = event.getAxisValue(MotionEvent.AXIS_BRAKE),
        )
        rightTriggerValue = resolveTriggerValue(
            primaryAxis = event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            fallbackAxis = event.getAxisValue(MotionEvent.AXIS_GAS),
        )

        leftStick = normalizeRadialStick(rawLx, rawLy, config.stickDeadzone)
        rightStick = normalizeRadialStick(rawRx, rawRy, config.stickDeadzone)

        _telemetry.update {
            it.copy(
                leftX = leftStick.x,
                leftY = leftStick.y,
                rightX = rightStick.x,
                rightY = rightStick.y,
                leftTrigger = leftTriggerValue,
                rightTrigger = rightTriggerValue,
            )
        }

        updateTriggerStates()

        val hatDirection = resolveDominantDirection(rawHatX, rawHatY)
        hatRepeatTracker.update(hatDirection, SystemClock.uptimeMillis())?.let { dir ->
            recordLastDirection("十字键", dir)
            dispatchDirectionAction(dir, fromDpad = true)
        }

        if (leftStick.isActive && _uiState.value.mode == GamepadControlMode.CURSOR) {
            wakeCursor()
        }

        if (!rightStick.isActive) {
            windowBridge.finishScrollDragIfActive()
        }

        if (shouldKeepFrameLoopRunning()) {
            startFrameLoop()
        }
        return true
    }

    private fun extractRightStickAxes(event: MotionEvent, device: InputDevice?): Pair<Float, Float> {
        val hasZ = device?.getMotionRange(MotionEvent.AXIS_Z, InputDevice.SOURCE_JOYSTICK) != null
        val hasRz = device?.getMotionRange(MotionEvent.AXIS_RZ, InputDevice.SOURCE_JOYSTICK) != null
        return if (hasZ || hasRz || device == null) {
            event.getAxisValue(MotionEvent.AXIS_Z) to event.getAxisValue(MotionEvent.AXIS_RZ)
        } else {
            event.getAxisValue(MotionEvent.AXIS_RX) to event.getAxisValue(MotionEvent.AXIS_RY)
        }
    }

    private fun updateTriggerStates() {
        val nextLt = resolveTriggerPressed(leftTriggerValue, ltPressed)
        if (nextLt != ltPressed) {
            ltPressed = nextLt
            _uiState.update { it.copy(precisionAimHeld = nextLt) }
            syncDialogOverlayIfNeeded()
        }

        val nextRt = resolveTriggerPressed(rightTriggerValue, rtPressed)
        if (nextRt != rtPressed) {
            rtPressed = nextRt
            val state = _uiState.value
            val canBoost = config.playerShortcutsEnabled && isVideoScene(state.scene)
            if (nextRt && canBoost) {
                rtSpeedBoostActive = true
                _uiState.update { it.copy(rtBoostActive = true) }
                emitPlayerAction(GamepadPlayerAction.SpeedBoostChanged(active = true, speed = config.rtBoostSpeed))
                showBanner("${config.rtBoostSpeed}x 倍速中（松开 RT 恢复）")
            } else if (!nextRt && rtSpeedBoostActive) {
                rtSpeedBoostActive = false
                _uiState.update { it.copy(rtBoostActive = false) }
                emitPlayerAction(GamepadPlayerAction.SpeedBoostChanged(active = false, speed = 1.0f))
            }
        }
    }

    private fun stepFrame(dtSeconds: Float) {
        val state = _uiState.value
        if (!state.pluginEnabled) return
        val nowMs = SystemClock.uptimeMillis()
        val density = windowBridge.currentActivity()?.resources?.displayMetrics?.density ?: 2f

        // 1. 蓝牙 Xbox 十字键（HAT 轴）长按连发
        val hatDirection = resolveDominantDirection(rawHatX, rawHatY)
        hatRepeatTracker.update(hatDirection, nowMs)?.let { dir ->
            dispatchDirectionAction(dir, fromDpad = true)
        }

        // 2. 左摇杆：光标模式移动光标，焦点模式驱动方向键步进
        if (leftStick.isActive) {
            when (state.mode) {
                GamepadControlMode.CURSOR -> {
                    ensureCursorBoundsInitialized()
                    val (viewportW, viewportH) = windowBridge.currentViewportSize()
                    val (dx, dy) = computeCursorStepPx(
                        vector = leftStick,
                        deltaSeconds = dtSeconds,
                        density = density,
                        sensitivity = config.cursorSensitivity,
                        precisionAimHeld = ltPressed,
                    )
                    val nextX = (cursorX + dx).coerceIn(0f, (viewportW - 1).coerceAtLeast(1).toFloat())
                    val nextY = (cursorY + dy).coerceIn(0f, (viewportH - 1).coerceAtLeast(1).toFloat())
                    if (nextX != cursorX || nextY != cursorY) {
                        cursorX = nextX
                        cursorY = nextY
                        _uiState.update {
                            it.copy(
                                cursorVisible = true,
                                cursorX = cursorX,
                                cursorY = cursorY,
                            )
                        }
                        syncDialogOverlayIfNeeded()
                        if (state.pointerPressed) {
                            windowBridge.injectPointerMove(cursorX, cursorY)
                        } else if (config.hoverHighlightEnabled && nowMs - lastHoverDispatchMs >= HOVER_DISPATCH_INTERVAL_MS) {
                            lastHoverDispatchMs = nowMs
                            windowBridge.injectHover(cursorX, cursorY)
                        }
                    }
                    scheduleCursorAutoHide()
                }
                GamepadControlMode.FOCUS -> {
                    val stickDir = resolveDominantDirection(leftStick.x, leftStick.y, threshold = 0.25f)
                    leftStickFocusRepeatTracker.update(stickDir, nowMs)?.let { dir ->
                        dispatchDirectionAction(dir, fromDpad = false)
                    }
                }
            }
        } else {
            leftStickFocusRepeatTracker.reset()
        }

        // 3. 右摇杆（及浏览模式下 RT 加速向下滚动）
        val inFullscreenNoCursor = state.scene == GamepadScene.VIDEO_FULLSCREEN &&
            config.playerShortcutsEnabled &&
            !state.cursorVisible
        if (inFullscreenNoCursor && rightStick.isActive) {
            if (abs(rightStick.y) >= abs(rightStick.x)) {
                val deltaBrightness = -rightStick.y * dtSeconds * 0.75f
                if (abs(deltaBrightness) > 0.004f) {
                    emitPlayerAction(GamepadPlayerAction.AdjustBrightness(deltaBrightness))
                }
            } else if (nowMs - lastFullscreenSeekMs >= FULLSCREEN_STICK_SEEK_INTERVAL_MS) {
                lastFullscreenSeekMs = nowMs
                val deltaMs = if (rightStick.x > 0f) 5_000L else -5_000L
                emitPlayerAction(GamepadPlayerAction.SeekRelative(deltaMs))
            }
        } else {
            val effectiveRy = when {
                rightStick.isActive -> rightStick.y
                rtPressed && !isVideoScene(state.scene) -> rightTriggerValue.coerceAtLeast(0.65f)
                else -> 0f
            }
            val effectiveRx = if (rightStick.isActive) rightStick.x else 0f
            if (effectiveRx != 0f || effectiveRy != 0f) {
                ensureCursorBoundsInitialized()
                val sign = if (config.invertRightStickScroll) -1f else 1f
                val scrollSpeed = config.scrollSensitivity.coerceIn(0.4f, 3.0f)
                // Android AXIS_VSCROLL 正值=向上滚，右摇杆向下推（effectiveRy > 0）应向下滚（vScroll < 0）。
                val vScroll = -effectiveRy * sign * scrollSpeed * 16f * dtSeconds
                val hScroll = effectiveRx * scrollSpeed * 12f * dtSeconds
                val fallbackDragDx = -effectiveRx * scrollSpeed * 620f * density * dtSeconds
                val fallbackDragDy = -effectiveRy * sign * scrollSpeed * 760f * density * dtSeconds
                val (viewportW, viewportH) = windowBridge.currentViewportSize()
                val targetX = if (state.cursorVisible) cursorX else viewportW * 0.5f
                val targetY = if (state.cursorVisible) cursorY else viewportH * 0.5f
                windowBridge.injectScroll(
                    screenX = targetX,
                    screenY = targetY,
                    hScrollUnits = hScroll,
                    vScrollUnits = vScroll,
                    fallbackDragDxPx = fallbackDragDx,
                    fallbackDragDyPx = fallbackDragDy,
                )
            }
        }
    }

    private fun dispatchDirectionAction(direction: GamepadDirection, fromDpad: Boolean) {
        val state = _uiState.value
        val inVideo = isVideoScene(state.scene) && config.playerShortcutsEnabled
        val useVideoShortcut = inVideo && fromDpad &&
            (state.scene == GamepadScene.VIDEO_FULLSCREEN || state.mode == GamepadControlMode.CURSOR) &&
            !windowBridge.isTextInputFocused()

        if (useVideoShortcut) {
            when (direction) {
                GamepadDirection.LEFT -> emitPlayerAction(GamepadPlayerAction.SeekRelative(-5_000L))
                GamepadDirection.RIGHT -> emitPlayerAction(GamepadPlayerAction.SeekRelative(5_000L))
                GamepadDirection.UP -> emitPlayerAction(GamepadPlayerAction.AdjustVolume(+1))
                GamepadDirection.DOWN -> emitPlayerAction(GamepadPlayerAction.AdjustVolume(-1))
            }
            return
        }

        val keyCode = when (direction) {
            GamepadDirection.UP -> KeyEvent.KEYCODE_DPAD_UP
            GamepadDirection.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            GamepadDirection.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            GamepadDirection.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        }
        windowBridge.dispatchSyntheticKeyPress(keyCode)
    }

    private fun toggleControlMode() {
        val current = _uiState.value.mode
        val next = if (current == GamepadControlMode.CURSOR) {
            GamepadControlMode.FOCUS
        } else {
            GamepadControlMode.CURSOR
        }
        releasePointerIfPressed()
        if (next == GamepadControlMode.FOCUS) {
            hideCursorInternal()
        } else {
            wakeCursor()
        }
        _uiState.update { it.copy(mode = next) }
        showBanner(if (next == GamepadControlMode.CURSOR) "智能光标模式" else "焦点导航模式")
    }

    private fun toggleCursorVisibility() {
        val state = _uiState.value
        if (state.mode == GamepadControlMode.FOCUS) {
            _uiState.update { it.copy(mode = GamepadControlMode.CURSOR) }
            wakeCursor()
            showBanner("已唤出虚拟光标")
            return
        }
        if (state.cursorVisible) {
            hideCursorInternal()
            showBanner("已隐藏虚拟光标")
        } else {
            wakeCursor()
            showBanner("已唤出虚拟光标")
        }
    }

    private fun wakeCursor() {
        ensureCursorBoundsInitialized()
        if (!_uiState.value.cursorVisible) {
            _uiState.update {
                it.copy(
                    cursorVisible = true,
                    cursorX = cursorX,
                    cursorY = cursorY,
                )
            }
        }
        syncDialogOverlayIfNeeded()
        scheduleCursorAutoHide()
    }

    private fun hideCursorInternal() {
        mainHandler.removeCallbacks(autoHideCursorRunnable)
        releasePointerIfPressed()
        _uiState.update { it.copy(cursorVisible = false, pointerPressed = false) }
        windowBridge.clearDialogCursorOverlay()
    }

    private fun scheduleCursorAutoHide() {
        mainHandler.removeCallbacks(autoHideCursorRunnable)
        if (config.autoHideCursor) {
            mainHandler.postDelayed(autoHideCursorRunnable, CURSOR_AUTO_HIDE_MS)
        }
    }

    private fun onRealTouchDetected() {
        if (!config.hideCursorOnTouch) return
        if (_uiState.value.cursorVisible) {
            hideCursorInternal()
        }
    }

    private fun releasePointerIfPressed() {
        if (_uiState.value.pointerPressed) {
            _uiState.update { it.copy(pointerPressed = false) }
            windowBridge.injectPointerUp(cursorX, cursorY)
        }
    }

    private fun ensureCursorBoundsInitialized() {
        val (w, h) = windowBridge.currentViewportSize()
        if (!cursorInitialized) {
            cursorX = w * 0.5f
            cursorY = h * 0.5f
            cursorInitialized = true
            _uiState.update { it.copy(cursorX = cursorX, cursorY = cursorY) }
        } else {
            cursorX = cursorX.coerceIn(0f, (w - 1).coerceAtLeast(1).toFloat())
            cursorY = cursorY.coerceIn(0f, (h - 1).coerceAtLeast(1).toFloat())
        }
    }

    private fun syncDialogOverlayIfNeeded() {
        val state = _uiState.value
        val density = windowBridge.currentActivity()?.resources?.displayMetrics?.density ?: 2f
        windowBridge.syncDialogCursorOverlay(
            visible = state.pluginEnabled && state.cursorVisible,
            screenX = cursorX,
            screenY = cursorY,
            pressed = state.pointerPressed,
            precisionAim = state.precisionAimHeld,
            density = density,
        )
    }

    private fun publishEffectiveScene() {
        val effective = when {
            sceneStack.any { it.scene == GamepadScene.VIDEO_FULLSCREEN } -> GamepadScene.VIDEO_FULLSCREEN
            sceneStack.any { it.scene == GamepadScene.VIDEO_DETAIL } -> GamepadScene.VIDEO_DETAIL
            else -> GamepadScene.BROWSING
        }
        val previous = _uiState.value.scene
        if (effective != previous) {
            if (!isVideoScene(effective) && rtSpeedBoostActive) {
                rtSpeedBoostActive = false
                _uiState.update { it.copy(rtBoostActive = false) }
                emitPlayerAction(GamepadPlayerAction.SpeedBoostChanged(active = false, speed = 1.0f))
            }
            _uiState.update { it.copy(scene = effective) }
        }
    }

    private fun refreshConnectedControllers(notifyChange: Boolean) {
        val manager = inputManager ?: return
        val gamepad = manager.inputDeviceIds
            .asSequence()
            .mapNotNull { manager.getInputDevice(it) }
            .firstOrNull { isHardwareGamepadDevice(it) }
        val wasConnected = _uiState.value.connected
        val isNowConnected = gamepad != null
        val name = gamepad?.name?.takeIf { it.isNotBlank() }
        _uiState.update {
            it.copy(
                connected = isNowConnected,
                controllerName = name,
            )
        }
        if (notifyChange && _uiState.value.pluginEnabled && wasConnected != isNowConnected) {
            if (isNowConnected) {
                val label = name ?: "Xbox 手柄"
                showBanner("已连接 $label")
                Logger.d(TAG, "Gamepad connected: $label")
            } else {
                resetLiveInputs()
                hideCursorInternal()
                showBanner("手柄已断开")
                Logger.d(TAG, "Gamepad disconnected")
            }
        }
    }

    private fun emitPlayerAction(action: GamepadPlayerAction) {
        _playerActions.tryEmit(action)
    }

    private fun showBanner(message: String) {
        statusBannerSeq += 1L
        _uiState.update {
            it.copy(
                statusBanner = message,
                statusBannerId = statusBannerSeq,
            )
        }
        mainHandler.removeCallbacks(clearBannerRunnable)
        mainHandler.postDelayed(clearBannerRunnable, 2_000L)
    }

    private fun shouldKeepFrameLoopRunning(): Boolean {
        if (!_uiState.value.pluginEnabled) return false
        return leftStick.isActive ||
            rightStick.isActive ||
            resolveDominantDirection(rawHatX, rawHatY) != null ||
            (rtPressed && !isVideoScene(_uiState.value.scene))
    }

    private fun startFrameLoop() {
        if (frameLoopRunning) return
        frameLoopRunning = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopFrameLoop() {
        if (!frameLoopRunning) return
        frameLoopRunning = false
        lastFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun resetLiveInputs() {
        stopFrameLoop()
        mainHandler.removeCallbacks(yLongPressRunnable)
        mainHandler.removeCallbacks(autoHideCursorRunnable)
        yLongPressFired = false
        leftStick = NormalizedStickVector()
        rightStick = NormalizedStickVector()
        rawHatX = 0f
        rawHatY = 0f
        leftTriggerValue = 0f
        rightTriggerValue = 0f
        ltPressed = false
        rtPressed = false
        if (rtSpeedBoostActive) {
            rtSpeedBoostActive = false
            emitPlayerAction(GamepadPlayerAction.SpeedBoostChanged(active = false, speed = 1.0f))
        }
        hatRepeatTracker.reset()
        leftStickFocusRepeatTracker.reset()
        windowBridge.finishScrollDragIfActive()
        _telemetry.value = GamepadLiveTelemetry()
        _uiState.update { it.copy(precisionAimHeld = false, rtBoostActive = false) }
    }

    private fun isVideoScene(scene: GamepadScene): Boolean {
        return scene == GamepadScene.VIDEO_DETAIL || scene == GamepadScene.VIDEO_FULLSCREEN
    }

    private fun isGamepadKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code in GAMEPAD_ONLY_KEYCODES) return true
        if (code in DPAD_KEYCODES) {
            val fromGamepad = event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
                event.isFromSource(InputDevice.SOURCE_JOYSTICK)
            return fromGamepad
        }
        return false
    }

    private fun recordLastButton(keyCode: Int) {
        val label = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> "A 键"
            KeyEvent.KEYCODE_BUTTON_B -> "B 键"
            KeyEvent.KEYCODE_BUTTON_X -> "X 键"
            KeyEvent.KEYCODE_BUTTON_Y -> "Y 键"
            KeyEvent.KEYCODE_BUTTON_L1 -> "LB 键"
            KeyEvent.KEYCODE_BUTTON_R1 -> "RB 键"
            KeyEvent.KEYCODE_BUTTON_L2 -> "LT 键"
            KeyEvent.KEYCODE_BUTTON_R2 -> "RT 键"
            KeyEvent.KEYCODE_BUTTON_START -> "Menu / Start"
            KeyEvent.KEYCODE_BUTTON_SELECT -> "View / Back"
            KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3（左摇杆按压）"
            KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3（右摇杆按压）"
            KeyEvent.KEYCODE_DPAD_UP -> "十字键 上"
            KeyEvent.KEYCODE_DPAD_DOWN -> "十字键 下"
            KeyEvent.KEYCODE_DPAD_LEFT -> "十字键 左"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "十字键 右"
            else -> KeyEvent.keyCodeToString(keyCode)
        }
        _telemetry.update { it.copy(lastButtonLabel = label) }
    }

    private fun recordLastDirection(prefix: String, direction: GamepadDirection) {
        val suffix = when (direction) {
            GamepadDirection.UP -> "上"
            GamepadDirection.DOWN -> "下"
            GamepadDirection.LEFT -> "左"
            GamepadDirection.RIGHT -> "右"
        }
        _telemetry.update { it.copy(lastButtonLabel = "$prefix $suffix") }
    }

    fun hasConnectedGamepad(context: Context): Boolean {
        val manager = context.getSystemService(InputManager::class.java) ?: return false
        return manager.inputDeviceIds.any { id ->
            isHardwareGamepadDevice(manager.getInputDevice(id))
        }
    }

    fun isHardwareGamepadDevice(device: InputDevice?): Boolean {
        if (device == null || device.isVirtual) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && !device.isEnabled) return false
        val sources = device.sources
        if ((sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
            return true
        }
        if ((sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            val hasStickAxis = device.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_JOYSTICK) != null &&
                device.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_JOYSTICK) != null
            val hasFaceButtons = device.hasKeys(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B).any { it }
            return hasStickAxis && hasFaceButtons
        }
        return false
    }

    private val GAMEPAD_ONLY_KEYCODES = setOf(
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_L2,
        KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_BUTTON_THUMBL,
        KeyEvent.KEYCODE_BUTTON_THUMBR,
    )

    private val DPAD_KEYCODES = setOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
    )
}
