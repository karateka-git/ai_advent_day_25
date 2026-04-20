package ru.compadre.indexer

import kotlinx.coroutines.runBlocking
import ru.compadre.indexer.chat.memory.TaskStateUpdateService
import ru.compadre.indexer.chat.orchestration.LlmAnswerRewriteService
import ru.compadre.indexer.chat.orchestration.ChatSessionCoordinator
import ru.compadre.indexer.chat.orchestration.RagGroundedChatAnswerService
import ru.compadre.indexer.chat.retrieval.RetrievalQueryBuilder
import ru.compadre.indexer.chat.storage.InMemoryChatSessionStore
import ru.compadre.indexer.cli.CliCommandParser
import ru.compadre.indexer.cli.CliOutputFormatter
import ru.compadre.indexer.cli.DefaultCliCommandParser
import ru.compadre.indexer.cli.DefaultCliOutputFormatter
import ru.compadre.indexer.config.AppConfig
import ru.compadre.indexer.config.AppConfigLoader
import ru.compadre.indexer.config.withPostProcessingMode
import ru.compadre.indexer.qa.RagQuestionAnsweringService
import ru.compadre.indexer.search.BruteForceSearchEngine
import ru.compadre.indexer.search.RetrievalPipelineService
import ru.compadre.indexer.trace.TraceSink
import ru.compadre.indexer.trace.TraceSinkFactory
import ru.compadre.indexer.workflow.command.AskCommand
import ru.compadre.indexer.workflow.command.ChatCommand
import ru.compadre.indexer.workflow.command.HelpCommand
import ru.compadre.indexer.workflow.command.SearchCommand
import ru.compadre.indexer.workflow.command.SetPostModeCommand
import ru.compadre.indexer.workflow.command.WorkflowCommand
import ru.compadre.indexer.workflow.result.ChatHistoryViewResult
import ru.compadre.indexer.workflow.result.ChatMemoryViewResult
import ru.compadre.indexer.workflow.result.ChatSessionStartedResult
import ru.compadre.indexer.workflow.result.ChatTurnCliResult
import ru.compadre.indexer.workflow.result.PostModeUpdateResult
import ru.compadre.indexer.workflow.service.DefaultWorkflowCommandHandler
import ru.compadre.indexer.workflow.service.WorkflowCommandHandler
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Главная точка входа учебного индексатора документов.
 */
fun main(args: Array<String>) = runBlocking {
    configureUtf8Console()
    configureLogging()

    val baseConfig = AppConfigLoader.load()
    val parser: CliCommandParser = DefaultCliCommandParser()
    val formatter: CliOutputFormatter = DefaultCliOutputFormatter()
    val traceSink = TraceSinkFactory.create(baseConfig.app.outputDir)
    val commandHandler: WorkflowCommandHandler = DefaultWorkflowCommandHandler(traceSink = traceSink)

    if (args.isEmpty()) {
        runInteractiveShell(
            parser = parser,
            formatter = formatter,
            baseConfig = baseConfig,
            commandHandler = commandHandler,
        )
        return@runBlocking
    }

    val command = try {
        parser.parse(args)
    } catch (error: IllegalArgumentException) {
        println(error.message ?: "Не удалось разобрать CLI-команду.")
        return@runBlocking
    }

    if (command is ChatCommand) {
        runChatSession(
            formatter = formatter,
            config = baseConfig,
            command = command,
            chatSessionCoordinator = createChatSessionCoordinator(traceSink),
        )
        return@runBlocking
    }

    val result = when (command) {
        is SetPostModeCommand -> PostModeUpdateResult(
            effectivePostMode = command.postMode ?: baseConfig.search.postProcessingMode,
            resetToConfig = command.postMode == null,
        )

        else -> executeCommandWithFeedback(command, baseConfig, commandHandler)
    }

    println(formatter.format(result))
}

private fun configureLogging() {
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    System.setProperty("org.slf4j.simpleLogger.log.org.apache.pdfbox", "error")
}

private fun configureUtf8Console() {
    System.setOut(
        PrintStream(
            FileOutputStream(FileDescriptor.out),
            true,
            StandardCharsets.UTF_8,
        ),
    )
    System.setErr(
        PrintStream(
            FileOutputStream(FileDescriptor.err),
            true,
            StandardCharsets.UTF_8,
        ),
    )
}

private suspend fun runInteractiveShell(
    parser: CliCommandParser,
    formatter: CliOutputFormatter,
    baseConfig: AppConfig,
    commandHandler: WorkflowCommandHandler,
) {
    println("Local Document Indexer")
    println("Интерактивный режим. Введите `help`, чтобы увидеть доступные команды, или `exit`, чтобы завершить сессию.")
    var sessionPostModeOverride: String? = null

    while (true) {
        print("> ")
        val rawInput = readlnOrNull()
            ?.trim()
            ?.trimStart('\uFEFF')
            ?: run {
                println("CLI-сессия завершена.")
                return
            }

        if (rawInput.isBlank()) {
            continue
        }

        when (rawInput.lowercase()) {
            "exit", "quit" -> {
                println("CLI-сессия завершена.")
                return
            }

            "help" -> {
                val helpConfig = applySessionPostModeOverride(baseConfig, sessionPostModeOverride)
                println(formatter.format(commandHandler.handle(HelpCommand, helpConfig)))
                continue
            }
        }

        sessionPostModeOverride = executeInteractiveCommand(
            rawInput = rawInput,
            parser = parser,
            formatter = formatter,
            baseConfig = baseConfig,
            sessionPostModeOverride = sessionPostModeOverride,
            commandHandler = commandHandler,
        ) ?: sessionPostModeOverride
    }
}

private suspend fun executeInteractiveCommand(
    rawInput: String,
    parser: CliCommandParser,
    formatter: CliOutputFormatter,
    baseConfig: AppConfig,
    sessionPostModeOverride: String?,
    commandHandler: WorkflowCommandHandler,
): String? {
    val command = try {
        parser.parse(tokenizeCliInput(rawInput))
    } catch (error: IllegalArgumentException) {
        println(error.message ?: "Не удалось разобрать CLI-команду.")
        return null
    }

    return when (command) {
        is ChatCommand -> {
            val effectiveConfig = applySessionPostModeOverride(baseConfig, sessionPostModeOverride)
            runChatSession(
                formatter = formatter,
                config = effectiveConfig,
                command = command,
                chatSessionCoordinator = createChatSessionCoordinator(
                    TraceSinkFactory.create(baseConfig.app.outputDir),
                ),
            )
            sessionPostModeOverride
        }

        is SetPostModeCommand -> {
            val updatedOverride = command.postMode
            val effectivePostMode = updatedOverride ?: baseConfig.search.postProcessingMode
            println(
                formatter.format(
                    PostModeUpdateResult(
                        effectivePostMode = effectivePostMode,
                        resetToConfig = updatedOverride == null,
                    ),
                ),
            )
            updatedOverride
        }

        else -> {
            val effectiveConfig = applySessionPostModeOverride(baseConfig, sessionPostModeOverride)
            println(formatter.format(executeCommandWithFeedback(command, effectiveConfig, commandHandler)))
            sessionPostModeOverride
        }
    }
}

private suspend fun runChatSession(
    formatter: CliOutputFormatter,
    config: AppConfig,
    command: ChatCommand,
    chatSessionCoordinator: ChatSessionCoordinator,
) {
    var activeSession = chatSessionCoordinator.startSession()
    println(
        formatter.format(
            ChatSessionStartedResult(
                strategyLabel = command.strategy.id,
                topK = command.topK,
            ),
        ),
    )

    while (true) {
        print("chat> ")
        val rawInput = readlnOrNull()
            ?.trim()
            ?.trimStart('\uFEFF')
            ?: run {
                println("Chat-сессия завершена.")
                return
            }

        if (rawInput.isBlank()) {
            continue
        }

        when (rawInput.lowercase()) {
            ":exit" -> {
                println("Chat-сессия завершена.")
                return
            }

            ":memory" -> {
                println(formatter.format(ChatMemoryViewResult(activeSession.taskState)))
                continue
            }

            ":history" -> {
                println(
                    formatter.format(
                        ChatHistoryViewResult(
                            messages = activeSession.messages.takeLast(CHAT_HISTORY_LIMIT),
                        ),
                    ),
                )
                continue
            }
        }

        val loadingIndicator = LoadingIndicator()
        val turnResult = try {
            loadingIndicator.start()
            chatSessionCoordinator.handleUserTurn(
                sessionId = activeSession.sessionId,
                userMessage = rawInput,
                config = config,
                strategy = command.strategy,
                topK = command.topK,
            )
        } finally {
            loadingIndicator.stop()
        }

        activeSession = turnResult.session
        println(
            formatter.format(
                ChatTurnCliResult(
                    userMessage = rawInput,
                    retrievalQuery = turnResult.retrievalQuery,
                    ragAnswer = turnResult.ragAnswer,
                ),
            ),
        )
    }
}

private suspend fun executeCommandWithFeedback(
    command: WorkflowCommand,
    config: AppConfig,
    commandHandler: WorkflowCommandHandler,
) = if (command is AskCommand) {
    val loadingIndicator = LoadingIndicator()
    try {
        loadingIndicator.start()
        commandHandler.handle(command, applyCommandPostModeOverride(config, command))
    } finally {
        loadingIndicator.stop()
    }
} else if (command is SearchCommand) {
    commandHandler.handle(command, applyCommandPostModeOverride(config, command))
} else {
    commandHandler.handle(command, config)
}

private fun applySessionPostModeOverride(
    baseConfig: AppConfig,
    sessionPostModeOverride: String?,
): AppConfig =
    sessionPostModeOverride?.let(baseConfig::withPostProcessingMode) ?: baseConfig

private fun applyCommandPostModeOverride(
    config: AppConfig,
    command: WorkflowCommand,
): AppConfig =
    when (command) {
        is AskCommand -> command.postMode?.let(config::withPostProcessingMode) ?: config
        is SearchCommand -> command.postMode?.let(config::withPostProcessingMode) ?: config
        else -> config
    }

private fun tokenizeCliInput(rawInput: String): Array<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var quoteChar: Char? = null

    rawInput.forEach { char ->
        when {
            quoteChar == null && char.isWhitespace() -> {
                if (current.isNotEmpty()) {
                    tokens += current.toString()
                    current.clear()
                }
            }

            char == '"' || char == '\'' -> {
                if (quoteChar == null) {
                    quoteChar = char
                } else if (quoteChar == char) {
                    quoteChar = null
                } else {
                    current.append(char)
                }
            }

            else -> current.append(char)
        }
    }

    if (current.isNotEmpty()) {
        tokens += current.toString()
    }

    return tokens.toTypedArray()
}

private fun createChatSessionCoordinator(traceSink: TraceSink): ChatSessionCoordinator {
    val retrievalPipelineService = RetrievalPipelineService(
        searchEngine = BruteForceSearchEngine(),
        traceSink = traceSink,
    )
    val ragQuestionAnsweringService = RagQuestionAnsweringService(
        retrievalPipelineService = retrievalPipelineService,
        traceSink = traceSink,
    )

    return ChatSessionCoordinator(
        chatSessionStore = InMemoryChatSessionStore(),
        taskStateUpdateService = TaskStateUpdateService(traceSink = traceSink),
        retrievalQueryBuilder = RetrievalQueryBuilder(),
        groundedChatAnswerService = RagGroundedChatAnswerService(ragQuestionAnsweringService),
        answerRewriteService = LlmAnswerRewriteService(),
        traceSink = traceSink,
    )
}

private const val CHAT_HISTORY_LIMIT = 10

private class LoadingIndicator {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        running.set(true)
        thread = Thread {
            var step = 0
            while (running.get()) {
                val dots = ".".repeat(step % 4)
                val padding = " ".repeat(3 - dots.length)
                print("\rОтвет модели$dots$padding")
                Thread.sleep(350)
                step++
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.join(500)
        print("\r${" ".repeat(40)}\r")
    }
}
