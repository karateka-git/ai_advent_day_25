package ru.compadre.indexer.model

/**
 * Унифицированная модель входного документа на этапе загрузки корпуса.
 */
data class RawDocument(
    val documentId: String,
    val filePath: String,
    val fileName: String,
    val sourceType: String,
    val title: String,
    val extension: String,
    val text: String? = null,
)
