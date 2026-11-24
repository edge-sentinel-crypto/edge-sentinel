package com.airbridge.airplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-app capture texts state what Android actually does: playback capture works with Spotify
 * and most media apps when the app and the phone allow it, and gives silence only for an app (or
 * app version) that opts out. No text claims that Spotify always blocks capture.
 */
class CaptureWordingTest {
    private val blanketClaims = listOf(
        "Spotify does this", "Spotify blocks", "Spotify refuses", "Spotify cannot", "Spotify does not work",
        "cannot be streamed", "Spotify (and some other DRM",
    )

    @Test
    fun silenceBannerBlamesTheOptOutNotSpotify() {
        for (claim in blanketClaims) assertFalse("banner says \"$claim\"", CAPTURE_BLOCKED_TEXT.contains(claim, ignoreCase = true))
        assertTrue(CAPTURE_BLOCKED_TEXT.contains("seems to block Android's playback capture"))
        assertTrue(CAPTURE_BLOCKED_TEXT.contains("(or this version of it)"))
        assertTrue("suggests settings, another player, Connect or Cast", CAPTURE_BLOCKED_TEXT.contains("settings") && CAPTURE_BLOCKED_TEXT.contains("another player") && CAPTURE_BLOCKED_TEXT.contains("Cast"))
    }

    @Test
    fun helpTextExplainsWhenCaptureWorks() {
        for (claim in blanketClaims) assertFalse("help says \"$claim\"", SILENT_CAPTURE_HELP.contains(claim, ignoreCase = true))
        assertTrue(SILENT_CAPTURE_HELP.contains("works with Spotify and most other media apps when the app and the phone allow capture"))
        assertTrue(SILENT_CAPTURE_HELP.contains("allowAudioPlaybackCapture=\"false\""))
        assertTrue(SILENT_CAPTURE_HELP.contains("no root and no workarounds"))
    }

    @Test
    fun captureFormatIsShownInDiagnostics() {
        assertEquals("", captureFormatText(0))
        assertEquals(" (44.1 kHz)", captureFormatText(44_100))
        assertEquals(" (48 kHz, converted to 44.1 kHz)", captureFormatText(48_000))
    }
}
