package com.amihay.drumtrainer

import com.amihay.drumtrainer.data.PadStore
import com.amihay.drumtrainer.model.HitFeatures
import com.amihay.drumtrainer.model.Pad
import com.amihay.drumtrainer.model.TrainingSample
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PadStoreTest {
    @Test
    fun roundTripsSamples() {
        val dir = Files.createTempDirectory("pads").toFile()
        val store = PadStore(File(dir, "pads.json"))
        assertEquals(emptyList<TrainingSample>(), store.load())

        val samples = listOf(
            TrainingSample(Pad.KICK, HitFeatures(FloatArray(HitFeatures.NUM_BANDS) { -it.toFloat() }, -3.5f)),
            TrainingSample(Pad.CRASH, HitFeatures(FloatArray(HitFeatures.NUM_BANDS) { it * 0.5f }, 1.25f)),
        )
        store.save(samples)
        val back = store.load()
        assertEquals(listOf(Pad.KICK, Pad.CRASH), back.map { it.pad })
        assertArrayEquals(samples[1].features.bandsDb, back[1].features.bandsDb, 1e-6f)
        assertEquals(-3.5f, back[0].features.decayDb, 1e-6f)
    }

    @Test
    fun ignoresUnknownPadsAndCorruptFiles() {
        val text = """[{"pad":"cowbell","decayDb":0,"bandsDb":[]},""" +
            """{"pad":"snare","decayDb":0,"bandsDb":[${List(HitFeatures.NUM_BANDS) { "1" }.joinToString(",")}]}]"""
        assertEquals(listOf(Pad.SNARE), PadStore.decode(text).map { it.pad })

        val dir = Files.createTempDirectory("pads").toFile()
        File(dir, "pads.json").writeText("not json")
        assertEquals(emptyList<TrainingSample>(), PadStore(File(dir, "pads.json")).load())
    }
}
