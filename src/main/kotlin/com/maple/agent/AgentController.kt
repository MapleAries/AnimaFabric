package com.maple.agent

import com.maple.config.AnimaFabricConfig
import com.maple.entity.FakePlayerManager
import com.maple.llm.ChatMessage
import com.maple.llm.LLMClient
import kotlinx.coroutines.*
import net.minecraft.server.MinecraftServer
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * 主控制器：接收指令 → 判断类型 → 分发到 TaskPlanner 处理。
 * 通过 Carpet 命令控制假人。
 */
class AgentController(initialConfig: AnimaFabricConfig, private val server: MinecraftServer) {

    @Volatile
    private var config = initialConfig

    @Volatile
    private var llmClient = LLMClient(initialConfig)

    private val memories = ConcurrentHashMap<String, ConversationMemory>()
    private val scopes = ConcurrentHashMap<String, CoroutineScope>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val chatJobs = ConcurrentHashMap<String, Job>()

    @Volatile
    private var lastActiveBotName: String? = null

    /**
     * 获取所有可用的假人名称。
     */
    fun getAvailableBots(): List<String> {
        return FakePlayerManager.listNames(server)
    }

    /**
     * 向指定假人发送指令。
     * @param name 假人名称
     * @param command 指令内容
     * @param sender 指令发送者（真实玩家），用于获取准星目标等上下文
     * @param onComplete 完成回调
     */
    fun sendCommand(name: String, command: String, sender: net.minecraft.server.level.ServerPlayer? = null, onComplete: (String) -> Unit) {
        val bot = FakePlayerManager.getBot(server, name)
        if (bot == null) {
            onComplete("假人 '$name' 不存在。请先使用 /player <name> spawn 生成假人。")
            return
        }

        val botName = bot.name.string
        lastActiveBotName = botName
        val scope = scopeFor(botName)
        val memory = memoryFor(botName)
        jobs[botName]?.cancel()

        jobs[botName] = scope.launch {
            try {
                memory.addUserMessage(command)
                println("[AnimaFabric] 指令交给 TaskPlanner 处理: $command (发送者: ${sender?.name?.string ?: "控制台"})")
                val actionExecutor = ActionExecutor(botName, server)
                val taskPlanner = TaskPlanner(botName, server, llmClient, actionExecutor, sender)
                val result = withTimeout(config.timeout * 2000) {
                    taskPlanner.processTask(command)
                }
                memory.addAssistantMessage(result)
                onComplete(result)
            } catch (e: TimeoutCancellationException) {
                val result = "指令执行超时"
                memory.addAssistantMessage(result)
                onComplete(result)
            } catch (e: CancellationException) {
                val result = "指令已取消"
                memory.addAssistantMessage(result)
                onComplete(result)
            } catch (e: Exception) {
                val result = "执行出错：${e.message}"
                memory.addAssistantMessage(result)
                onComplete(result)
            }
        }
    }

    /**
     * 和最近活跃的假人共享记忆进行自然语言聊天。
     */
    fun chat(message: String, onComplete: (String) -> Unit) {
        val botName = resolveChatBotName()
        if (botName == null) {
            onComplete("没有可用的对话上下文。请先向一个假人发送任务，或确保当前只有一个假人。")
            return
        }

        lastActiveBotName = botName
        val scope = scopeFor(botName)
        val memory = memoryFor(botName)
        chatJobs[botName]?.cancel()

        chatJobs[botName] = scope.launch {
            try {
                memory.addUserMessage(message)
                val messages = mutableListOf(
                    ChatMessage(
                        "system",
                        """
                        你是 Minecraft 里的 AI 假人 $botName。
                        直接回答玩家的问题，可以参考之前任务和对话记忆。
                        不要调用工具，不要输出 JSON，不要输出 !tool(...) 形式的动作命令。
                        如果记忆里没有答案，就直接说不知道。
                        """.trimIndent()
                    )
                )
                messages.addAll(memory.getMessages())

                val response = llmClient.chatStream(messages, extractActions = false)
                val reply = response.content.trim().ifBlank { "我暂时没有想好怎么回答。" }
                memory.addAssistantMessage(reply)
                onComplete(reply)
            } catch (e: CancellationException) {
                onComplete("对话已取消")
            } catch (e: Exception) {
                onComplete("对话出错：${e.message}")
            }
        }
    }

    /**
     * 恢复指定假人的任务计划文件。
     */
    fun resumePlan(name: String, planPath: Path, onComplete: (String) -> Unit) {
        val bot = FakePlayerManager.getBot(server, name)
        if (bot == null) {
            onComplete("假人 '$name' 不存在。请先使用 /player <name> spawn 生成假人。")
            return
        }

        val botName = bot.name.string
        lastActiveBotName = botName
        val scope = scopeFor(botName)
        val memory = memoryFor(botName)

        jobs[botName]?.cancel()

        jobs[botName] = scope.launch {
            try {
                val currentConfig = config
                val currentClient = llmClient
                val actionExecutor = ActionExecutor(botName, server)
                val taskPlanner = TaskPlanner(botName, server, currentClient, actionExecutor)
                val result = withTimeout(currentConfig.timeout * 2000) {
                    taskPlanner.resumePlan(planPath)
                }
                memory.addAssistantMessage(result)
                onComplete(result)
            } catch (e: TimeoutCancellationException) {
                val result = "计划恢复执行超时"
                memory.addAssistantMessage(result)
                onComplete(result)
            } catch (e: CancellationException) {
                val result = "计划恢复已取消"
                memory.addAssistantMessage(result)
                onComplete(result)
            } catch (e: Exception) {
                val result = "计划恢复出错：${e.message}"
                memory.addAssistantMessage(result)
                onComplete(result)
            }
        }
    }

    /**
     * 更新运行时配置，并让后续 LLM 请求使用新的客户端。
     */
    fun updateConfig(newConfig: AnimaFabricConfig) {
        config = newConfig
        llmClient = LLMClient(newConfig)
    }

    /**
     * 停止指定假人的当前动作。
     */
    fun stop(name: String) {
        val bot = FakePlayerManager.getBot(server, name) ?: return
        val botName = bot.name.string

        jobs[botName]?.cancel()
        jobs.remove(botName)
        chatJobs[botName]?.cancel()
        chatJobs.remove(botName)

        try {
            server.commands.performPrefixedCommand(
                server.createCommandSourceStack(),
                "/player $botName stop"
            )
        } catch (_: Exception) {}
    }

    /**
     * 停止所有假人的动作。
     */
    fun stopAll() {
        FakePlayerManager.listNames(server).forEach { stop(it) }
    }

    /**
     * 移除指定假人。
     */
    fun kill(name: String) {
        stop(name)
        val bot = FakePlayerManager.getBot(server, name) ?: return
        val botName = bot.name.string

        scopes[botName]?.cancel()
        scopes.remove(botName)
        memories.remove(botName)
        if (lastActiveBotName == botName) {
            lastActiveBotName = null
        }
        FakePlayerManager.kill(server, name)
    }

    /**
     * 移除所有假人。
     */
    fun killAll() {
        stopAll()
        FakePlayerManager.killAll(server)
        scopes.clear()
        memories.clear()
        chatJobs.clear()
        lastActiveBotName = null
    }

    /**
     * 获取假人的对话记忆。
     */
    fun getMemory(name: String): ConversationMemory? {
        val bot = FakePlayerManager.getBot(server, name) ?: return null
        return memories[bot.name.string]
    }

    /**
     * 清除指定假人的对话记忆。
     */
    fun clearMemory(name: String) {
        val bot = FakePlayerManager.getBot(server, name) ?: return
        memories[bot.name.string]?.clear()
    }

    private fun scopeFor(botName: String): CoroutineScope {
        return scopes.computeIfAbsent(botName) {
            CoroutineScope(Dispatchers.Default + SupervisorJob())
        }
    }

    private fun memoryFor(botName: String): ConversationMemory {
        return memories.computeIfAbsent(botName) {
            ConversationMemory(config.maxHistoryTurns, llmClient)
        }
    }

    private fun resolveChatBotName(): String? {
        val bots = getAvailableBots()
        val last = lastActiveBotName
        if (last != null && bots.contains(last)) {
            return last
        }
        return bots.singleOrNull()
    }
}
