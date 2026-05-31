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

## 响应格式
你必须以 JSON 格式回复，支持以下三种格式之一：

### 单个工具调用
```json
{"tool": "工具名", "parameters": {"参数名": "值"}}
```

### 多步骤管线
```json
{"pipeline": [
    {"tool": "工具1", "parameters": {"参数": "值"}},
    {"tool": "工具2", "parameters": {"参数": "${'$'}result"}}
]}
```
在管线中，后续步骤可以使用 ${'$'}result 引用前一步骤的结果。

### 澄清请求（当指令不明确时）
```json
{"clarification": "你的问题"}
```

## 注意事项
1. 只返回 JSON，不要包含其他文字
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
