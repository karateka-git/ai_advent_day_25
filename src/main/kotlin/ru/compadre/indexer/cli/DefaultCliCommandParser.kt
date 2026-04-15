package ru.compadre.indexer.cli

import ru.compadre.indexer.workflow.command.CompareCommand
import ru.compadre.indexer.workflow.command.HelpCommand
import ru.compadre.indexer.workflow.command.IndexCommand
import ru.compadre.indexer.workflow.command.WorkflowCommand

/**
 * Минималистичный парсер CLI-команд для учебного MVP.
 */
class DefaultCliCommandParser : CliCommandParser {
    override fun parse(args: Array<String>): WorkflowCommand {
        val command = args.firstOrNull()
            ?.trim()
            ?.trimStart('\uFEFF')
            ?.lowercase()
            ?: return HelpCommand

        return when (command) {
            "help" -> HelpCommand
            "index" -> parseIndexCommand(args)
            "compare" -> parseCompareCommand(args)
            else -> throw IllegalArgumentException(
                "Неизвестная команда `$command`. Поддерживаемые команды: help, index, compare.",
            )
        }
    }

    private fun parseIndexCommand(args: Array<String>): WorkflowCommand {
        val input = findOption(args, "--input")
        val strategy = findOption(args, "--strategy")
        val allStrategies = args.any { it.equals("--all-strategies", ignoreCase = true) }

        if (strategy != null && allStrategies) {
            throw IllegalArgumentException("Нельзя одновременно указывать `--strategy` и `--all-strategies`.")
        }

        return IndexCommand(
            inputDir = input,
            strategy = strategy,
            allStrategies = allStrategies,
        )
    }

    private fun parseCompareCommand(args: Array<String>): WorkflowCommand =
        CompareCommand(
            inputDir = findOption(args, "--input"),
        )

    private fun findOption(args: Array<String>, optionName: String): String? {
        val index = args.indexOfFirst { it.equals(optionName, ignoreCase = true) }
        if (index == -1) {
            return null
        }

        return args.getOrNull(index + 1)
            ?.takeUnless { it.startsWith("--") }
            ?: throw IllegalArgumentException("Для опции `$optionName` требуется значение.")
    }
}

