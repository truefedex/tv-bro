package com.phlox.tvwebbrowser.utils

import java.io.File
import java.io.IOException

object FileUtils {
    @Throws(IOException::class, InterruptedException::class)
    fun deleteDirectory(file: File): Boolean {
        if (file.exists()) {
            val deleteCommand = "rm -rf " + file.getAbsolutePath()
            val runtime = Runtime.getRuntime()
            val process = runtime.exec(deleteCommand)
            process.waitFor()
            return true
        }
        return false
    }
}