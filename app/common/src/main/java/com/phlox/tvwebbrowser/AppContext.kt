package com.phlox.tvwebbrowser

import android.app.Application
import android.content.Context

class AppContext {
    companion object {
        private var instance: Application? = null
        private var config: Config? = null

        /**
         * This process is the one of the kids profile (set by the application before anything else): its
         * pages keep their data apart from the main profile, and so does the app (its own database)
         */
        @Volatile
        var kidsProfile: Boolean = false

        fun init(app: Application, config: Config) {
            this.instance = app
            this.config = config
        }

        fun get(): Context {
            return instance ?: throw IllegalStateException("AppContext is not initialized")
        }

        fun provideConfig(): Config {
            return config ?: throw IllegalStateException("AppContext is not initialized")
        }
    }
}