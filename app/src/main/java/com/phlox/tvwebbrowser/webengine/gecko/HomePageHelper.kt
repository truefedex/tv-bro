package com.phlox.tvwebbrowser.webengine.gecko

import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.utils.deleteDirectory
import com.phlox.tvwebbrowser.utils.extractAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object HomePageHelper {
    /*private const val HOME_PAGE_VERSION = 1
    var homePageFilesReady: Boolean = false
    private const val forceExtractHomePageFiles: Boolean = true//for debug only
    private const val HOME_PAGE_DIR_NAME = "home_page"
    val HOME_PAGE_URL = "file://${TVBro.instance.filesDir}/${HOME_PAGE_DIR_NAME}/index.html"

    suspend fun prepareHomePageFiles() {
        if (homePageFilesReady) return
        val ctx = TVBro.instance
        val config = TVBro.config
        val filesReady = withContext(Dispatchers.IO) {
            val homePageDir = ctx.filesDir.resolve(HOME_PAGE_DIR_NAME)
            try {
                if (!homePageDir.exists()) {
                    homePageDir.mkdirs()
                    extractAssets(ctx, "pages/home", homePageDir)
                } else if (config.homePageVersionExtracted != HOME_PAGE_VERSION || forceExtractHomePageFiles) {
                    deleteDirectory(homePageDir)
                    homePageDir.mkdirs()
                    extractAssets(ctx, "pages/home", homePageDir)
                    config.homePageVersionExtracted = HOME_PAGE_VERSION
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext false
            }
            true
        }

        homePageFilesReady = filesReady
    }*/
}