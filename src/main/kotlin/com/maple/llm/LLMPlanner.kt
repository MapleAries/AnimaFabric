package com.maple.llm

import com.maple.agent.ToolRegistry

object LLMPlanner {

    fun buildSystemPrompt(worldState: String, executionHistory: String = ""): String {
        val toolDescriptions = ToolRegistry.allTools.joinToString("\n") { tool ->
            val params = tool.parameters.joinToString(", ") { "${it.name}: ${it.type}" }
            val required = tool.parameters.filter { it.required }.joinToString(", ") { it.name }
            val optional = tool.parameters.filter { !it.required }.joinToString(", ") { "${it.name}(可选)" }
            val constraint = getToolConstraint(tool.name)
            "- !${tool.name}($params): ${tool.description}${if (constraint.isNotEmpty()) " $constraint" else ""}"
        }

        return """你是一个 Minecraft AI 机器人，通过命令与游戏世界交互。

## 当前世界状态
$worldState
${if (executionHistory.isNotEmpty()) "\n## 执行历史（已完成的步骤）\n$executionHistory\n" else ""}
## 可用命令
$toolDescriptions

## 响应规则

**格式**: 只输出命令，不要输出解释文字。
**格式示例**: !move(forward, 5) 或 !mineBlock(100, 64, 200)

### 关键约束
1. 每个响应最多 6 个命令，按顺序执行
2. 不要重复相同命令
3. 坐标必须来自世界状态，不要编造
4. 距离超过 5 格的目标，先用 !moveTo 靠近
5. !sneak() 是切换状态：第一次蹲下，第二次站起来

### 任务分解原则
对于复杂任务，按以下步骤思考：
1. 分析任务需要哪些步骤
2. 每个步骤用哪个命令
3. 步骤之间有什么依赖关系
4. 输出最小必要的命令序列

## 命令示例

### 简单任务
- "往前走5步" → !move(forward, 5)
- "蹲下" → !sneak()
- "跳一下" → !jump()
- "看看背包" → !getInventory()
- "扫描周围" → !scanArea(5)

### 复合任务
- "往前走5步然后蹲下再站起来" → !move(forward, 5) !sneak() !sneak()
- "往前走10步然后蹲下3秒再站起来" → !move(forward, 10) !sneak(3000)  （sneak(3000) 自动在3秒后站起来）
- "走到100 64 200然后挖方块" → !moveTo(100, 64, 200) !mineBlock(100, 64, 200)
- "转身往回走" → !turn(back) !move(forward, 5)

### sneak 命令说明
- !sneak() — 切换状态（第一次蹲下，第二次站起来）
- !sneak(3000) — 蹲下3秒后自动站起来，不需要再调用 !sneak()
- 如果任务说"蹲下N秒再站起来"，只需一个 !sneak(N*1000) 命令

### 采集任务
- "挖木头" → 先 !scanArea(10) 找到原木坐标，再 !moveTo 靠近，再 !mineBlock 挖掘
- "挖铁矿" → 先 !scanArea(10) 找矿，再移动挖掘

### 建造任务
- "放一个方块在脚下" → !placeBlock(当前X, 当前Y-1, 当前Z, cobblestone)

## 重要提醒
- 只输出命令，不要输出多余文字
- 用实际坐标，不要用变量名
- 如果不确定坐标，先用 !scanArea 扫描
"""
    }

    /**
     * 获取工具的额外约束说明。
     */
    private fun getToolConstraint(toolName: String): String {
        return when (toolName) {
            "move" -> "方向: forward/backward/left/right/north/south/east/west"
            "moveTo" -> "自动寻路，会避开障碍物"
            "mineBlock" -> "距离必须 ≤5 格，否则先 moveTo"
            "placeBlock" -> "需要主手有方块物品"
            "sneak" -> "切换模式：调用一次蹲下，再调用一次站起来"
            "look" -> "yaw: 0=南, 90=西, 180=北, 270=东; pitch: -90=上, 0=平, 90=下"
            else -> ""
        }
    }

    fun buildUserPrompt(command: String): String {
        return command
    }

    /**
     * 构建带执行反馈的 prompt。
     * 在多步任务中，告诉 LLM 前一步的执行结果。
     */
    fun buildFeedbackPrompt(worldState: String, originalCommand: String, stepResults: String): String {
        return """你是一个 Minecraft AI 机器人。你正在执行一个多步任务。

## 原始任务
$originalCommand

## 当前世界状态
$worldState

## 已完成的步骤
$stepResults

## 下一步
根据已完成的步骤和当前世界状态，决定下一步应该执行什么命令。
如果所有步骤已完成，输出 !stop()。
如果遇到问题，输出 !stop() 并用 !sendMessage 说明原因。

只输出命令，不要输出解释。
"""
    }

    /**
     * 构建任务分解 prompt。
     * 对于复杂任务，先让 LLM 分解为子步骤。
     */
    fun buildDecompositionPrompt(worldState: String, task: String): String {
        return """你是一个 Minecraft AI 任务规划器。将以下任务分解为简单的命令序列。

## 当前世界状态
$worldState

## 任务
$task

## 要求
1. 将任务分解为 2-6 个简单命令
2. 每个命令必须是以下之一：!move, !moveTo, !turn, !jump, !sneak, !mineBlock, !placeBlock, !attack, !use, !scanArea, !getInventory, !getHealth, !sendMessage, !stop
3. 使用实际坐标（从世界状态中获取）
4. 输出格式：每行一个命令

## 示例
任务："走到那棵树旁边挖木头"
输出：
!moveTo(10, 64, -5)
!mineBlock(10, 65, -5)
!mineBlock(10, 64, -5)

任务："蹲下然后站起来"
输出：
!sneak()
!sneak()
"""
    }

    /**
     * 构建重试 prompt，包含错误反馈。
     */
    fun buildRetryPrompt(worldState: String, errorFeedback: String, attempt: Int): String {
        val toolDescriptions = ToolRegistry.allTools.joinToString("\n") { tool ->
            val params = tool.parameters.joinToString(", ") { "${it.name}: ${it.type}" }
            "- !${tool.name}($params): ${tool.description}"
        }

        return """你是一个 Minecraft AI 机器人。之前的计划失败了，请分析错误并重试。

## 当前世界状态
$worldState

## 可用命令
$toolDescriptions

## 失败的尝试（第 $attempt 次）
$errorFeedback

## 要求
1. 分析失败原因
2. 根据当前世界状态调整方案
3. 如果方块太远，先 !moveTo 靠近
4. 如果路径被堵，换方向
5. 不要重复失败的命令
6. 生成新的命令序列

只输出命令，不要输出解释。
"""
    }
}
