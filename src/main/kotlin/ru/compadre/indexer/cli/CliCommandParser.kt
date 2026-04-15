package ru.compadre.indexer.cli

import ru.compadre.indexer.workflow.command.WorkflowCommand

/**
 * Контракт разбора CLI-ввода в команду приложения.
 */
interface CliCommandParser {
    /**
     * Разбирает аргументы запуска в внутреннюю команду.
     */
    fun parse(args: Array<String>): WorkflowCommand
}

