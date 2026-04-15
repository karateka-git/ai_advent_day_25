package ru.compadre.indexer.extractor

import ru.compadre.indexer.model.SourceType
import java.nio.file.Path

/**
 * Контракт извлечения текста из поддерживаемого документа.
 */
interface TextExtractor {
    /**
     * Возвращает `true`, если extractor умеет работать с указанным типом источника.
     */
    fun supports(sourceType: SourceType): Boolean

    /**
     * Извлекает текстовое содержимое документа.
     */
    fun extract(path: Path, sourceType: SourceType): String
}
