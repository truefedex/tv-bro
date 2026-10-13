package com.phlox.tvwebbrowser.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRetryPolicyTest {
    @Test fun transientCodesAreRetried() {
        assertTrue(PlaybackRetryPolicy.isTransient(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertTrue(PlaybackRetryPolicy.isTransient(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertTrue(PlaybackRetryPolicy.isTransient(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertTrue(PlaybackRetryPolicy.isTransient(PlaybackException.ERROR_CODE_TIMEOUT))
        assertTrue(PlaybackRetryPolicy.isTransient(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
    }

    @Test fun fatalCodesAreNotRetried() {
        assertFalse(PlaybackRetryPolicy.isTransient(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertFalse(PlaybackRetryPolicy.isTransient(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
        assertFalse(PlaybackRetryPolicy.isTransient(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertFalse(PlaybackRetryPolicy.isTransient(PlaybackException.ERROR_CODE_DRM_UNSPECIFIED))
    }

    @Test fun backoffGrowsExponentially() {
        assertEquals(2000L, PlaybackRetryPolicy.backoffMs(1))
        assertEquals(4000L, PlaybackRetryPolicy.backoffMs(2))
        assertEquals(8000L, PlaybackRetryPolicy.backoffMs(3))
    }
}
