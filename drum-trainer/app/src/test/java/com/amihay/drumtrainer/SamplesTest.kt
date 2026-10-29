package com.amihay.drumtrainer

import com.amihay.drumtrainer.data.WavReader
import com.amihay.drumtrainer.model.Pad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class SamplesTest {
    @Test
    fun everyDrumHasARecordedSoundThatStartsOnTime() {
        for (pad in Pad.entries) {
            val sound = WavReader.read(File("src/main/assets/sounds/${pad.id}.wav").readBytes())
            assertNotNull(pad.id, sound)
            sound!!
            assertEquals(48000, sound.sampleRate)
            val peak = sound.samples.maxOf { abs(it) }
            assertTrue(pad.id, peak in 0.3f..1f)
            // The hit starts within a millisecond, so it sounds when the note is due.
            val onset = sound.samples.indexOfFirst { abs(it) > peak * 0.05f }
            assertTrue("${pad.id} starts at $onset", onset < 48)
            // Fades out to silence (no click at the end).
            assertTrue(pad.id, abs(sound.samples.last()) < 0.001f)
        }
    }

    @Test
    fun rejectsWhatItCannotRead() {
        assertNull(WavReader.read(ByteArray(10)))
        assertNull(WavReader.read("RIFF....WAVEjunk".toByteArray()))
    }
}
