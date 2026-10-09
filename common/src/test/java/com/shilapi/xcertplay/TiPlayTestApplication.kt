package com.shilapi.xcertplay

import android.app.Application

/**
 * The Robolectric application of this module (src/test/resources/robolectric.properties).
 *
 * Phone + browser mode is TiPlay's default, so the activities and the session service start [TeslaBrowserLink] in
 * most tests. Here its server opens no socket: no unit test listens on port 8080 of every address, and a test never
 * depends on whether that port is free. Tests of the link itself install their own factory.
 */
class TiPlayTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TeslaBrowserLink.serverFactory = noSocketServers
    }

    internal companion object {
        val noSocketServers = TeslaBrowserLink.ServerFactory { _, _, _ ->
            object : TeslaBrowserLink.Server {
                override fun start(): Result<Int> = Result.success(TeslaBrowserLink.PORT)
                override fun stop() = Unit
            }
        }
    }
}
