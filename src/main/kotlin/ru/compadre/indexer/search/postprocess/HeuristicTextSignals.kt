package ru.compadre.indexer.search.postprocess

import kotlin.math.max

/**
 * Нормализованные текстовые сигналы для rule-based post-retrieval обработки.
 */
data class HeuristicTextSignals(
    val queryTerms: Set<String>,
    val textTerms: Set<String>,
    val titleTerms: Set<String>,
    val sectionTerms: Set<String>,
) {
    /**
     * Количество общих значимых слов между вопросом и текстом чанка.
     */
    val keywordOverlapCount: Int
        get() = queryTerms.intersect(textTerms).size

    /**
     * Есть ли совпадение по значимым словам хотя бы в одном из текстовых полей чанка.
     */
    val hasMeaningfulMatch: Boolean
        get() = queryTerms.any { term ->
            term in textTerms || term in titleTerms || term in sectionTerms
        }

    /**
     * Есть ли совпадение по значимым словам в самом тексте чанка.
     */
    val hasTextMatch: Boolean
        get() = queryTerms.any { term -> term in textTerms }

    /**
     * Есть ли совпадение по значимым словам в заголовке чанка.
     */
    val hasTitleMatch: Boolean
        get() = queryTerms.any { term -> term in titleTerms }

    /**
     * Есть ли совпадение по значимым словам в секции чанка.
     */
    val hasSectionMatch: Boolean
        get() = queryTerms.any { term -> term in sectionTerms }

    /**
     * Относительный overlap между вопросом и текстом чанка.
     */
    val overlapRatio: Double
        get() = keywordOverlapCount.toDouble() / max(queryTerms.size, 1)
}

/**
 * Вспомогательные функции для расчёта rule-based текстовых сигналов.
 */
object HeuristicTextSignalExtractor {
    private val tokenPattern = Regex("[\\p{L}\\p{Nd}]+")
    private val russianStopWords = setOf(
        "а", "без", "бы", "в", "во", "вот", "все", "всё", "вы", "где", "да", "для", "до", "его", "ее", "её",
        "если", "есть", "же", "за", "здесь", "и", "из", "или", "им", "их", "к", "как", "ко", "кто", "ли", "мне",
        "мы", "на", "над", "не", "него", "нее", "неё", "нет", "но", "о", "об", "однако", "он", "она", "они",
        "оно", "от", "по", "под", "при", "с", "со", "так", "там", "то", "тоже", "только", "у", "уже", "что",
        "чтобы", "это", "эта", "эти", "этого", "этой", "этот", "я",
    )

    /**
     * Строит набор текстовых сигналов для вопроса и кандидата retrieval.
     */
    fun extract(
        query: String,
        text: String,
        title: String,
        section: String,
    ): HeuristicTextSignals =
        HeuristicTextSignals(
            queryTerms = normalizeTerms(query),
            textTerms = normalizeTerms(text),
            titleTerms = normalizeTerms(title),
            sectionTerms = normalizeTerms(section),
        )

    /**
     * Считает приближенную Jaccard-похожесть по значимым словам.
     */
    fun jaccardSimilarity(left: Set<String>, right: Set<String>): Double {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0
        }

        val intersectionSize = left.intersect(right).size.toDouble()
        val unionSize = left.union(right).size.toDouble()
        return if (unionSize == 0.0) 0.0 else intersectionSize / unionSize
    }

    private fun normalizeTerms(text: String): Set<String> =
        tokenPattern.findAll(text.lowercase())
            .map { match -> match.value }
            .filter { token -> token.length >= MIN_TOKEN_LENGTH }
            .filterNot { token -> token in russianStopWords }
            .toSet()

    private const val MIN_TOKEN_LENGTH = 3
}
