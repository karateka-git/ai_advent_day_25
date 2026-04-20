package ru.compadre.indexer.chat.orchestration

import ru.compadre.indexer.chat.model.TaskState
import ru.compadre.indexer.chat.orchestration.model.GroundedChatAnswerRequest
import ru.compadre.indexer.qa.RagQuestionAnsweringService
import ru.compadre.indexer.qa.model.RagAnswer
import ru.compadre.indexer.qa.model.RagQuote
import ru.compadre.indexer.qa.model.RagSource

/**
 * Адаптер между chat orchestration и текущим single-turn RAG-сервисом.
 */
class RagGroundedChatAnswerService(
    private val ragQuestionAnsweringService: RagQuestionAnsweringService,
) : GroundedChatAnswerService {
    override suspend fun answer(request: GroundedChatAnswerRequest): RagAnswer =
        filterAnswerToActiveDocument(
            ragAnswer = ragQuestionAnsweringService.answer(
                requestId = request.requestId,
                question = request.retrievalQuery,
                databasePath = request.databasePath,
                strategy = request.strategy,
                initialTopK = request.initialTopK,
                finalTopK = request.finalTopK,
                config = request.config,
            ),
            taskState = request.taskState,
        )
}

internal fun filterAnswerToActiveDocument(
    ragAnswer: RagAnswer,
    taskState: TaskState,
): RagAnswer {
    val activeDocumentTitle = taskState.activeDocumentTitle() ?: return ragAnswer
    val filteredSources = ragAnswer.sources.filter { source ->
        source.matchesDocumentTitle(activeDocumentTitle)
    }
    if (filteredSources.isEmpty()) {
        return ragAnswer
    }

    val allowedChunkIds = filteredSources.map(RagSource::chunkId).toSet()
    val filteredQuotes = ragAnswer.quotes.filter { quote -> quote.chunkId in allowedChunkIds }
    return ragAnswer.copy(
        sources = filteredSources,
        quotes = filteredQuotes,
    )
}

private fun TaskState.activeDocumentTitle(): String? =
    constraints.firstNotNullOfOrNull(::extractDocumentTitle)
        ?: goal?.let(::extractDocumentTitle)

private fun extractDocumentTitle(text: String): String? =
    DOCUMENT_TITLE_REGEX.find(text)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotBlank)

private fun RagSource.matchesDocumentTitle(documentTitle: String): Boolean {
    val normalizedTitle = documentTitle.trim().lowercase()
    val titleVariants = setOf(
        normalizedTitle,
        transliterateRussian(normalizedTitle),
    )
    val normalizedSource = source.lowercase()
    val normalizedSection = section.lowercase()
    return titleVariants.any { candidate ->
        normalizedSource.contains("/$candidate.")
            || normalizedSource.contains("\\$candidate.")
            || normalizedSource.endsWith("$candidate.txt")
            || normalizedSection == candidate
            || chunkId.lowercase().contains("$candidate#")
    }
}

private val DOCUMENT_TITLE_REGEX = Regex("""текст\s+[«"]([^»"]+)[»"]""", RegexOption.IGNORE_CASE)

private fun transliterateRussian(text: String): String = buildString {
    text.forEach { char ->
        append(
            when (char) {
                'а' -> "a"
                'б' -> "b"
                'в' -> "v"
                'г' -> "g"
                'д' -> "d"
                'е' -> "e"
                'ё' -> "e"
                'ж' -> "zh"
                'з' -> "z"
                'и' -> "i"
                'й' -> "i"
                'к' -> "k"
                'л' -> "l"
                'м' -> "m"
                'н' -> "n"
                'о' -> "o"
                'п' -> "p"
                'р' -> "r"
                'с' -> "s"
                'т' -> "t"
                'у' -> "u"
                'ф' -> "f"
                'х' -> "h"
                'ц' -> "ts"
                'ч' -> "ch"
                'ш' -> "sh"
                'щ' -> "sch"
                'ъ' -> ""
                'ы' -> "y"
                'ь' -> ""
                'э' -> "e"
                'ю' -> "yu"
                'я' -> "ya"
                ' ' -> "-"
                else -> char.toString()
            },
        )
    }
}
