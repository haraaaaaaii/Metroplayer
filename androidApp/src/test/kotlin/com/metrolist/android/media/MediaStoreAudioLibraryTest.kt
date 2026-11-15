package com.metrolist.android.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreAudioLibraryTest {
    @Test
    fun supportsLocalAudioFormatsByMimeTypeOrExtension() {
        assertTrue(isSupportedAudioFile("audio/flac", "track.bin"))
        assertTrue(isSupportedAudioFile("audio/wav", "track.bin"))
        assertTrue(isSupportedAudioFile("audio/mp4", "track.bin"))
        assertTrue(isSupportedAudioFile("audio/mpeg", "track.bin"))
        assertTrue(isSupportedAudioFile(null, "track.FLAC"))
        assertTrue(isSupportedAudioFile(null, "track.wav"))
        assertTrue(isSupportedAudioFile(null, "track.m4a"))
        assertTrue(isSupportedAudioFile(null, "track.mp3"))
    }

    @Test
    fun rejectsUnsupportedAudioFormats() {
        assertFalse(isSupportedAudioFile("audio/ogg", "track.ogg"))
        assertFalse(isSupportedAudioFile("audio/aac", "track.aac"))
        assertFalse(isSupportedAudioFile(null, "cover.jpg"))
    }
}
