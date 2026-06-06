package com.maple.agent

/**
 * Centralized classifier for tool execution results.
 *
 * Tool executors currently return user-facing strings instead of a structured
 * result type, so all planning paths must agree on which prefixes mean failure.
 */
object ActionResultClassifier {
    private val failurePrefixes = listOf(
        "Failed",
        "Error",
        "挖掘失败",
        "放置失败",
        "移动未完成",
        "Bot 不存在",
        "未知工具",
        "无效方向",
        "合成失败",
        "给予物品失败",
        "无法"
    )

    fun isFailure(result: String): Boolean {
        return failurePrefixes.any { result.startsWith(it) }
    }
}
