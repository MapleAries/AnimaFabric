package com.maple.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class MCMindConfig(
    val apiUrl: String = "https://api.openai.com/v1/chat/completions",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val maxTokens: Int = 1024,
    val timeout: Long = 30,
    val maxHistoryTurns: Int = 10
) {
    companion object {
        private val CONFIG_PATH: Path = FabricLoader.getInstance().configDir.resolve("mc-mind.json")
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

        fun load(): MCMindConfig {
            return try {
                if (Files.exists(CONFIG_PATH)) {
                    json.decodeFromString<MCMindConfig>(Files.readString(CONFIG_PATH))
                } else {
                    val default = MCMindConfig()
                    default.save()
                    default
                }
            } catch (e: Exception) {
                MCMindConfig()
            }
        }
    }

    fun save() {
        try {
            Files.createDirectories(CONFIG_PATH.parent)
            Files.writeString(CONFIG_PATH, json.encodeToString(this))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
