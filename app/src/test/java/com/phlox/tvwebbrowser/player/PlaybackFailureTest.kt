package com.phlox.tvwebbrowser.player

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackFailureTest {
    private fun httpError(status: Int): PlaybackException {
        val response = HttpDataSource.InvalidResponseCodeException(status, null, null, emptyMap(),
            DataSpec(Uri.parse("https://cdn.example/v.m3u8")), ByteArray(0))
        return PlaybackException("bad status", response, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
    }

    private fun error(code: Int) = PlaybackException("failed", null, code)

    @Test
    fun theStatusOfTheServerTellsWhy() {
        assertEquals(PlaybackFailure.Refused(403), PlaybackFailure.of(httpError(403)))
        assertEquals(PlaybackFailure.Refused(401), PlaybackFailure.of(httpError(401)))
        assertEquals(PlaybackFailure.NotFound(404), PlaybackFailure.of(httpError(404)))
        assertEquals(PlaybackFailure.Server(502), PlaybackFailure.of(httpError(502)))
    }

    @Test
    fun theCodeOfTheErrorTellsTheRest() {
        assertEquals(PlaybackFailure.Network, PlaybackFailure.of(error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
        assertEquals(PlaybackFailure.Network, PlaybackFailure.of(error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)))
        assertEquals(PlaybackFailure.Format, PlaybackFailure.of(error(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)))
        assertEquals(PlaybackFailure.Format, PlaybackFailure.of(error(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)))
        assertEquals(PlaybackFailure.Protected, PlaybackFailure.of(error(PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED)))
        assertEquals(PlaybackFailure.Other("ERROR_CODE_UNSPECIFIED"), PlaybackFailure.of(error(PlaybackException.ERROR_CODE_UNSPECIFIED)))
    }
}
