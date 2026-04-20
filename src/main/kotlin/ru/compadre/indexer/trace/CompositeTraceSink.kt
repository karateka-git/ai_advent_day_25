package ru.compadre.indexer.trace

class CompositeTraceSink(
    private val sinks: List<TraceSink>,
) : TraceSink {
    override fun emit(record: TraceRecord) {
        sinks.forEach { sink -> sink.emit(record) }
    }
}
