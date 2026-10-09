package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayAudioDiagnosticsTest {
    private val base = AirPlayConfig(
        deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01", sourceVersion = "1.0",
        main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
    )
    private val bt = base.copy(audioViaCarBluetooth = true)

    @Test fun infoReportsTheRouteAndHowMuchAudioIsDeclared() {
        assertEquals("airplay /info audioRoute=car-bluetooth audioFormats=0 audioLatencies=9 features=0x615653aee2",
            AirPlayAudioDiagnostics.info(bt, AirPlayInfoPlist.build(bt)))
        assertEquals("airplay /info audioRoute=tiplay audioFormats=9 audioLatencies=9 features=0x615653aee2",
            AirPlayAudioDiagnostics.info(base, AirPlayInfoPlist.build(base)))
        val alternative = bt.copy(disableAudioOutput = true)
        assertEquals("airplay /info audioRoute=car-bluetooth-alternative audioFormats=0 audioLatencies=0 features=0x615203a4e2",
            AirPlayAudioDiagnostics.info(alternative, AirPlayInfoPlist.build(alternative)))
        assertEquals("airplay /info audioRoute=disabled audioFormats=0 audioLatencies=0 features=0x0",
            AirPlayAudioDiagnostics.info(base.copy(disableAudioOutput = true), emptyMap()))
    }

    @Test fun setupNamesTheStreamFormatAndResultWithoutItsContents() {
        val stream = mapOf<String, Any?>("type" to 100L, "audioType" to "media", "audioFormat" to 0x8000L, "shk" to ByteArray(32))
        assertEquals(
            "airplay audio SETUP type=100 audioType=media formatBits=0x8000 audioRoute=car-bluetooth " +
                "result=declined dataPort=none controlPort=none",
            AirPlayAudioDiagnostics.setup(bt, 100, stream, null),
        )
        assertEquals(
            "airplay audio SETUP type=100 audioType=media formatBits=0x8000 audioRoute=tiplay " +
                "result=accepted dataPort=6000 controlPort=6001",
            AirPlayAudioDiagnostics.setup(base, 100, stream, mapOf("type" to 100, "dataPort" to 6000, "controlPort" to 6001)),
        )
        assertEquals(
            "airplay audio SETUP type=102 audioType=none formatBits=none audioRoute=tiplay " +
                "result=declined dataPort=none controlPort=none",
            AirPlayAudioDiagnostics.setup(base, 102, emptyMap(), null),
        )
    }

    @Test fun audioTypeKeepsOnlyKnownWords() {
        assertEquals("speechrecognition", AirPlayAudioDiagnostics.audioType(mapOf("audioType" to "speechRecognition")))
        assertEquals("other", AirPlayAudioDiagnostics.audioType(mapOf("audioType" to "Jane's iPhone")))
        assertEquals("none", AirPlayAudioDiagnostics.audioType(emptyMap()))
        assertEquals("101/other", AirPlayAudioDiagnostics.streamLabel(101, mapOf("audioType" to "password=secret")))
    }

    @Test fun recordedSaysWhetherAudioSetupsAreExpected() {
        assertEquals("airplay audio route=car-bluetooth expecting no audio SETUP", AirPlayAudioDiagnostics.recorded(bt))
        assertEquals("airplay audio route=tiplay receiving audio streams", AirPlayAudioDiagnostics.recorded(base))
    }

    @Test fun summaryCountsSetupsAndListsDistinctStreams() {
        assertEquals("airplay audio summary audioRoute=car-bluetooth audioSetups=0 streams=none",
            AirPlayAudioDiagnostics.summary(bt, emptyList()))
        assertEquals("airplay audio summary audioRoute=tiplay audioSetups=3 streams=100/media,101/default",
            AirPlayAudioDiagnostics.summary(base, listOf("100/media", "101/default", "100/media")))
    }
}
