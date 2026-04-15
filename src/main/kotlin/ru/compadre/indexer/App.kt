package ru.compadre.indexer

import ru.compadre.indexer.cli.CliCommandParser
import ru.compadre.indexer.cli.CliOutputFormatter
import ru.compadre.indexer.cli.DefaultCliCommandParser
import ru.compadre.indexer.cli.DefaultCliOutputFormatter
import ru.compadre.indexer.config.AppConfigLoader
import ru.compadre.indexer.workflow.command.HelpCommand
import ru.compadre.indexer.workflow.command.WorkflowCommand
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * Главная точка входа учебного индексатора документов.
 */
fun main(args: Array<String>) {
    configureUtf8Console()

    val config = AppConfigLoader.load()
    val parser: CliCommandParser = DefaultCliCommandParser()
    val formatter: CliOutputFormatter = DefaultCliOutputFormatter()

    if (args.isEmpty()) {
        runInteractiveShell(
            parser = parser,
            formatter = formatter,
            config = config,
        )
        return
    }

    val command = try {
        parser.parse(args)
    } catch (error: IllegalArgumentException) {
        println(error.message ?: "Не удалось разобрать CLI-команду.")
        return
    }

    println(formatter.format(command, config))
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

private fun runInteractiveShell(
    parser: CliCommandParser,
    formatter: CliOutputFormatter,
    config: ru.compadre.indexer.config.AppConfig,
) {
    println("Local Document Indexer")
    println("Интерактивный режим. Введите `help`, чтобы увидеть доступные команды, или `exit`, чтобы завершить сессию.")

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
                println(formatter.format(HelpCommand, config))
                continue
            }
        }

        executeInteractiveCommand(
            rawInput = rawInput,
            parser = parser,
            formatter = formatter,
            config = config,
        )
    }
}

private fun executeInteractiveCommand(
    rawInput: String,
    parser: CliCommandParser,
    formatter: CliOutputFormatter,
    config: ru.compadre.indexer.config.AppConfig,
) {
    val command = try {
        parser.parse(rawInput.split(Regex("\\s+")).toTypedArray())
    } catch (error: IllegalArgumentException) {
        println(error.message ?: "Не удалось разобрать CLI-команду.")
        return
    }

    println(formatter.format(command, config))
}
