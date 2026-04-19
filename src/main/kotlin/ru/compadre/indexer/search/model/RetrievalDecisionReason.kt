package ru.compadre.indexer.search.model

/**
 * Причина решения post-retrieval этапа для конкретного кандидата.
 */
sealed interface RetrievalDecisionReason {
    val mode: PostRetrievalMode
    val label: String
}

/**
 * Причины решений для threshold-filter.
 */
enum class ThresholdFilterReason(
    override val label: String,
) : RetrievalDecisionReason {
    BELOW_MIN_SIMILARITY("ниже порога similarity"),
    TRIMMED_BY_FINAL_TOP_K("не попал в финальный top-K"),
    ;

    override val mode: PostRetrievalMode = PostRetrievalMode.THRESHOLD_FILTER
}

/**
 * Причины решений для heuristic-filter.
 */
enum class HeuristicFilterReason(
    override val label: String,
) : RetrievalDecisionReason {
    LOW_KEYWORD_OVERLAP("недостаточное пересечение по ключевым словам"),
    NO_MEANINGFUL_MATCH("не найдено значимых совпадений"),
    DUPLICATE_OF_STRONGER_CANDIDATE("дубликат более сильного кандидата"),
    TRIMMED_BY_FINAL_TOP_K("не попал в финальный top-K"),
    ;

    override val mode: PostRetrievalMode = PostRetrievalMode.HEURISTIC_FILTER
}

/**
 * Причины решений для heuristic-rerank.
 */
enum class HeuristicRerankReason(
    override val label: String,
) : RetrievalDecisionReason {
    BOOSTED_BY_EXACT_MATCH("поднят из-за точного совпадения"),
    BOOSTED_BY_TITLE_MATCH("поднят из-за совпадения с заголовком"),
    DEMOTED_BY_WEAK_OVERLAP("понижен из-за слабого lexical overlap"),
    ;

    override val mode: PostRetrievalMode = PostRetrievalMode.HEURISTIC_RERANK
}

/**
 * Причины решений для model-rerank.
 */
enum class ModelRerankReason(
    override val label: String,
) : RetrievalDecisionReason {
    RANKED_BY_MODEL_SCORE("переупорядочен по model score"),
    BELOW_MODEL_SCORE_THRESHOLD("ниже порога model score"),
    ;

    override val mode: PostRetrievalMode = PostRetrievalMode.MODEL_RERANK
}
