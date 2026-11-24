package com.airbridge.airplay.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AirBridge must never take audio focus: a music app such as Spotify pauses when another app
 * requests focus, and AirBridge only captures what that app plays. AirBridge also never plays audio
 * itself, so nothing in it can request focus indirectly (players such as MediaPlayer can). This
 * guard reads the app's own source code (comments removed) and fails if a focus API or an audio
 * player appears, and checks that capture uses AudioPlaybackCapture rather than a microphone source.
 */
class NoAudioFocusTest {
    private val mainDir: File = listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
        ?: throw AssertionError("app/src/main not found from ${File(".").absolutePath}")

    /** Removes // and /* */ comments so documentation may mention what the code never does. */
    private fun code(text: String): String {
        val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(text, " ")
        return noBlock.lines().joinToString("\n") { line ->
            val i = line.indexOf("//")
            if (i >= 0 && line.substring(0, i).count { it == '"' } % 2 == 0) line.substring(0, i) else line
        }
    }

    private val sources: Map<String, String> by lazy {
        mainDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java" || it.extension == "xml") }
            .associate { it.relativeTo(mainDir).path to code(it.readText()) }
    }

    @Test
    fun noAudioFocusRequestsAndNoLocalPlaybackAnywhere() {
        assertTrue("found the app sources", sources.keys.any { it.endsWith("CaptureEngine.kt") })
        val forbidden = listOf(
            "requestAudioFocus", "abandonAudioFocus", "AudioFocusRequest", "OnAudioFocusChangeListener", "AUDIOFOCUS_",
            "AudioTrack", "MediaPlayer", "SoundPool", "ExoPlayer", "Ringtone", "ToneGenerator",
        )
        val hits = sources.flatMap { (path, text) -> forbidden.filter { text.contains(it) }.map { "$path: $it" } }
        assertEquals("audio focus or local playback found", emptyList<String>(), hits)
    }

    @Test
    fun captureUsesPlaybackCaptureNotTheMicrophone() {
        val engine = sources.entries.single { it.key.endsWith("CaptureEngine.kt") }.value
        assertTrue(engine.contains("setAudioPlaybackCaptureConfig"))
        val micSources = listOf("AudioSource.MIC", "VOICE_COMMUNICATION", "VOICE_RECOGNITION", "CAMCORDER", "setAudioSource")
        val hits = sources.flatMap { (path, text) -> micSources.filter { text.contains(it) }.map { "$path: $it" } }
        assertEquals("microphone sources found", emptyList<String>(), hits)
    }
}
