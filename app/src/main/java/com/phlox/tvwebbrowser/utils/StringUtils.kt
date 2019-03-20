package com.phlox.tvwebbrowser.utils

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

object StringUtils {
    @Throws(IOException::class)
    fun streamToString(stream: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        var read = stream.read(buffer)
        while (read > -1) {
            output.write(buffer, 0, read)
            read = stream.read(buffer)
        }
        val result = String(output.toByteArray())
        output.close()
        return result
    }
}
