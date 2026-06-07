package com.maple.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class AnimaFabricConfig(
    val apiUrl: String = "https://api.deepseek.com/chat/completions",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    val maxTokens: Int = 2048,
    val timeout: Long = 300,
    val maxHistoryTurns: Int = 10,
    val maxRetries: Int = 3,
    val requiredPermissionLevel: Int = 2
) {
    companion object {
        const val API_KEY_ENV = "ANIMA_FABRIC_API_KEY"
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

    fun effectiveApiKey(): String {
        return System.getenv(API_KEY_ENV)?.takeIf { it.isNotBlank() } ?: apiKey
    }

    fun isApiKeyFromEnvironment(): Boolean {
        return !System.getenv(API_KEY_ENV).isNullOrBlank()
    }
}
