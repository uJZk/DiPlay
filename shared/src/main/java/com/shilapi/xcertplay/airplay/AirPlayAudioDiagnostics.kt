package com.shilapi.xcertplay.airplay

/** Audio route evidence for the diagnostic report: fixed words, stream types and format bits, never stream contents. */
internal object AirPlayAudioDiagnostics {
    private val AUDIO_TYPES = setOf("compatibility", "default", "media", "telephony", "speechrecognition", "alert")

    fun route(config: AirPlayConfig): String = when {
        config.disableAudioOutput && config.audioViaCarBluetooth -> "car-bluetooth-alternative"
        config.audioViaCarBluetooth -> "car-bluetooth"
        config.disableAudioOutput -> "disabled"
        else -> "teslaplay"
    }

    fun audioType(stream: Map<String, Any?>): String {
        val raw = stream["audioType"] ?: return "none"
        return raw.toString().lowercase().takeIf { it in AUDIO_TYPES } ?: "other"
    }

    fun streamLabel(type: Int, stream: Map<String, Any?>): String = "$type/${audioType(stream)}"

    fun info(config: AirPlayConfig, info: Map<String, Any?>): String =
        "airplay /info audioRoute=${route(config)} audioFormats=${(info["audioFormats"] as? List<*>)?.size ?: 0} " +
            "audioLatencies=${(info["audioLatencies"] as? List<*>)?.size ?: 0} " +
            "features=0x${java.lang.Long.toHexString((info["features"] as? Number)?.toLong() ?: 0L)}"

    fun setup(config: AirPlayConfig, type: Int, stream: Map<String, Any?>, response: Map<String, Any?>?): String {
        val bits = (stream["audioFormat"] as? Number)?.toLong()
        return "airplay audio SETUP type=$type audioType=${audioType(stream)} " +
            "formatBits=${bits?.let { "0x" + java.lang.Long.toHexString(it) } ?: "none"} audioRoute=${route(config)} " +
            "result=${if (response != null) "accepted" else "declined"} " +
            "dataPort=${response?.get("dataPort") ?: "none"} controlPort=${response?.get("controlPort") ?: "none"}"
    }

    fun recorded(config: AirPlayConfig): String =
        "airplay audio route=${route(config)} " + if (config.receivesAudio) "receiving audio streams" else "expecting no audio SETUP"

    fun summary(config: AirPlayConfig, setups: List<String>): String =
        "airplay audio summary audioRoute=${route(config)} audioSetups=${setups.size} " +
            "streams=${setups.distinct().joinToString(",").ifEmpty { "none" }}"
}
