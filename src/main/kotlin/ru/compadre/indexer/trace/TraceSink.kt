package ru.compadre.indexer.trace

fun interface TraceSink {
    fun emit(record: TraceRecord)
}
