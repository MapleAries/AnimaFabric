package com.maple.agent

import net.minecraft.server.level.ServerPlayer

/**
 * 过程基类 - 有明确目标的任务执行单元。
 * 参考 Baritone 的 Process 模式。
 *
 * 与 Behavior 不同，Process 是有目标的、会完成的。
 * 例如：MineProcess（挖矿到完成）、BuildProcess（建造到完成）
 */
abstract class Process(val name: String) {

    /** 过程状态 */
    enum class Status {
        IDLE,       // 空闲
        RUNNING,    // 运行中
        PAUSED,     // 暂停
        SUCCESS,    // 成功完成
        FAILED      // 失败
    }

    /** 当前状态 */
    var status = Status.IDLE
        protected set

    /** 是否活跃（运行中或暂停） */
    val isActive: Boolean get() = status == Status.RUNNING || status == Status.PAUSED

    /**
     * 启动过程。
     * @param goal 目标描述
     */
    fun start(goal: String) {
        status = Status.RUNNING
        onStart(goal)
    }

    /**
     * 每 tick 调用。
     * @return 当前状态
     */
    fun tick(player: ServerPlayer): Status {
        if (status != Status.RUNNING) return status
        status = onTick(player)
        return status
    }

    /**
     * 暂停过程。
     */
    fun pause() {
        if (status == Status.RUNNING) {
            status = Status.PAUSED
            onPause()
        }
    }

    /**
     * 恢复过程。
     */
    fun resume() {
        if (status == Status.PAUSED) {
            status = Status.RUNNING
            onResume()
        }
    }

    /**
     * 取消过程。
     */
    fun cancel() {
        status = Status.IDLE
        onCancel()
    }

    // ========== 子类实现 ==========

    protected abstract fun onStart(goal: String)
    protected abstract fun onTick(player: ServerPlayer): Status
    protected open fun onPause() {}
    protected open fun onResume() {}
    protected open fun onCancel() {}
}

/**
 * 过程管理器 - 管理所有 Process，支持优先级和中断。
 */
class ProcessManager {
    private val processes = mutableMapOf<String, Process>()
    private var activeProcess: Process? = null

    fun register(process: Process) {
        processes[process.name] = process
    }

    fun unregister(name: String) {
        processes[name]?.cancel()
        processes.remove(name)
        if (activeProcess?.name == name) activeProcess = null
    }

    /**
     * 启动指定过程（会暂停当前活跃过程）。
     */
    fun startProcess(name: String, goal: String): Boolean {
        val process = processes[name] ?: return false

        // 暂停当前活跃过程
        activeProcess?.pause()

        // 启动新过程
        process.start(goal)
        activeProcess = process
        return true
    }

    /**
     * tick 驱动。
     */
    fun tick(player: ServerPlayer) {
        val process = activeProcess ?: return
        val status = process.tick(player)

        when (status) {
            Process.Status.SUCCESS, Process.Status.FAILED, Process.Status.IDLE -> {
                // 过程结束，恢复之前暂停的过程（如果有）
                activeProcess = null
            }
            else -> {}
        }
    }

    fun getActiveProcess(): Process? = activeProcess

    fun getProcess(name: String): Process? = processes[name]

    fun cancelAll() {
        processes.values.forEach { it.cancel() }
        activeProcess = null
    }
}
