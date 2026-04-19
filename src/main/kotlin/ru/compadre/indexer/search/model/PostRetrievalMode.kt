package ru.compadre.indexer.search.model

/**
 * Режим второго этапа retrieval после базового vector search.
 */
enum class PostRetrievalMode(val configValue: String) {
    NONE("none"),
    THRESHOLD_FILTER("threshold-filter"),
    HEURISTIC_FILTER("heuristic-filter"),
    HEURISTIC_RERANK("heuristic-rerank"),
    MODEL_RERANK("model-rerank"),
    ;

    companion object {
        /**
         * Преобразует строку из конфига или CLI в поддерживаемый режим.
         */
        fun fromValue(rawValue: String): PostRetrievalMode? =
            entries.firstOrNull { mode -> mode.configValue.equals(rawValue.trim(), ignoreCase = true) }
    }
}
