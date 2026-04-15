package ru.compadre.indexer.cli

import ru.compadre.indexer.config.AppConfig
import ru.compadre.indexer.workflow.command.WorkflowCommand

/**
 * Контракт пользовательского CLI-вывода.
 */
interface CliOutputFormatter {
    /**
     * Форматирует команду и активную конфигурацию в понятный текст для консоли.
     */
    fun format(command: WorkflowCommand, config: AppConfig): String
}

