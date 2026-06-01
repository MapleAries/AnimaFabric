package com.maple.agent

import com.maple.config.AnimaFabricConfig
import com.maple.entity.FakePlayerManager
import com.maple.llm.LLMClient
import kotlinx.coroutines.*
import net.minecraft.server.MinecraftServer
import java.util.concurrent.ConcurrentHashMap

/**
 * 主控制器：接收指令 → 判断类型 → 分发到简单执行器或 LLM 规划器。
 * 控制 carpet 生成的假人，不再自己生成。
 */
class AgentController(private val config: AnimaFabricConfig, private val server: MinecraftServer) {

    private val llmClient = LLMClient(config)
    private val memories = ConcurrentHashMap<String, ConversationMemory>()
    private val scopes = ConcurrentHashMap<String, CoroutineScope>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val behaviorModes = ConcurrentHashMap<String, BehaviorModes>()

    /**
     * 获取所有可用的假人名称。
     */
    fun getAvailableBots(): List<String> {
        return FakePlayerManager.listNames(server)
    }

    /**
     * 向指定假人发送指令。
     * 先判断是否为简单指令，如果是则直接执行，否则交给 LLM 规划。
     */
    fun sendCommand(name: String, command: String, onComplete: (String) -> Unit) {
        val bot = FakePlayerManager.getBot(server, name)
        if (bot == null) {
            onComplete("假人 '$name' 不存在。请先使用 /player <name> spawn 生成假人。")
            return
        }

        val botName = bot.name.string

        // 确保有协程作用域和行为模式
        if (!scopes.containsKey(botName)) {
            scopes[botName] = CoroutineScope(Dispatchers.Default + SupervisorJob())
        }
        if (!memories.containsKey(botName)) {
            memories[botName] = ConversationMemory(config.maxHistoryTurns, llmClient)
        }
        if (!behaviorModes.containsKey(botName)) {
            behaviorModes[botName] = BehaviorModes(botName, server)
        }

        val scope = scopes[botName]!!

        // 取消之前的任务
        jobs[botName]?.cancel()

        // 分析指令类型
        val commandType = CommandRouter.analyze(command)

        // 启动新任务
        jobs[botName] = scope.launch {
            try {
                val result = when (commandType) {
                    is CommandRouter.CommandType.Simple -> {
                        // 简单指令：直接执行
                        println("[AnimaFabric] 简单指令: ${commandType.action}")
                        val executor = SimpleCommandExecutor(botName, server)
                        executor.execute(commandType.action, commandType.groups)
                    }
                    is CommandRouter.CommandType.Complex -> {
                        // 复杂指令：LLM 规划
                        println("[AnimaFabric] 复杂指令，交给 LLM 规划")
                        val memory = memories[botName]!!
                        val actionExecutor = ActionExecutor(botName, server)
                        val pipelineExecutor = PipelineExecutor(botName, server, llmClient, memory, actionExecutor)
                        withTimeout(config.timeout * 2000) {
                            pipelineExecutor.processCommand(commandType.command)
                        }
                    }
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
     * 停止指定假人的当前动作。
     */
    fun stop(name: String) {
        val bot = FakePlayerManager.getBot(server, name) ?: return
        val botName = bot.name.string

        jobs[botName]?.cancel()
        jobs.remove(botName)

        try {
            server.getCommands().performPrefixedCommand(
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
        behaviorModes.remove(botName)
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
        behaviorModes.clear()
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

    /**
     * 获取假人的行为模式。
     */
    fun getBehaviorModes(name: String): BehaviorModes? {
        val bot = FakePlayerManager.getBot(server, name) ?: return null
        return behaviorModes[bot.name.string]
    }
}
