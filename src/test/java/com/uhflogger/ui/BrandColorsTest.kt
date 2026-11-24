package com.uhflogger.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Trava os tokens de `res/values/colors.xml` nos valores do CSS de
 * app.spacevis.com.br. Se a plataforma mudar uma cor, este teste é o lugar de
 * atualizar junto — e um hex trocado por engano no XML quebra aqui, não em campo.
 */
class BrandColorsTest {

    private val platformTokens = mapOf(
        "sv_primary" to "#31814F",
        "sv_background" to "#FAFAFA",
        "sv_card" to "#FFFFFF",
        "sv_foreground" to "#193423",
        "sv_muted" to "#F0F4F2",
        "sv_muted_foreground" to "#527A61",
        "sv_border" to "#E2E9E4",
        "sv_input" to "#E8EEEA",
        "sv_sidebar" to "#265939",
        "sv_sidebar_accent" to "#326745",
        "sv_warning" to "#EE9D2B",
        "sv_warning_foreground" to "#372315",
        "sv_error" to "#DF3020",
    )

    private fun colors(): Map<String, String> {
        // Testes unitários rodam com o diretório do módulo como working dir.
        val file = File("src/main/res/values/colors.xml")
        assertTrue("colors.xml não encontrado em ${file.absolutePath}", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("color")
        return (0 until nodes.length).associate { i ->
            val el = nodes.item(i)
            el.attributes.getNamedItem("name").nodeValue to el.textContent.trim().uppercase()
        }
    }

    @Test
    fun `tokens batem com a plataforma`() {
        val actual = colors()
        platformTokens.forEach { (name, hex) ->
            assertEquals("token $name", hex, actual[name])
        }
    }

    @Test
    fun `verde Material antigo nao voltou`() {
        val legacy = setOf("#2E7D32", "#FF2E7D32", "#00E5FF", "#3DDC84")
        val leaked = colors().filterValues { it in legacy }
        assertTrue("cores antigas no colors.xml: $leaked", leaked.isEmpty())
    }
}
