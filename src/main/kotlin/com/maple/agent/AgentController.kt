package com.maple.agent

import com.maple.config.MCMindConfig
import com.maple.entity.FakePlayerManager
import com.maple.llm.LLMClient
import kotlinx.coroutines.*
import net.minecraft.server.MinecraftServer
import java.util.concurrent.ConcurrentHashMap

/**
 * 主控制器：接收指令 → 判断类型 → 分发到简单执行器或 LLM 规划器。
 */
class AgentController(private val config: MCMindConfig, private val server: MinecraftServer) {

    private val llmClient = LLMClient(config)
    private val memories = ConcurrentHashMap<String, ConversationMemory>()
    private val scopes = ConcurrentHashMap<String, CoroutineScope>()
    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * 生成一个新的 AI bot。
     */
    fun spawn(name: String, x: Double, y: Double, z: Double): Boolean {
        val success = FakePlayerManager.spawn(name, server, x, y, z)
        if (success) {
            memories[name] = ConversationMemory(config.maxHistoryTurns)
            scopes[name] = CoroutineScope(Dispatchers.Default + SupervisorJob())
        }
        return success
    }

    /**
     * 向指定 bot 发送指令。
     * 先判断是否为简单指令，如果是则直接执行，否则交给 LLM 规划。
     */
    fun sendCommand(name: String, command: String, onComplete: (String) -> Unit) {
        if (!FakePlayerManager.exists(name)) {
            onComplete("Bot '$name' 不存在")
            return
        }

        val scope = scopes[name] ?: run {
            onComplete("Bot '$name' 的协程作用域未初始化")
            return
        }

        // 取消之前的任务
        jobs[name]?.cancel()

        // 分析指令类型
        val commandType = CommandRouter.analyze(command)

        // 启动新任务
        jobs[name] = scope.launch {
            try {
                val result = when (commandType) {
                    is CommandRouter.CommandType.Simple -> {
                        // 简单指令：直接执行
                        println("[MC-Mind] 简单指令: ${commandType.action}")
                        val executor = SimpleCommandExecutor(name, server)
                        executor.execute(commandType.action, commandType.groups)
                    }
                    is CommandRouter.CommandType.Complex -> {
                        // 复杂指令：LLM 规划
                        println("[MC-Mind] 复杂指令，交给 LLM 规划")
                        val memory = memories[name] ?: ConversationMemory(config.maxHistoryTurns)
                        val actionExecutor = ActionExecutor(name, server)
                        val pipelineExecutor = PipelineExecutor(name, server, llmClient, memory, actionExecutor)
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
     * 停止指定 bot 的当前动作。
     */
    fun stop(name: String) {
        jobs[name]?.cancel()
        jobs.remove(name)

        try {
            server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "/player $name stop"
            )
        } catch (_: Exception) {}
    }

    /**
     * 移除指定 bot。
     */
    fun kill(name: String) {
        stop(name)
        scopes[name]?.cancel()
        scopes.remove(name)
        memories.remove(name)
        FakePlayerManager.kill(name, server)
    }

    /**
     * 移除所有 bot。
     */
    fun killAll() {
        FakePlayerManager.listNames().forEach { kill(it) }
    }

    /**
     * 获取 bot 的对话记忆。
     */
    fun getMemory(name: String): ConversationMemory? = memories[name]

    /**
     * 清除指定 bot 的对话记忆。
     */
    fun clearMemory(name: String) {
        memories[name]?.clear()
    }
}
