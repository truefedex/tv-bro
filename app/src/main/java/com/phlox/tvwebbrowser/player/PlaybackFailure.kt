package com.phlox.tvwebbrowser.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

/** Why the built-in player could not play a video, for the words of its error card */
sealed interface PlaybackFailure {
    /** The server of the video refused it (401, 403) */
    data class Refused(val status: Int) : PlaybackFailure
    /** The video is not on its server (404, 410) */
    data class NotFound(val status: Int) : PlaybackFailure
    /** Another status of the server */
    data class Server(val status: Int) : PlaybackFailure
    /** No connection to the server, or it did not answer in time */
    data object Network : PlaybackFailure
    /** The player can not read or decode the video */
    data object Format : PlaybackFailure
    /** The video is protected (DRM) */
    data object Protected : PlaybackFailure
    /** Something else: the name of the error of Media3 */
    data class Other(val name: String) : PlaybackFailure

    companion object {
        fun of(error: PlaybackException): PlaybackFailure {
            var cause: Throwable? = error
            while (cause != null) {
                if (cause is HttpDataSource.InvalidResponseCodeException) {
                    val status = cause.responseCode
                    return when (status) {
                        401, 403 -> Refused(status)
                        404, 410 -> NotFound(status)
                        else -> Server(status)
                    }
                }
                cause = cause.cause
            }
            return when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> Network
                PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> Format
                in PARSING_ERRORS, in DECODING_ERRORS -> Format
                in DRM_ERRORS -> Protected
                else -> Other(error.errorCodeName)
            }
        }

        /** The codes of Media3 by their thousands: 3xxx parsing, 4xxx decoding, 6xxx DRM */
        private val PARSING_ERRORS = 3000..3999
        private val DECODING_ERRORS = 4000..4999
        private val DRM_ERRORS = 6000..6999
    }
}
