package ru.compadre.indexer.loader

import ru.compadre.indexer.model.SourceType
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * Определяет тип поддерживаемого документа по имени и расширению файла.
 */
object SourceTypeDetector {
    private val codeExtensions = setOf(
        "kt",
        "java",
        "py",
        "js",
        "ts",
        "json",
        "yaml",
        "yml",
    )

    fun detect(path: Path): SourceType? {
        val fileName = path.name
        val extension = path.extension.lowercase()

        if (isReadme(fileName, extension)) {
            return SourceType.README
        }

        return when (extension) {
            "md" -> SourceType.MARKDOWN
            "txt" -> SourceType.TEXT
            "pdf" -> SourceType.PDF
            in codeExtensions -> SourceType.CODE
            else -> null
        }
    }

    private fun isReadme(fileName: String, extension: String): Boolean {
        val normalizedName = fileName.lowercase()
        return normalizedName == "readme" || normalizedName == "readme.$extension"
    }
}
