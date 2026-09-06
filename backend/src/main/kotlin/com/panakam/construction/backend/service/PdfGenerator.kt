package com.panakam.construction.backend.service

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayOutputStream

/**
 * Renders plain text into a simple multi-page A4 PDF. Used to turn an agreement's
 * placeholder-substituted text content into a shareable/downloadable PDF document
 * (see AgreementRoutes.kt) — no rich formatting, just readable line-wrapped text,
 * good enough for a draft the customer reviews before the builder's actual legal
 * signing process (which happens outside this app).
 */
object PdfGenerator {
    private const val MARGIN = 50f
    private const val FONT_SIZE = 11f
    private const val LEADING = 15f

    fun textToPdf(title: String, body: String): ByteArray {
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        val boldFont = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
        val doc = PDDocument()
        try {
            var page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            var stream = PDPageContentStream(doc, page)
            var y = PDRectangle.A4.height - MARGIN
            val maxWidth = PDRectangle.A4.width - 2 * MARGIN

            fun newPage() {
                stream.close()
                page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                stream = PDPageContentStream(doc, page)
                y = PDRectangle.A4.height - MARGIN
            }

            fun wrapLine(line: String, useFont: PDType1Font, size: Float): List<String> {
                if (line.isBlank()) return listOf("")
                val words = line.split(" ")
                val wrapped = mutableListOf<String>()
                var current = StringBuilder()
                for (word in words) {
                    val trial = if (current.isEmpty()) word else "$current $word"
                    val width = useFont.getStringWidth(trial) / 1000 * size
                    if (width > maxWidth && current.isNotEmpty()) {
                        wrapped += current.toString()
                        current = StringBuilder(word)
                    } else {
                        current = StringBuilder(trial)
                    }
                }
                if (current.isNotEmpty()) wrapped += current.toString()
                return wrapped
            }

            // Title
            stream.beginText()
            stream.setFont(boldFont, 16f)
            stream.newLineAtOffset(MARGIN, y)
            stream.showText(title.take(90))
            stream.endText()
            y -= LEADING * 2

            for (rawLine in body.split("\n")) {
                for (line in wrapLine(rawLine, font, FONT_SIZE)) {
                    if (y < MARGIN) newPage()
                    stream.beginText()
                    stream.setFont(font, FONT_SIZE)
                    stream.newLineAtOffset(MARGIN, y)
                    stream.showText(sanitize(line))
                    stream.endText()
                    y -= LEADING
                }
            }
            stream.close()

            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        } finally {
            doc.close()
        }
    }

    /** Strips characters not supported by the built-in WinAnsi encoding (Helvetica)
     *  so PDFBox doesn't throw on unusual OCR/unicode characters. */
    private fun sanitize(s: String): String =
        s.map { if (it.code in 32..255) it else '?' }.joinToString("")
}

