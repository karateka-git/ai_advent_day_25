package ru.compadre.indexer.extractor

/**
 * Нормализует текст документов после извлечения.
 */
object TextNormalizer {
    fun normalize(text: String): String {
        val normalizedLineBreaks = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        val compactBlocks = normalizedLineBreaks
            .replace(Regex("\n[\\t ]+\n"), "\n\n")
            .replace(Regex("\n{3,}"), "\n\n")

        return compactBlocks.trim()
    }
}
