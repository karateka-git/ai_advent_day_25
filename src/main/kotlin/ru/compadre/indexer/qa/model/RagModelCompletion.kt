package ru.compadre.indexer.qa.model

import kotlinx.serialization.Serializable

/**
 * Structured response expected from the LLM for RAG generation.
 */
@Serializable
data class RagModelCompletion(
    val answer: String? = null,
    val quotes: List<RagModelQuote> = emptyList(),
)

/**
 * Quote item returned by the LLM.
 */
@Serializable
data class RagModelQuote(
    val chunkId: String? = null,
    val quote: String? = null,
    val text: String? = null,
)
