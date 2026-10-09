package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BrandNameResourcesTest {
    private val attribution = "receiver_based_on_xcertplay_licensed_under_gpl_3_0_diplay"

    @Test fun userVisibleStringsSayTeslaPlay() {
        val folders = File("src/main/res").listFiles { f -> f.name.startsWith("values") }!! +
            File("../mobile/src/main/res").listFiles { f -> f.name.startsWith("values") }!!
        val stale = folders.flatMap { folder ->
            folder.listFiles { f -> f.extension == "xml" }!!.flatMap { file ->
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
                (0 until nodes.length).map { nodes.item(it) }
                    .filter { it.attributes.getNamedItem("name").nodeValue != attribution && it.textContent.contains("DiPlay") }
                    .map { "${folder.name}/${file.name}:${it.attributes.getNamedItem("name").nodeValue}" }
            }
        }
        assertEquals(emptyList<String>(), stale)
    }
}
