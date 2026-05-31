package com.maple.agent

import com.maple.config.MCMindConfig
import com.maple.entity.FakePlayer
import com.maple.entity.FakePlayerManager
import com.maple.llm.LLMClient
import kotlinx.coroutines.*
import net.minecraft.server.level.ServerLevel
import java.util.concurrent.ConcurrentHashMap

/**
 * 主控制器：接收指令 → 调用 LLM → 执行管线。
 * 管理每个 bot 的独立协程和对话记忆。
 */
class AgentController(private val config: MCMindConfig) {

    private val llmClient = LLMClient(config)
    private val memories = ConcurrentHashMap<String, ConversationMemory>()
    private val scopes = ConcurrentHashMap<String, CoroutineScope>()
    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * 生成一个新的 AI bot。
     */
    fun spawn(name: String, level: ServerLevel, x: Double, y: Double, z: Double): FakePlayer {
        val bot = FakePlayerManager.spawn(name, level, x, y, z)
        memories[name] = ConversationMemory(config.maxHistoryTurns)
        scopes[name] = CoroutineScope(Dispatchers.Default + SupervisorJob())
        return bot
    }

    /**
     * 向指定 bot 发送指令。
     */
    fun sendCommand(name: String, command: String, onComplete: (String) -> Unit) {
        val bot = FakePlayerManager.get(name) ?: run {
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
                val executor = PipelineExecutor(bot, llmClient, memory)
                val result = withTimeout(config.timeout * 2000) {
                    executor.processCommand(command)
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

        val bot = FakePlayerManager.get(name)
        bot?.actionPack?.stopAll()
        bot?.zza = 0f
        bot?.xxa = 0f
    }

    /**
     * 移除指定 bot。
     */
    fun kill(name: String) {
        stop(name)
        scopes[name]?.cancel()
        scopes.remove(name)
        memories.remove(name)
        FakePlayerManager.kill(name)
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
