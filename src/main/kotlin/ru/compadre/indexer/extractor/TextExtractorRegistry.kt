package ru.compadre.indexer.extractor

import ru.compadre.indexer.model.SourceType

/**
 * Хранит набор доступных extractors и выбирает подходящий по типу источника.
 */
class TextExtractorRegistry(
    private val extractors: List<TextExtractor>,
) {
    fun getFor(sourceType: SourceType): TextExtractor =
        extractors.firstOrNull { it.supports(sourceType) }
            ?: error("Не найден TextExtractor для sourceType `$sourceType`.")
}
