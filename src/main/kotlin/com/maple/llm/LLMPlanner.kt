package com.maple.llm

import com.maple.agent.ToolRegistry
import com.maple.agent.WorldPerception

object LLMPlanner {

    fun buildSystemPrompt(worldState: String): String {
        val toolDescriptions = ToolRegistry.allTools.joinToString("\n\n") { tool ->
            val params = tool.parameters.joinToString(", ") { "${it.name}: ${it.type}" }
            buildString {
                appendLine("### ${tool.name}")
                appendLine("- 描述：${tool.description}")
                appendLine("- 参数：$params")
                if (tool.stateKeys.isNotEmpty()) {
                    appendLine("- 输出状态：${tool.stateKeys.joinToString(", ") { "\$$it" }}")
                }
            }
        }

        return """你是一个 Minecraft 游戏中的 AI 助手。玩家会用自然语言给你下达指令，你需要分析指令并调用合适的工具来执行。

## 当前世界状态
$worldState

## 可用工具
$toolDescriptions

## 重要规则：你必须只返回 JSON，不要返回任何其他文字！

## 响应格式（只返回以下三种 JSON 之一）：

### 格式1：单个工具调用
{"tool": "工具名", "parameters": {"参数名": "值"}}

### 格式2：多步骤管线
{"pipeline": [
    {"tool": "工具1", "parameters": {"参数": "值"}},
    {"tool": "工具2", "parameters": {"参数": "${'$'}result"}}
]}

### 格式3：澄清请求（当指令不明确时）
{"clarification": "你的问题"}

## 示例：
用户说："往前走3格"
你返回：{"tool": "move", "parameters": {"direction": "forward", "ticks": 3}}

用户说："挖掘面前的方块"
你返回：{"tool": "mineBlock", "parameters": {"x": 100, "y": 64, "z": 200}}

用户说："给我一个钻石剑"
你返回：{"tool": "executeCommand", "parameters": {"command": "give @p diamond_sword"}}

用户说："搭建小屋"
你返回：{"pipeline": [
    {"tool": "executeCommand", "parameters": {"command": "give @p oak_planks 64"}},
    {"tool": "placeBlock", "parameters": {"x": 100, "y": 64, "z": 200, "block": "oak_planks"}}
]}

## 注意事项
1. 只返回 JSON，绝对不要返回其他文字！
2. 参数值必须是正确的类型（数字用数字，字符串用字符串）
3. 如果玩家的指令不明确，使用 clarification 格式询问
4. 管线中的步骤会按顺序执行
5. 使用 `stop` 工具可以停止当前所有动作
"""
    }

    fun buildUserPrompt(command: String): String {
        return command
    }
}
