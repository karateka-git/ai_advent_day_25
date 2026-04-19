package ru.compadre.indexer.search.postprocess

import ru.compadre.indexer.config.AppConfig

/**
 * Общий расчёт heuristic score для rule-based post-retrieval режимов.
 */
object HeuristicScoreCalculator {
    /**
     * Считает итоговый heuristic score на основе cosine и текстовых сигналов.
     */
    fun calculate(
        cosineScore: Double,
        signals: HeuristicTextSignals,
        config: AppConfig,
    ): Double {
        val heuristicConfig = config.search.heuristic
        var score =
            cosineScore * heuristicConfig.cosineWeight +
                signals.overlapRatio * heuristicConfig.keywordOverlapWeight

        if (signals.hasTextMatch) {
            score += heuristicConfig.exactMatchBonus
        }
        if (signals.hasTitleMatch) {
            score += heuristicConfig.titleMatchBonus
        }
        if (signals.hasSectionMatch) {
            score += heuristicConfig.sectionMatchBonus
        }

        return score
    }
}

