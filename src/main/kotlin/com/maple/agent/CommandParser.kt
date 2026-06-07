package com.maple.agent

object CommandParser {
    private val commandPattern = Regex("""!(\w+)\(([^)]*)\)""")
    private val typePattern = Regex("""\w+:\s*(string|number|boolean|int|float|double)""")
    private val nameOnlyPattern = Regex("""^[a-zA-Z_]+$""")

    fun extractExecutableCommands(content: String): List<String> {
        return commandPattern.findAll(content)
            .filter { match -> isExecutableArgumentList(match.groupValues[2].trim()) }
            .map { it.value }
            .toList()
    }

    fun parsePositionalParams(paramsStr: String): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        if (paramsStr.isBlank()) return params

        splitArguments(paramsStr).forEachIndexed { index, part ->
            params["param$index"] = parseValue(part.trim())
        }

        return params
    }

    private fun isExecutableArgumentList(args: String): Boolean {
        if (args.isEmpty()) return true
        if (typePattern.containsMatchIn(args)) return false

        val parts = splitArguments(args).map { it.trim() }
        val allNames = parts.all { it.isNotEmpty() && nameOnlyPattern.matches(it) }
        return !allNames
    }

    private fun splitArguments(paramsStr: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false

        for (char in paramsStr) {
            if (escaped) {
                current.append(char)
                escaped = false
                continue
            }

            when {
                char == '\\' && quote != null -> escaped = true
                quote != null && char == quote -> {
                    current.append(char)
                    quote = null
                }
                quote == null && (char == '"' || char == '\'') -> {
                    current.append(char)
                    quote = char
                }
                quote == null && char == ',' -> {
                    parts.add(current.toString())
                    current.clear()
                }
                else -> current.append(char)
            }
        }

        if (escaped) {
            current.append('\\')
        }
        parts.add(current.toString())

        return parts
    }

    private fun parseValue(part: String): Any {
        return when {
            part.equals("true", ignoreCase = true) -> true
            part.equals("false", ignoreCase = true) -> false
            part.toDoubleOrNull() != null -> part.toDouble()
            isQuoted(part) -> unquote(part)
            else -> part
        }
    }

    private fun isQuoted(value: String): Boolean {
        return value.length >= 2 &&
            ((value.first() == '"' && value.last() == '"') ||
                (value.first() == '\'' && value.last() == '\''))
    }

    private fun unquote(value: String): String {
        val body = value.substring(1, value.length - 1)
        val result = StringBuilder()
        var escaped = false

        for (char in body) {
            if (escaped) {
                result.append(char)
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else {
                result.append(char)
            }
        }

        if (escaped) {
            result.append('\\')
        }

        return result.toString()
    }
}
