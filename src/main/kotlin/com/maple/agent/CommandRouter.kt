package com.maple.agent

import com.maple.entity.FakePlayerManager

/**
 * 命令路由器 - 判断指令类型并分发到对应处理器。
 */
object CommandRouter {

    // 简单指令的正则模式
    private val simplePatterns = listOf(
        // 移动类（允许方向词和数字之间有空格）
        Regex("^(往前|往前走|向前|前进|forward)\\s*(\\d+)?格?$") to "move_forward",
        Regex("^(往后|往后走|向后|后退|backward|back)\\s*(\\d+)?格?$") to "move_backward",
        Regex("^(往左|往左走|向左|left)\\s*(\\d+)?格?$") to "move_left",
        Regex("^(往右|往右走|向右|right)\\s*(\\d+)?格?$") to "move_right",
        Regex("^(移动|走到|去)(\\d+)[,，\\s]*(\\d+)[,，\\s]*(\\d+)$") to "move_to",

        // 转向类
        Regex("^(向左转|左转|turn left)$") to "turn_left",
        Regex("^(向右转|右转|turn right)$") to "turn_right",
        Regex("^(转头|转身|turn back)$") to "turn_back",

        // 动作类
        Regex("^(跳|跳跃|jump)$") to "jump",
        Regex("^(攻击|打|attack)$") to "attack",
        Regex("^(使用|用|use)$") to "use",

        // 查看类
        Regex("^(查看?背包|看看?背包|背包里有什么|inventory)$") to "get_inventory",
        Regex("^(查看?血量|血量多少|health)$") to "get_health",
        Regex("^(查看?饥饿|饥饿值多少|hunger)$") to "get_hunger",
        Regex("^(扫描|看看?周围|scan)$") to "scan_area",

        // 停止类
        Regex("^(停|停止|停下|stop)$") to "stop",

        // 潜行类
        Regex("^(蹲|蹲下|潜行|sneak|crouch|站起|站起来|起身)$") to "sneak",
    )

    /**
     * 分析指令类型。
     * 返回 SimpleCommand（简单指令）或 ComplexCommand（复杂指令）。
     */
    fun analyze(command: String): CommandType {
        val trimmed = command.trim()

        // 检查是否匹配简单指令模式
        for ((pattern, action) in simplePatterns) {
            val match = pattern.find(trimmed)
            if (match != null) {
                return CommandType.Simple(action, match.groupValues)
            }
        }

        // 检查是否包含"挖掘面前/前面的方块"等指令
        if (trimmed.contains("挖掘") && (trimmed.contains("面前") || trimmed.contains("前面"))) {
            return CommandType.Simple("mine_front", listOf(trimmed))
        }

        // 检查是否包含直接指令关键词（需要 LLM 翻译成具体指令）
        val commandKeywords = listOf("给我", "传送", "设置", "切换", "召唤", "杀死", "清空", "创造", "生存", "冒险", "旁观")
        for (keyword in commandKeywords) {
            if (trimmed.contains(keyword)) {
                // 交给 LLM 处理，但会使用 executeCommand 工具
                return CommandType.Complex(trimmed)
            }
        }

        // 其他情况交给 LLM 规划
        return CommandType.Complex(trimmed)
    }

    sealed class CommandType {
        data class Simple(val action: String, val groups: List<String>) : CommandType()
        data class Complex(val command: String) : CommandType()
    }
}
