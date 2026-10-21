package com.phlox.tvwebbrowser.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareCodecsTest {
    @Test
    fun androidTellsItFromAndroid10() {
        assertTrue(HardwareCodecs.isHardware("c2.android.av1.decoder", true))
        assertFalse(HardwareCodecs.isHardware("OMX.Nvidia.vp9.decode", false))
    }

    @Test
    fun beforeTheCodecsOfAndroidAreSoftware() {
        assertFalse(HardwareCodecs.isHardware("c2.android.av1.decoder", null))
        assertFalse(HardwareCodecs.isHardware("OMX.google.vp9.decoder", null))
        assertFalse(HardwareCodecs.isHardware("c2.google.av1.decoder", null))
        assertFalse(HardwareCodecs.isHardware("OMX.sec.av1.sw.dec", null))
        assertFalse(HardwareCodecs.isHardware("OMX.ffmpeg.av1.decoder", null))
        assertTrue(HardwareCodecs.isHardware("OMX.Nvidia.vp9.decode", null))
        assertTrue(HardwareCodecs.isHardware("c2.amlogic.av1.decoder", null))
        assertTrue(HardwareCodecs.isHardware("OMX.MTK.VIDEO.DECODER.AV1", null))
    }
}
