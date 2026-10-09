package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CarBluetoothAudioSessionTest {
    private val logs = CopyOnWriteArrayList<String>()

    @Test
    fun carBluetoothSoundDeclinesEveryAudioSetupBeforeTheMediaHandler() {
        val media = CountingMedia()
        val session = session(base.copy(audioViaCarBluetooth = true), media)
        try {
            handle(session, "RECORD", ByteArray(0))
            val response = handle(session, "SETUP", audioSetup())
            val decoded = BplistCodec.decode(response.body) as Map<*, *>
            assertEquals(emptyList<Any>(), decoded["streams"])
            assertEquals(0, media.audioSetups)
            assertTrue(logs.contains("airplay audio route=car-bluetooth expecting no audio SETUP"))
            assertTrue(logs.contains(
                "airplay audio SETUP type=100 audioType=media formatBits=0x8000 audioRoute=car-bluetooth " +
                    "result=declined dataPort=none controlPort=none",
            ))
        } finally { session.close() }
        assertTrue(logs.contains(
            "airplay audio summary audioRoute=car-bluetooth audioSetups=3 streams=100/media,101/default,102/media",
        ))
    }

    @Test
    fun withoutCarBluetoothSoundEveryAudioSetupReachesTheMediaHandler() {
        val media = CountingMedia()
        val session = session(base, media)
        try {
            val response = handle(session, "SETUP", audioSetup())
            val decoded = BplistCodec.decode(response.body) as Map<*, *>
            assertEquals(3, (decoded["streams"] as List<*>).size)
            assertEquals(3, media.audioSetups)
            assertTrue(logs.contains(
                "airplay audio SETUP type=100 audioType=media formatBits=0x8000 audioRoute=tiplay " +
                    "result=accepted dataPort=1 controlPort=none",
            ))
        } finally { session.close() }
    }

    @Test
    fun infoLeavesOutAudioFormatsAndLogsTheRoute() {
        val session = session(base.copy(audioViaCarBluetooth = true), CountingMedia())
        try {
            val response = handle(session, "GET", ByteArray(0), path = "/info")
            val info = BplistCodec.decode(response.body) as Map<*, *>
            assertFalse(info.containsKey("audioFormats"))
            assertEquals(9, (info["audioLatencies"] as List<*>).size)
            assertEquals(0x615653aee2L, (info["features"] as Number).toLong())
            assertTrue(logs.contains("airplay /info audioRoute=car-bluetooth audioFormats=0 audioLatencies=9 features=0x615653aee2"))
            handle(session, "RECORD", ByteArray(0))
        } finally { session.close() }
        assertTrue(logs.contains("airplay audio summary audioRoute=car-bluetooth audioSetups=0 streams=none"))
    }

    @Test
    fun alternativeVariantAlsoDeclinesAudioSetups() {
        val media = CountingMedia()
        val session = session(base.copy(audioViaCarBluetooth = true, disableAudioOutput = true), media)
        try {
            val body = BplistCodec.encode(mapOf("streams" to listOf(mapOf("type" to 100, "audioType" to "telephony"))))
            val decoded = BplistCodec.decode(handle(session, "SETUP", body).body) as Map<*, *>
            assertEquals(emptyList<Any>(), decoded["streams"])
            assertEquals(0, media.audioSetups)
            assertTrue(logs.contains(
                "airplay audio SETUP type=100 audioType=telephony formatBits=none audioRoute=car-bluetooth-alternative " +
                    "result=declined dataPort=none controlPort=none",
            ))
        } finally { session.close() }
    }

    @Test
    fun carBluetoothSoundAlsoDeclinesBufferedMusicAndIgnoresItsControls() {
        val media = CountingMedia()
        val session = session(base.copy(audioViaCarBluetooth = true, mainBufferedAudio = true), media)
        try {
            val body = BplistCodec.encode(mapOf("streams" to listOf(mapOf("type" to 103, "audioType" to "media"))))
            val decoded = BplistCodec.decode(handle(session, "SETUP", body).body) as Map<*, *>
            assertEquals(emptyList<Any>(), decoded["streams"])
            assertEquals(0, media.bufferedSetups)
            handle(session, "SETRATEANCHORTIME", BplistCodec.encode(mapOf("rate" to 1)))
            handle(session, "GETANCHOR", ByteArray(0))
            assertEquals(0, media.bufferedControls)
            assertTrue(logs.contains(
                "airplay audio SETUP type=103 audioType=media formatBits=none audioRoute=car-bluetooth " +
                    "result=declined dataPort=none controlPort=none",
            ))
        } finally { session.close() }
    }

    private class CountingMedia : AirPlayMediaHandler {
        var audioSetups = 0
        var bufferedSetups = 0
        var bufferedControls = 0

        override fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?>? {
            audioSetups++
            return mapOf("type" to type, "dataPort" to 1)
        }

        override fun onBufferedAudio(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
            bufferedSetups++
            return mapOf("type" to 103, "dataPort" to 1)
        }

        override fun onBufferedAudioControl(session: AirPlaySession, method: String, body: Map<String, Any?>): Map<String, Any?>? {
            bufferedControls++
            return null
        }
    }

    private val base = AirPlayConfig(
        deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01", sourceVersion = "1.0",
        main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
    )

    private fun audioSetup(): ByteArray = BplistCodec.encode(mapOf("streams" to listOf(
        mapOf("type" to 100, "audioType" to "media", "audioFormat" to 0x8000),
        mapOf("type" to 101, "audioType" to "default"),
        mapOf("type" to 102, "audioType" to "media"),
    )))

    private fun handle(session: AirPlaySession, method: String, body: ByteArray, path: String = "rtsp://test"): RtspMessage.Response =
        AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java).apply { isAccessible = true }
            .invoke(session, RtspMessage.Request(method, path, "RTSP/1.0", emptyMap(), body)) as RtspMessage.Response

    private fun session(config: AirPlayConfig, media: AirPlayMediaHandler): AirPlaySession = AirPlaySession(
        socket = object : Socket() {
            override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 1234)
        },
        config = config,
        identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
        listener = object : AirPlaySessionListener {
            override fun onDebugLog(message: String) { logs += message }
        },
        media = media,
    )
}
