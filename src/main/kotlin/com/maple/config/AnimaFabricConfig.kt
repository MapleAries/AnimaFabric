package com.maple.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class AnimaFabricConfig(
    val apiUrl: String = "https://api.deepseek.com/v1/chat/completions",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    val maxTokens: Int = 2048,
    val timeout: Long = 120,
    val maxHistoryTurns: Int = 10,
    val maxRetries: Int = 3
) {
    companion object {
        private val CONFIG_PATH: Path = FabricLoader.getInstance().configDir.resolve("anima-fabric.json")
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

        fun load(): AnimaFabricConfig {
            return try {
                if (Files.exists(CONFIG_PATH)) {
                    json.decodeFromString<AnimaFabricConfig>(Files.readString(CONFIG_PATH))
                } else {
                    val default = AnimaFabricConfig()
                    default.save()
                    default
                }
            } catch (e: Exception) {
                AnimaFabricConfig()
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
