package com.example.openrouteragent

/*
 * Slash commands typed at the start of the main composer. A command is handled by the app and is never sent to the
 * main chat. Only the side conversation's commands exist so far; more (for example one per skill) can be added to
 * [SlashCommands.all] and handled where [SlashCommands.parse] is called.
 */

/** A command typed as "/name" or "/alias", optionally followed by text. */
internal data class SlashCommand(val name: String, val aliases: List<String> = emptyList(), val description: String) {
    val names: List<String> get() = listOf(name) + aliases
}

/** A typed command and the text after it (trimmed, possibly empty). */
internal data class ParsedSlashCommand(val command: SlashCommand, val argument: String)

internal object SlashCommands {
    /** "/side" or "/btw" opens the side conversation; text after it is sent there as a side message. */
    val Side = SlashCommand("side", aliases = listOf("btw"),
        description = "Open the side conversation, or ask it something: /btw <message>")

    val all: List<SlashCommand> = listOf(Side)

    /**
     * The command at the start of [input], or null when the input isn't a known command. The name must be followed
     * by whitespace or the end, so "/sideways" or "/btwx" stay ordinary messages. Names ignore case.
     */
    fun parse(input: String): ParsedSlashCommand? {
        val text = input.trimStart()
        if (!text.startsWith("/")) return null
        val end = text.indexOfFirst(Char::isWhitespace).let { if (it < 0) text.length else it }
        val name = text.substring(1, end).lowercase()
        if (name.isEmpty()) return null
        val command = all.firstOrNull { name in it.names } ?: return null
        return ParsedSlashCommand(command, text.substring(end).trim())
    }
}
