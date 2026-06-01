package com.maple.agent

/**
 * 结构化行动方案，支持循环和条件判断。
 * 替代简单的 !tool(param) token 序列，实现 Voyager 风格的代码生成。
 *
 * 示例：
 * ```json
 * {
 *   "goal": "挖到 10 个铁矿",
 *   "steps": [
 *     {"action": "scanArea", "params": {"radius": 10}},
 *     {"action": "moveTo", "params": {"x": 100, "y": 40, "z": 200}},
 *     {"loop": {"until": {"inventory_contains": "raw_iron", "count": 10}, "max_iterations": 50,
 *       "steps": [
 *         {"action": "mineBlock", "params": {"x": 100, "y": 40, "z": 200}},
 *         {"action": "moveTo", "params": {"x": 101, "y": 40, "z": 200}}
 *       ]
 *     }},
 *     {"conditional": {"check": {"health_below": 10},
 *       "then": [{"action": "sendMessage", "params": {"message": "血量过低，停止挖掘"}}],
 *       "else": [{"action": "sendMessage", "params": {"message": "挖掘完成！"}}]
 *     }}
 *   ]
 * }
 * ```
 */
sealed class ActionPlanNode {

    /** 单个工具调用 */
    data class Action(
        val tool: String,
        val params: Map<String, Any> = emptyMap()
    ) : ActionPlanNode()

    /** 顺序执行一组步骤 */
    data class Sequential(
        val steps: List<ActionPlanNode>
    ) : ActionPlanNode()

    /** 循环执行，直到条件满足或达到最大迭代次数 */
    data class Loop(
        val steps: List<ActionPlanNode>,
        val until: LoopCondition? = null,
        val whileCondition: LoopCondition? = null,
        val maxIterations: Int = 20
    ) : ActionPlanNode()

    /** 条件分支 */
    data class Conditional(
        val condition: CheckCondition,
        val thenSteps: List<ActionPlanNode>,
        val elseSteps: List<ActionPlanNode> = emptyList()
    ) : ActionPlanNode()

    /** 延迟指定毫秒 */
    data class Delay(val ms: Long) : ActionPlanNode()
}

/**
 * 循环条件。
 */
data class LoopCondition(
    /** 检查类型 */
    val type: ConditionType,
    /** 目标物品名称（用于 inventory_contains） */
    val item: String? = null,
    /** 目标数量 */
    val count: Int? = null,
    /** 目标方块坐标 */
    val pos: BlockPos3? = null,
    /** 目标方块状态（air = 已破坏） */
    val blockState: String? = null,
    /** 血量阈值 */
    val health: Float? = null
)

/**
 * 条件检查（用于 conditional）。
 */
data class CheckCondition(
    val type: ConditionType,
    val item: String? = null,
    val count: Int? = null,
    val pos: BlockPos3? = null,
    val blockState: String? = null,
    val health: Float? = null
)

/**
 * 简单坐标。
 */
data class BlockPos3(val x: Int, val y: Int, val z: Int)

/**
 * 条件类型枚举。
 */
enum class ConditionType {
    /** 背包中包含指定数量的物品 */
    INVENTORY_CONTAINS,
    /** 背包已满 */
    INVENTORY_FULL,
    /** 指定位置的方块状态 */
    BLOCK_AT,
    /** 血量低于阈值 */
    HEALTH_BELOW,
    /** 血量高于阈值 */
    HEALTH_ABOVE,
    /** 总是为真 */
    ALWAYS,
    /** 总是为假 */
    NEVER
}
