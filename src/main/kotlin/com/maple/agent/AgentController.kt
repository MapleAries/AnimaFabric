package com.maple.agent

import com.maple.config.MCMindConfig
import com.maple.entity.FakePlayerManager
import com.maple.llm.LLMClient
import kotlinx.coroutines.*
import net.minecraft.server.MinecraftServer
import java.util.concurrent.ConcurrentHashMap

/**
 * 主控制器：接收指令 → 调用 LLM → 执行管线。
 * 使用 carpet 的 /player 命令控制 bot。
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
     */
    fun sendCommand(name: String, command: String, onComplete: (String) -> Unit) {
        if (!FakePlayerManager.exists(name)) {
            onComplete("Bot '$name' 不存在")
            return
        }

        val memory = memories[name] ?: run {
            onComplete("Bot '$name' 的记忆未初始化")
            return
        }

        val scope = scopes[name] ?: run {
            onComplete("Bot '$name' 的协程作用域未初始化")
            return
        }

        // 取消之前的任务
        jobs[name]?.cancel()

        // 启动新任务
        jobs[name] = scope.launch {
            try {
                val executor = ActionExecutor(name, server)
                val pipelineExecutor = PipelineExecutor(name, server, llmClient, memory, executor)
                val result = withTimeout(config.timeout * 2000) {
                    pipelineExecutor.processCommand(command)
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
            val commandManager = server.getCommands()
            val source = server.createCommandSourceStack()
            commandManager.performPrefixedCommand(source, "/player $name stop")
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
