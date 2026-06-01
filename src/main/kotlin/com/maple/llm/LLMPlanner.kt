package com.maple.llm

import com.maple.agent.ToolRegistry

object LLMPlanner {

    fun buildSystemPrompt(worldState: String): String {
        val toolDescriptions = ToolRegistry.allTools.joinToString("\n") { tool ->
            val params = tool.parameters.joinToString(", ") { "${it.name}: ${it.type}" }
            "- !${tool.name}($params): ${tool.description}"
        }

        return """You are an AI Minecraft bot that can converse with players, see, move, mine, build, and interact with the world by using commands.

## Current World State
$worldState

## Available Commands
$toolDescriptions

## Response Rules

1. You MUST include at least one command in every response
2. Use the exact command format: !commandName(param1, param2)
3. You can include multiple commands in one response
4. You can add natural language text before or after commands
5. Always use coordinates from the world state - never guess coordinates
6. When mining, prefer the nearest target blocks
7. When mining blocks farther than 5 blocks away, use !moveTo first
8. Never mine terrain blocks (dirt, grass, stone) unless specifically asked
9. Never mine the block directly under your feet (Y-1)

## Examples

Player: "move forward 3 blocks"
You: "Sure! !move(forward, 3)"

Player: "mine the block in front of me"
You: "I'll mine that block. !mineBlock(100, 64, 200)"

Player: "chop some wood"
You: "I'll find the nearest tree and chop it down. !moveTo(10, 64, -5) !mineBlock(10, 65, -5) !mineBlock(10, 64, -5) !mineBlock(10, 63, -5)"

Player: "build a small house"
You: "I'll build a simple house for you. !moveTo(5, 64, 0) !placeBlock(5, 64, 0, oak_planks) !placeBlock(5, 65, 0, oak_planks)"

Player: "what's in my inventory?"
You: "Let me check your inventory. !getInventory()"

Player: "follow me"
You: "I'll follow you! !moveTo(100, 64, 200)"

## Important Notes
- Always use actual coordinates from the world state
- If you need to move to a location first, use !moveTo before other actions
- Commands are executed in order from left to right
- You can chain multiple commands in a single response
"""
    }

    fun buildUserPrompt(command: String): String {
        return command
    }

    /**
     * 构建重试 prompt，包含错误反馈。
     * 当执行失败时，将错误信息回传 LLM，让它修正方案。
     */
    fun buildRetryPrompt(worldState: String, errorFeedback: String, attempt: Int): String {
        val toolDescriptions = ToolRegistry.allTools.joinToString("\n") { tool ->
            val params = tool.parameters.joinToString(", ") { "${it.name}: ${it.type}" }
            "- !${tool.name}($params): ${tool.description}"
        }

        return """You are an AI Minecraft bot. Your previous plan failed. Please analyze the error and try a different approach.

## Current World State
$worldState

## Available Commands
$toolDescriptions

## Previous Attempt (Attempt $attempt) - FAILED
$errorFeedback

## Instructions
1. Analyze WHY the previous attempt failed
2. Consider the current world state and adjust your approach
3. If a block was too far, use !moveTo first to get closer
4. If a path was blocked, try a different direction
5. If resources are missing, try to find alternatives
6. Generate a NEW plan that avoids the previous mistakes
7. Do NOT repeat the exact same commands that failed

## Response Rules
1. You MUST include at least one command in every response
2. Use the exact command format: !commandName(param1, param2)
3. Commands are executed in order from left to right
4. Always use actual coordinates from the world state
5. When mining blocks farther than 5 blocks away, use !moveTo first

## Important
- Think about what went wrong and adjust your strategy
- Prefer simpler approaches if the complex one failed
- If you need to move closer to a target, do that FIRST
"""
    }
}
