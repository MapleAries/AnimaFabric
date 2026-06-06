package com.maple.agent

import com.maple.config.AnimaFabricConfig
import com.maple.entity.FakePlayerManager
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

        if (!scopes.containsKey(botName)) {
            scopes[botName] = CoroutineScope(Dispatchers.Default + SupervisorJob())
        }
        if (!memories.containsKey(botName)) {
            memories[botName] = ConversationMemory(config.maxHistoryTurns, llmClient)
        }

        val scope = scopes[botName]!!
        jobs[botName]?.cancel()

        jobs[botName] = scope.launch {
            try {
                println("[AnimaFabric] 指令交给 TaskPlanner 处理: $command (发送者: ${sender?.name?.string ?: "控制台"})")
                val actionExecutor = ActionExecutor(botName, server)
                val taskPlanner = TaskPlanner(botName, server, llmClient, actionExecutor, sender)
                val result = withTimeout(config.timeout * 2000) {
                    taskPlanner.processTask(command)
                }
                onComplete(result)
            } catch (e: TimeoutCancellationException) {
                onComplete("指令执行超时")
            } catch (e: CancellationException) {
                onComplete("指令已取消")
            } catch (e: Exception) {
                onComplete("执行出错：${e.message}")
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
        if (!scopes.containsKey(botName)) {
            scopes[botName] = CoroutineScope(Dispatchers.Default + SupervisorJob())
        }

        val scope = scopes[botName]!!
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
                onComplete(result)
            } catch (e: TimeoutCancellationException) {
                onComplete("计划恢复执行超时")
            } catch (e: CancellationException) {
                onComplete("计划恢复已取消")
            } catch (e: Exception) {
                onComplete("计划恢复出错：${e.message}")
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
}
