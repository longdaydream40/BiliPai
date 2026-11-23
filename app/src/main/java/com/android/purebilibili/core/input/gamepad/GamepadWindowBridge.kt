package com.android.purebilibili.core.input.gamepad

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.ui.window.DialogWindowProvider
import com.android.purebilibili.core.util.Logger
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

private const val TAG = "GamepadWindowBridge"

/**
 * 进程内多窗口桥接器：
 * 1. 自动识别 Compose Dialog / ModalBottomSheet 创建的独立 Window，包装其 Window.Callback
 *    以持续截获 Xbox 手柄按键与摇杆事件；
 * 2. 注入合成点击、拖拽、悬停和滚轮滚动事件时，自动定位最顶层可见可交互窗口并完成屏幕坐标换算；
 * 3. 在弹窗窗口覆盖 Activity 时，通过 DecorView.overlay 绘制同步光标，无需悬浮窗权限且不被弹窗遮挡。
 */
internal class GamepadWindowBridge(
    private val onKeyEvent: (KeyEvent) -> Boolean,
    private val onGenericMotionEvent: (MotionEvent) -> Boolean,
    private val onRealTouch: () -> Unit,
) {
    private var activeActivityRef: WeakReference<ComponentActivity>? = null
    private val hookedWindows = WeakHashMap<Window, Boolean>()

    @Volatile
    var isDispatchingSynthetic: Boolean = false
        private set

    private var activePointerDownTimeMs: Long = 0L
    private var activePointerTargetRef: WeakReference<View>? = null
    private var activeScrollDragDownTimeMs: Long = 0L
    private var activeScrollDragStartX: Float = 0f
    private var activeScrollDragStartY: Float = 0f
    private var activeScrollDragCurrentX: Float = 0f
    private var activeScrollDragCurrentY: Float = 0f
    private var activeScrollDragTargetRef: WeakReference<View>? = null

    private var dialogOverlayRootRef: WeakReference<View>? = null
    private val dialogCursorDrawable = DialogCursorOverlayDrawable()

    fun attachActivity(activity: ComponentActivity) {
        activeActivityRef = WeakReference(activity)
        syncSubWindowCallbacks()
    }

    fun detachActivity(activity: ComponentActivity) {
        if (activeActivityRef?.get() === activity) {
            cancelActiveGestures()
            clearDialogCursorOverlay()
            activeActivityRef = null
        }
    }

    fun currentActivity(): ComponentActivity? {
        return activeActivityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
    }

    /** 检查当前屏幕尺寸（px）。 */
    fun currentViewportSize(): Pair<Int, Int> {
        val decor = currentActivity()?.window?.decorView
        if (decor != null && decor.width > 0 && decor.height > 0) {
            return decor.width to decor.height
        }
        val metrics = currentActivity()?.resources?.displayMetrics
        return if (metrics != null) {
            metrics.widthPixels to metrics.heightPixels
        } else {
            1080 to 1920
        }
    }

    /** 检查当前是否处于系统软键盘展开或文本输入框获焦状态。 */
    fun isTextInputFocused(): Boolean {
        val topRoot = resolveTopmostRootView() ?: return false
        val focused = topRoot.findFocus() ?: return false
        return focused.onCheckIsTextEditor()
    }

    /** 发现并包装所有 Compose Dialog / ModalBottomSheet 窗口的 Window.Callback。 */
    fun syncSubWindowCallbacks() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val activityWindow = currentActivity()?.window
        val roots = runCatching { WindowInspector.getGlobalWindowViews() }.getOrNull().orEmpty()
        for (root in roots) {
            if (!root.isAttachedToWindow || root.visibility != View.VISIBLE) continue
            val dialogWindow = findDialogWindow(root) ?: continue
            if (dialogWindow === activityWindow) continue
            if (hookedWindows.containsKey(dialogWindow)) continue
            val currentCallback = dialogWindow.callback ?: continue
            if (currentCallback is GamepadWindowCallbackProxy) {
                hookedWindows[dialogWindow] = true
                continue
            }
            dialogWindow.callback = GamepadWindowCallbackProxy(
                original = currentCallback,
                onKey = onKeyEvent,
                onGenericMotion = onGenericMotionEvent,
                onTouch = { ev ->
                    if (!isDispatchingSynthetic &&
                        ev.actionMasked == MotionEvent.ACTION_DOWN &&
                        ev.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                    ) {
                        onRealTouch()
                    }
                },
            )
            hookedWindows[dialogWindow] = true
            Logger.d(TAG, "Hooked Dialog Window.Callback for gamepad routing")
        }
    }

    /** 当顶层是独立 Dialog 窗口时，同步在 Dialog DecorView.overlay 上绘制光标，避免被弹窗遮挡。 */
    fun syncDialogCursorOverlay(
        visible: Boolean,
        screenX: Float,
        screenY: Float,
        pressed: Boolean,
        precisionAim: Boolean,
        density: Float,
    ) {
        val activityDecor = currentActivity()?.window?.decorView
        val topRoot = resolveTopmostRootView()
        val isDialogOnTop = topRoot != null && topRoot !== activityDecor
        if (!visible || !isDialogOnTop || topRoot == null) {
            clearDialogCursorOverlay()
            return
        }
        val previousRoot = dialogOverlayRootRef?.get()
        if (previousRoot !== topRoot) {
            previousRoot?.overlay?.remove(dialogCursorDrawable)
            dialogOverlayRootRef = WeakReference(topRoot)
            topRoot.overlay.add(dialogCursorDrawable)
        }
        val loc = IntArray(2)
        topRoot.getLocationOnScreen(loc)
        dialogCursorDrawable.setBounds(0, 0, topRoot.width, topRoot.height)
        dialogCursorDrawable.update(
            localX = screenX - loc[0],
            localY = screenY - loc[1],
            pressed = pressed,
            precisionAim = precisionAim,
            density = density,
        )
        topRoot.postInvalidateOnAnimation()
    }

    fun clearDialogCursorOverlay() {
        dialogOverlayRootRef?.get()?.overlay?.remove(dialogCursorDrawable)
        dialogOverlayRootRef = null
    }

    fun injectHover(screenX: Float, screenY: Float) {
        val target = resolveTargetRootForPoint(screenX, screenY) ?: return
        val (localX, localY) = toLocalCoordinates(target, screenX, screenY)
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = localX
                y = localY
                pressure = 0f
                size = 1f
            }
        )
        val event = MotionEvent.obtain(
            now,
            now,
            MotionEvent.ACTION_HOVER_MOVE,
            1,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_MOUSE,
            0,
        )
        dispatchSyntheticGenericMotion(target, event)
    }

    fun injectPointerDown(screenX: Float, screenY: Float) {
        finishScrollDragIfActive()
        val target = resolveTargetRootForPoint(screenX, screenY) ?: return
        val (localX, localY) = toLocalCoordinates(target, screenX, screenY)
        val now = SystemClock.uptimeMillis()
        activePointerDownTimeMs = now
        activePointerTargetRef = WeakReference(target)
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, localX, localY, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        dispatchSyntheticTouch(target, event)
    }

    fun injectPointerMove(screenX: Float, screenY: Float) {
        val target = activePointerTargetRef?.get()?.takeIf { it.isAttachedToWindow } ?: return
        val (localX, localY) = toLocalCoordinates(target, screenX, screenY)
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            activePointerDownTimeMs,
            now,
            MotionEvent.ACTION_MOVE,
            localX,
            localY,
            0,
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        dispatchSyntheticTouch(target, event)
    }

    fun injectPointerUp(screenX: Float, screenY: Float) {
        val target = activePointerTargetRef?.get()?.takeIf { it.isAttachedToWindow }
        activePointerTargetRef = null
        if (target == null) return
        val (localX, localY) = toLocalCoordinates(target, screenX, screenY)
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            activePointerDownTimeMs,
            now,
            MotionEvent.ACTION_UP,
            localX,
            localY,
            0,
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        dispatchSyntheticTouch(target, event)
    }

    fun cancelActiveGestures() {
        activePointerTargetRef?.get()?.takeIf { it.isAttachedToWindow }?.let { target ->
            val now = SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(
                activePointerDownTimeMs,
                now,
                MotionEvent.ACTION_CANCEL,
                0f,
                0f,
                0,
            ).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            dispatchSyntheticTouch(target, cancel)
        }
        activePointerTargetRef = null
        finishScrollDragIfActive()
    }

    /**
     * 优先以鼠标滚轮（ACTION_SCROLL）滚动光标所在窗口，Compose LazyColumn/LazyGrid/WebView 均原生支持；
     * 若顶层容器未消费滚轮事件，则自动回退为连续合成触摸滑动。
     */
    fun injectScroll(
        screenX: Float,
        screenY: Float,
        hScrollUnits: Float,
        vScrollUnits: Float,
        fallbackDragDxPx: Float,
        fallbackDragDyPx: Float,
    ) {
        if (activePointerTargetRef?.get() != null) return
        val target = resolveTargetRootForPoint(screenX, screenY) ?: return
        val (localX, localY) = toLocalCoordinates(target, screenX, screenY)
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = localX
                y = localY
                setAxisValue(MotionEvent.AXIS_HSCROLL, hScrollUnits)
                setAxisValue(MotionEvent.AXIS_VSCROLL, vScrollUnits)
            }
        )
        val scrollEvent = MotionEvent.obtain(
            now,
            now,
            MotionEvent.ACTION_SCROLL,
            1,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_MOUSE,
            0,
        )
        val consumed = dispatchSyntheticGenericMotion(target, scrollEvent)
        if (consumed) {
            finishScrollDragIfActive()
        } else if (abs(fallbackDragDxPx) > 0.5f || abs(fallbackDragDyPx) > 0.5f) {
            advanceFallbackScrollDrag(target, localX, localY, fallbackDragDxPx, fallbackDragDyPx)
        }
    }

    fun finishScrollDragIfActive() {
        val target = activeScrollDragTargetRef?.get()?.takeIf { it.isAttachedToWindow }
        activeScrollDragTargetRef = null
        if (target == null) return
        val now = SystemClock.uptimeMillis()
        val up = MotionEvent.obtain(
            activeScrollDragDownTimeMs,
            now,
            MotionEvent.ACTION_UP,
            activeScrollDragCurrentX,
            activeScrollDragCurrentY,
            0,
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        dispatchSyntheticTouch(target, up)
    }

    fun dispatchSyntheticKeyPress(keyCode: Int) {
        val target = resolveTopmostRootView() ?: return
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_DPAD)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_DPAD)
        isDispatchingSynthetic = true
        try {
            target.dispatchKeyEvent(down)
            target.dispatchKeyEvent(up)
        } finally {
            isDispatchingSynthetic = false
        }
    }

    /** 触发回退：若有独立弹窗在顶层，先向弹窗发送 BACK；否则调用 Activity onBackPressedDispatcher。 */
    fun dispatchBack() {
        val activity = currentActivity() ?: return
        val activityDecor = activity.window.decorView
        val topRoot = resolveTopmostRootView()
        if (topRoot != null && topRoot !== activityDecor) {
            dispatchSyntheticKeyPress(KeyEvent.KEYCODE_BACK)
            return
        }
        isDispatchingSynthetic = true
        try {
            activity.onBackPressedDispatcher.onBackPressed()
        } finally {
            isDispatchingSynthetic = false
        }
    }

    private fun advanceFallbackScrollDrag(
        target: View,
        anchorX: Float,
        anchorY: Float,
        dxPx: Float,
        dyPx: Float,
    ) {
        val now = SystemClock.uptimeMillis()
        val currentTarget = activeScrollDragTargetRef?.get()
        val exceededSpan = abs(activeScrollDragCurrentX - activeScrollDragStartX) > target.width * 0.45f ||
            abs(activeScrollDragCurrentY - activeScrollDragStartY) > target.height * 0.45f
        if (currentTarget !== target || exceededSpan) {
            finishScrollDragIfActive()
            activeScrollDragDownTimeMs = now
            activeScrollDragStartX = anchorX.coerceIn(24f, (target.width - 24).coerceAtLeast(24).toFloat())
            activeScrollDragStartY = anchorY.coerceIn(24f, (target.height - 24).coerceAtLeast(24).toFloat())
            activeScrollDragCurrentX = activeScrollDragStartX
            activeScrollDragCurrentY = activeScrollDragStartY
            activeScrollDragTargetRef = WeakReference(target)
            val down = MotionEvent.obtain(
                now,
                now,
                MotionEvent.ACTION_DOWN,
                activeScrollDragStartX,
                activeScrollDragStartY,
                0,
            ).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            dispatchSyntheticTouch(target, down)
        }
        activeScrollDragCurrentX += dxPx
        activeScrollDragCurrentY += dyPx
        val move = MotionEvent.obtain(
            activeScrollDragDownTimeMs,
            now,
            MotionEvent.ACTION_MOVE,
            activeScrollDragCurrentX,
            activeScrollDragCurrentY,
            0,
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        dispatchSyntheticTouch(target, move)
    }

    private fun dispatchSyntheticTouch(target: View, event: MotionEvent): Boolean {
        isDispatchingSynthetic = true
        return try {
            target.dispatchTouchEvent(event)
        } finally {
            isDispatchingSynthetic = false
            event.recycle()
        }
    }

    private fun dispatchSyntheticGenericMotion(target: View, event: MotionEvent): Boolean {
        isDispatchingSynthetic = true
        return try {
            target.dispatchGenericMotionEvent(event)
        } finally {
            isDispatchingSynthetic = false
            event.recycle()
        }
    }

    private fun resolveTopmostRootView(): View? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roots = runCatching { WindowInspector.getGlobalWindowViews() }.getOrNull().orEmpty()
            for (i in roots.lastIndex downTo 0) {
                val root = roots[i]
                if (!isInteractiveRoot(root)) continue
                return root
            }
        }
        return currentActivity()?.window?.decorView?.takeIf { it.isAttachedToWindow }
    }

    private fun resolveTargetRootForPoint(screenX: Float, screenY: Float): View? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roots = runCatching { WindowInspector.getGlobalWindowViews() }.getOrNull().orEmpty()
            val loc = IntArray(2)
            var topmostInteractive: View? = null
            for (i in roots.lastIndex downTo 0) {
                val root = roots[i]
                if (!isInteractiveRoot(root)) continue
                if (topmostInteractive == null) {
                    topmostInteractive = root
                }
                root.getLocationOnScreen(loc)
                val inside = screenX >= loc[0] &&
                    screenX <= loc[0] + root.width &&
                    screenY >= loc[1] &&
                    screenY <= loc[1] + root.height
                if (inside) return root
                // 若顶层是模态窗口（无 FLAG_NOT_TOUCH_MODAL），即使落在弹窗外部也要交给它以支持点空白关闭。
                val params = root.layoutParams as? WindowManager.LayoutParams
                val isModal = params != null && (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) == 0
                if (isModal) return root
            }
            if (topmostInteractive != null) return topmostInteractive
        }
        return currentActivity()?.window?.decorView?.takeIf { it.isAttachedToWindow }
    }

    private fun isInteractiveRoot(root: View): Boolean {
        if (!root.isAttachedToWindow || root.visibility != View.VISIBLE) return false
        if (root.width <= 0 || root.height <= 0) return false
        val params = root.layoutParams as? WindowManager.LayoutParams
        if (params != null && (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) {
            return false
        }
        return true
    }

    private fun toLocalCoordinates(target: View, screenX: Float, screenY: Float): Pair<Float, Float> {
        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        return (screenX - loc[0]) to (screenY - loc[1])
    }

    private fun findDialogWindow(root: View): Window? {
        val queue = ArrayDeque<View>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 48) {
            val current = queue.removeFirst()
            visited++
            if (current is DialogWindowProvider) {
                return current.window
            }
            if (current is ViewGroup) {
                for (index in 0 until current.childCount) {
                    queue.add(current.getChildAt(index))
                }
            }
        }
        return null
    }
}

private class GamepadWindowCallbackProxy(
    private val original: Window.Callback,
    private val onKey: (KeyEvent) -> Boolean,
    private val onGenericMotion: (MotionEvent) -> Boolean,
    private val onTouch: (MotionEvent) -> Unit,
) : Window.Callback by original {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (onKey(event)) return true
        return original.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (onGenericMotion(event)) return true
        return original.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        onTouch(event)
        return original.dispatchTouchEvent(event)
    }
}

private class DialogCursorOverlayDrawable : Drawable() {
    private var x: Float = 0f
    private var y: Float = 0f
    private var pressed: Boolean = false
    private var precisionAim: Boolean = false
    private var density: Float = 2f

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun update(
        localX: Float,
        localY: Float,
        pressed: Boolean,
        precisionAim: Boolean,
        density: Float,
    ) {
        this.x = localX
        this.y = localY
        this.pressed = pressed
        this.precisionAim = precisionAim
        this.density = density.coerceAtLeast(1f)
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val outerRadius = (if (pressed) 13f else if (precisionAim) 11f else 15f) * density
        val accentColor = when {
            pressed -> Color.argb(240, 251, 114, 153)
            precisionAim -> Color.argb(235, 56, 189, 248)
            else -> Color.argb(235, 255, 255, 255)
        }
        haloPaint.color = if (pressed) Color.argb(72, 251, 114, 153) else Color.argb(56, 0, 0, 0)
        canvas.drawCircle(x, y, outerRadius + 3f * density, haloPaint)

        ringPaint.color = accentColor
        ringPaint.strokeWidth = 2.4f * density
        canvas.drawCircle(x, y, outerRadius, ringPaint)

        centerPaint.color = accentColor
        canvas.drawCircle(x, y, 2.8f * density, centerPaint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
