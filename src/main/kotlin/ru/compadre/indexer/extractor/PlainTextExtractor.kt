package ru.compadre.indexer.extractor

import ru.compadre.indexer.model.SourceType
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Извлекает текст из plain text, markdown, README и исходного кода без дополнительной обработки.
 */
class PlainTextExtractor : TextExtractor {
    private val supportedTypes = setOf(
        SourceType.README,
        SourceType.MARKDOWN,
        SourceType.TEXT,
        SourceType.CODE,
    )

    override fun supports(sourceType: SourceType): Boolean = sourceType in supportedTypes

    override fun extract(path: Path, sourceType: SourceType): String =
        Files.readString(path, StandardCharsets.UTF_8)
}
