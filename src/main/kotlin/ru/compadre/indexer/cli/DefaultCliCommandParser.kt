package ru.compadre.indexer.cli

import ru.compadre.indexer.model.ChunkingStrategy
import ru.compadre.indexer.search.model.PostRetrievalMode
import ru.compadre.indexer.workflow.command.AskCommand
import ru.compadre.indexer.workflow.command.ChatCommand
import ru.compadre.indexer.workflow.command.CompareCommand
import ru.compadre.indexer.workflow.command.HelpCommand
import ru.compadre.indexer.workflow.command.IndexCommand
import ru.compadre.indexer.workflow.command.SearchCommand
import ru.compadre.indexer.workflow.command.SetPostModeCommand
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
            "chat" -> parseChatCommand(args)
            "index" -> parseIndexCommand(args)
            "compare" -> parseCompareCommand(args)
            "ask" -> parseAskCommand(args)
            "search" -> parseSearchCommand(args)
            "set" -> parseSetCommand(args)
            else -> throw IllegalArgumentException(
                "Неизвестная команда `$command`. Поддерживаемые команды: help, chat, index, compare, ask, search, set.",
            )
        }
    }

    private fun parseChatCommand(args: Array<String>): WorkflowCommand {
        val strategy = findOption(args, "--strategy")?.let { rawValue ->
            ChunkingStrategy.fromCli(rawValue)
                ?: throw IllegalArgumentException(
                    "Для `chat --strategy` поддерживаются только значения `fixed` и `structured`.",
                )
        } ?: throw IllegalArgumentException("Для команды `chat` требуется опция `--strategy`.")
        val topK = findIntOption(args, "--top")
            ?: throw IllegalArgumentException("Для команды `chat` требуется опция `--top`.")

        return ChatCommand(
            strategy = strategy,
            topK = topK,
        )
    }

    private fun parseIndexCommand(args: Array<String>): WorkflowCommand {
        val input = findOption(args, "--input")
        val strategy = findOption(args, "--strategy")?.let { rawValue ->
            ChunkingStrategy.fromCli(rawValue)
                ?: throw IllegalArgumentException(
                    "Для `--strategy` поддерживаются только значения `fixed` и `structured`.",
                )
        }
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

    private fun parseAskCommand(args: Array<String>): WorkflowCommand {
        val query = findOption(args, "--query")
            ?: throw IllegalArgumentException("Для команды `ask` требуется опция `--query`.")
        val mode = (findOption(args, "--mode") ?: DEFAULT_ASK_MODE).lowercase()
        val strategy = findOption(args, "--strategy")?.let { rawValue ->
            ChunkingStrategy.fromCli(rawValue)
                ?: throw IllegalArgumentException(
                    "Для `ask --strategy` поддерживаются только значения `fixed` и `structured`.",
                )
        }
        val topK = findIntOption(args, "--top")
        val postMode = findPostModeOption(args, "--post-mode")
        val showAllCandidates = hasFlag(args, "--show-all-candidates")

        if (mode !in SUPPORTED_ASK_MODES) {
            throw IllegalArgumentException("Для `ask --mode` поддерживаются только значения `plain` и `rag`.")
        }

        if (mode == "plain" && strategy != null) {
            throw IllegalArgumentException("Параметр `--strategy` можно использовать только вместе с `ask --mode rag`.")
        }

        if (mode == "plain" && topK != null) {
            throw IllegalArgumentException("Параметр `--top` можно использовать только вместе с `ask --mode rag`.")
        }

        return AskCommand(
            query = query,
            mode = mode,
            strategy = strategy,
            topK = topK,
            postMode = postMode,
            showAllCandidates = showAllCandidates,
        )
    }

    private fun parseSearchCommand(args: Array<String>): WorkflowCommand {
        val query = findOption(args, "--query")
            ?: throw IllegalArgumentException("Для команды `search` требуется опция `--query`.")
        val strategy = findOption(args, "--strategy")?.let { rawValue ->
            ChunkingStrategy.fromCli(rawValue)
                ?: throw IllegalArgumentException(
                    "Для `search --strategy` поддерживаются только значения `fixed` и `structured`.",
                )
        }
        val topK = findOption(args, "--top")?.toIntOrNull()
            ?: findOption(args, "--top")?.let {
                throw IllegalArgumentException("Для `search --top` требуется целое число.")
            }
        val postMode = findPostModeOption(args, "--post-mode")
        val showAllCandidates = hasFlag(args, "--show-all-candidates")

        return SearchCommand(
            query = query,
            strategy = strategy,
            topK = topK,
            postMode = postMode,
            showAllCandidates = showAllCandidates,
        )
    }

    private fun parseSetCommand(args: Array<String>): WorkflowCommand {
        val postModeValue = findOption(args, "--post-mode")
            ?: throw IllegalArgumentException("Для команды `set` требуется опция `--post-mode`.")

        return if (postModeValue.equals("config", ignoreCase = true)) {
            SetPostModeCommand(postMode = null)
        } else {
            val postMode = validatePostMode(postModeValue, optionName = "--post-mode")
            SetPostModeCommand(postMode = postMode)
        }
    }

    private fun findOption(args: Array<String>, optionName: String): String? {
        val index = args.indexOfFirst { it.equals(optionName, ignoreCase = true) }
        if (index == -1) {
            return null
        }

        return args.getOrNull(index + 1)
            ?.takeUnless { it.startsWith("--") }
            ?: throw IllegalArgumentException("Для опции `$optionName` требуется значение.")
    }

    private fun findIntOption(args: Array<String>, optionName: String): Int? {
        val value = findOption(args, optionName) ?: return null
        return value.toIntOrNull()
            ?: throw IllegalArgumentException("Для опции `$optionName` требуется целое число.")
    }

    private fun hasFlag(args: Array<String>, flagName: String): Boolean =
        args.any { argument -> argument.equals(flagName, ignoreCase = true) }

    private fun findPostModeOption(args: Array<String>, optionName: String): String? {
        val rawValue = findOption(args, optionName) ?: return null
        return validatePostMode(rawValue, optionName)
    }

    private fun validatePostMode(rawValue: String, optionName: String): String =
        PostRetrievalMode.fromValue(rawValue)?.configValue
            ?: throw IllegalArgumentException(
                "Для `$optionName` поддерживаются значения: ${SUPPORTED_POST_MODES.joinToString()}.",
            )

    private companion object {
        private const val DEFAULT_ASK_MODE = "plain"
        private val SUPPORTED_ASK_MODES = setOf("plain", "rag")
        private val SUPPORTED_POST_MODES = PostRetrievalMode.entries.map { mode -> mode.configValue }
    }
}
