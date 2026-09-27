package com.panakam.construction.backend.service

import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for Word (.docx) agreement template support (see AgreementRoutes.kt
 * template registration) — added after confirming the ONLY previously-supported template
 * file formats were .pdf and plain .txt; uploading a .docx silently fell through to the
 * `String(bytes, Charsets.UTF_8)` branch, which decodes a .docx's raw ZIP-archive bytes as
 * if they were text, producing binary garbage instead of the template's actual text.
 */
class OcrServiceDocxTemplateTest {

    private fun buildDocx(paragraphs: List<String>, tableRows: List<List<String>> = emptyList()): ByteArray {
        val doc = XWPFDocument()
        paragraphs.forEach { text ->
            val p = doc.createParagraph()
            p.createRun().setText(text)
        }
        if (tableRows.isNotEmpty()) {
            val table = doc.createTable(tableRows.size, tableRows[0].size)
            tableRows.forEachIndexed { r, row ->
                row.forEachIndexed { c, cell ->
                    table.getRow(r).getCell(c).text = cell
                }
            }
        }
        val out = ByteArrayOutputStream()
        doc.write(out)
        doc.close()
        return out.toByteArray()
    }

    @Test
    fun `extracts paragraph text with placeholders from a docx template`() {
        val bytes = buildDocx(listOf(
            "SALE AGREEMENT",
            "This agreement is between the builder and {{CUSTOMER_NAME}}, residing at {{ADDRESS}}.",
            "Total consideration: Rs. {{TOTAL_AMOUNT}}/- ({{TOTAL_AMOUNT_WORDS}})."
        ))

        val text = OcrService.extractTextFromDocx(ByteArrayInputStream(bytes))

        assertTrue(text.contains("{{CUSTOMER_NAME}}"))
        assertTrue(text.contains("{{ADDRESS}}"))
        assertTrue(text.contains("{{TOTAL_AMOUNT_WORDS}}"))
        assertTrue(text.contains("SALE AGREEMENT"))
    }

    @Test
    fun `extracts table cell text from a docx template`() {
        val bytes = buildDocx(
            paragraphs = listOf("Unit Details"),
            tableRows = listOf(
                listOf("Unit Number", "{{UNIT_NUMBER}}"),
                listOf("Floor", "{{FLOOR}}")
            )
        )

        val text = OcrService.extractTextFromDocx(ByteArrayInputStream(bytes))

        assertTrue(text.contains("{{UNIT_NUMBER}}"))
        assertTrue(text.contains("{{FLOOR}}"))
    }

    @Test
    fun `invalid docx bytes return empty string instead of throwing`() {
        val garbage = "not a real docx file".toByteArray()
        val text = OcrService.extractTextFromDocx(ByteArrayInputStream(garbage))
        assertEquals("", text)
    }
}

