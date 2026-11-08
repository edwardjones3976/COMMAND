package com.command.handshake

import org.junit.Assert.assertEquals
import org.junit.Test

/** How the route reads the camera as Android has it, and when turning the consent on asks Android for it: plain rules, with no screen. */
class RegisterTokenPermissionsTest {

    @Test
    fun `a phone with no camera has none, whatever else is known`() {
        for (granted in listOf(false, true)) {
            for (refused in listOf(false, true)) {
                assertEquals(CameraAccess.NONE, cameraAccess(hasCamera = false, granted = granted, refused = refused))
            }
        }
    }

    @Test
    fun `a camera that is given is given, even after an earlier refusal`() {
        assertEquals(CameraAccess.GRANTED, cameraAccess(hasCamera = true, granted = true, refused = false))
        assertEquals(CameraAccess.GRANTED, cameraAccess(hasCamera = true, granted = true, refused = true))
    }

    @Test
    fun `a camera that is not given is refused once Android said no, and not asked before that`() {
        assertEquals(CameraAccess.REFUSED, cameraAccess(hasCamera = true, granted = false, refused = true))
        assertEquals(CameraAccess.NOT_ASKED, cameraAccess(hasCamera = true, granted = false, refused = false))
    }

    @Test
    fun `turning the consent on asks Android only when there is a camera and it is not given yet`() {
        assertEquals(true, shouldAskAfterConsent(on = true, hasCamera = true, granted = false))
        assertEquals(false, shouldAskAfterConsent(on = true, hasCamera = true, granted = true))
        assertEquals(false, shouldAskAfterConsent(on = true, hasCamera = false, granted = false))
        assertEquals(false, shouldAskAfterConsent(on = true, hasCamera = false, granted = true))
    }

    @Test
    fun `turning the consent off never asks Android`() {
        for (hasCamera in listOf(false, true)) {
            for (granted in listOf(false, true)) {
                assertEquals(false, shouldAskAfterConsent(on = false, hasCamera = hasCamera, granted = granted))
            }
        }
    }
}
