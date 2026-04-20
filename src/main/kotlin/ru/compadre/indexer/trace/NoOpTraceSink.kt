package ru.compadre.indexer.trace

object NoOpTraceSink : TraceSink {
    override fun emit(record: TraceRecord) = Unit
}
