package com.maple.agent

/**
 * 命令路由器 - 所有指令都交给 LLM 处理。
 */
object CommandRouter {

    /**
     * 分析指令类型。
     * 所有指令统一走 LLM 路径。
     */
    fun analyze(command: String): CommandType {
        return CommandType.Complex(command.trim())
    }

    sealed class CommandType {
        data class Complex(val command: String) : CommandType()
    }
}
