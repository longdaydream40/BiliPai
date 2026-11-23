package com.android.purebilibili.core.input.gamepad

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GamepadAxisNormalizerTest {

    @Test
    fun radialDeadzoneSuppressesStickDriftAndRescalesSmoothly() {
        val inside = normalizeRadialStick(rawX = 0.10f, rawY = 0.08f, deadzone = 0.15f)
        assertFalse(inside.isActive)
        assertEquals(0f, inside.x)
        assertEquals(0f, inside.y)

        val slight = normalizeRadialStick(rawX = 0.30f, rawY = 0f, deadzone = 0.15f)
        assertTrue(slight.isActive)
        assertTrue(slight.x in 0.01f..0.15f)
        assertEquals(0f, slight.y)

        val full = normalizeRadialStick(rawX = 1.0f, rawY = 0f, deadzone = 0.15f)
        assertTrue(abs(full.x - 1.0f) < 1e-4f)
        assertEquals(0f, full.y)
    }

    @Test
    fun precisionAimReducesCursorVelocity() {
        val stick = NormalizedStickVector(x = 0.8f, y = 0.4f)
        val normal = computeCursorStepPx(
            vector = stick,
            deltaSeconds = 1f / 60f,
            density = 2f,
            sensitivity = 1.0f,
            precisionAimHeld = false,
        )
        val precision = computeCursorStepPx(
            vector = stick,
            deltaSeconds = 1f / 60f,
            density = 2f,
            sensitivity = 1.0f,
            precisionAimHeld = true,
        )
        assertTrue(normal.first > 0f)
        assertTrue(precision.first < normal.first * 0.4f)
    }

    @Test
    fun triggerFallbackAndHysteresisPreventChatter() {
        assertEquals(0.75f, resolveTriggerValue(primaryAxis = 0f, fallbackAxis = 0.75f))
        assertEquals(0.90f, resolveTriggerValue(primaryAxis = 0.90f, fallbackAxis = 0.2f))

        assertFalse(resolveTriggerPressed(triggerValue = 0.45f, currentlyPressed = false))
        assertTrue(resolveTriggerPressed(triggerValue = 0.60f, currentlyPressed = false))
        assertTrue(resolveTriggerPressed(triggerValue = 0.40f, currentlyPressed = true))
        assertFalse(resolveTriggerPressed(triggerValue = 0.20f, currentlyPressed = true))
    }

    @Test
    fun directionalRepeatEmitsInitialEdgeThenRepeatsAfterDelay() {
        val tracker = DirectionalRepeatTracker(initialDelayMs = 300L, repeatIntervalMs = 100L)
        assertEquals(GamepadDirection.RIGHT, tracker.update(GamepadDirection.RIGHT, nowMs = 1000L))
        assertNull(tracker.update(GamepadDirection.RIGHT, nowMs = 1200L))
        assertEquals(GamepadDirection.RIGHT, tracker.update(GamepadDirection.RIGHT, nowMs = 1300L))
        assertNull(tracker.update(GamepadDirection.RIGHT, nowMs = 1350L))
        assertEquals(GamepadDirection.RIGHT, tracker.update(GamepadDirection.RIGHT, nowMs = 1400L))
        assertNull(tracker.update(null, nowMs = 1450L))
        assertEquals(GamepadDirection.LEFT, tracker.update(GamepadDirection.LEFT, nowMs = 1460L))
    }

    @Test
    fun dominantDirectionResolvesHatAxesCleanly() {
        assertNull(resolveDominantDirection(0.2f, -0.3f))
        assertEquals(GamepadDirection.LEFT, resolveDominantDirection(-1f, 0f))
        assertEquals(GamepadDirection.DOWN, resolveDominantDirection(0.1f, 0.9f))
        assertEquals(GamepadDirection.UP, resolveDominantDirection(0f, -0.8f))
    }
}
