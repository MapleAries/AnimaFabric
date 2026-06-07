package com.maple.llm

import kotlinx.serialization.json.Json

object JsonExtractor {
    private val json = Json { ignoreUnknownKeys = true }

    fun extractFirstObject(text: String): String? {
        return objectCandidates(text).firstOrNull { isValidJson(it) }
    }

    fun extractLastObjectContaining(text: String, requiredKeys: Collection<String>): String? {
        return objectCandidates(text)
            .filter { candidate -> requiredKeys.any { key -> candidate.contains("\"$key\"") } }
            .filter { isValidJson(it) }
            .lastOrNull()
    }

    private fun objectCandidates(text: String): List<String> {
        val candidates = mutableListOf<String>()
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false

        for (index in text.indices) {
            val char = text[index]

            if (escaped) {
                escaped = false
                continue
            }

            if (inString) {
                when (char) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                continue
            }

            when (char) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            candidates.add(text.substring(start, index + 1))
                            start = -1
                        }
                    }
                }
            }
        }

        return candidates
    }

    private fun isValidJson(candidate: String): Boolean {
        return try {
            json.parseToJsonElement(candidate)
            true
        } catch (_: Exception) {
            false
        }
    }
}
