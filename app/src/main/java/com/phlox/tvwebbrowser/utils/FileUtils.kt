package com.phlox.tvwebbrowser.utils

import android.content.Context
import java.io.File
import java.io.IOException

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

fun extractAssets(ctx: Context, assetsDir: String, destDir: File) {
    val assetManager = ctx.assets
    val files = assetManager.list(assetsDir)
    if (files != null) {
        for (file in files) {
            val fileName = assetsDir + File.separator + file
            val destFile = File(destDir, file)
            if (file.contains(".")) {
                copyAssetFile(ctx, fileName, destFile)
            } else {
                destFile.mkdirs()
                extractAssets(ctx, fileName, destFile)
            }
        }
    }
}

fun copyAssetFile(ctx: Context, assetPath: String, destFile: File) {
    val assetManager = ctx.assets
    val `in` = assetManager.open(assetPath)
    val out = destFile.outputStream()
    `in`.copyTo(out)
    `in`.close()
    out.close()
}