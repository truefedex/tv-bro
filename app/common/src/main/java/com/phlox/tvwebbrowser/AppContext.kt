package com.phlox.tvwebbrowser

import android.app.Application
import android.content.Context

class AppContext {
    companion object {
        private var instance: Application? = null
        private var config: Config? = null

        /**
         * The browser shows the kids profile (set by the browser activity before it gets its models; the
         * profiles replace each other, see `MainActivity`): its pages keep their data apart from the main
         * profile, and so does the app (its own database, `AppContainer.data`)
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