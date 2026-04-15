package ru.compadre.indexer.cli

import ru.compadre.indexer.config.AppConfig
import ru.compadre.indexer.workflow.command.CompareCommand
import ru.compadre.indexer.workflow.command.HelpCommand
import ru.compadre.indexer.workflow.command.IndexCommand
import ru.compadre.indexer.workflow.command.WorkflowCommand

/**
 * Форматтер CLI-вывода для стартового этапа проекта.
 */
class DefaultCliOutputFormatter : CliOutputFormatter {
    override fun format(command: WorkflowCommand, config: AppConfig): String = when (command) {
        HelpCommand -> helpText(config)
        is IndexCommand -> indexText(command, config)
        is CompareCommand -> compareText(command, config)
    }

    private fun helpText(config: AppConfig): String = buildList {
        add("Local Document Indexer")
        add("")
        add("Доступные команды:")
        add("  index --input <dir> --strategy <fixed|structured>")
        add("  index --input <dir> --all-strategies")
        add("  compare --input <dir>")
        add("  help")
        add("")
        add("Текущий конфиг:")
        add("  inputDir = ${config.app.inputDir}")
        add("  outputDir = ${config.app.outputDir}")
        add("  ollama.baseUrl = ${config.ollama.baseUrl}")
        add("  ollama.embeddingModel = ${config.ollama.embeddingModel}")
        add("  chunking.fixedSize = ${config.chunking.fixedSize}")
        add("  chunking.overlap = ${config.chunking.overlap}")
        add("")
        add("Этап 1 готов: каркас CLI и конфиг подключены.")
    }.joinToString(separator = System.lineSeparator())

    private fun indexText(command: IndexCommand, config: AppConfig): String = buildList {
        add("Команда `index` пока работает как каркас первого этапа.")
        add("Следующий этап подключит реальное сканирование документов.")
        add("")
        add("Параметры запуска:")
        add("  inputDir = ${command.inputDir ?: config.app.inputDir}")
        add("  strategy = ${command.strategy ?: if (command.allStrategies) "all" else "<не указана>"}")
        add("  outputDir = ${config.app.outputDir}")
    }.joinToString(separator = System.lineSeparator())

    private fun compareText(command: CompareCommand, config: AppConfig): String = buildList {
        add("Команда `compare` пока работает как каркас первого этапа.")
        add("Следующий этап подключит реальные метрики chunking.")
        add("")
        add("Параметры запуска:")
        add("  inputDir = ${command.inputDir ?: config.app.inputDir}")
        add("  outputDir = ${config.app.outputDir}")
    }.joinToString(separator = System.lineSeparator())
}

