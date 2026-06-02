package com.maple.agent

data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true
)

data class Tool(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>,
    val stateKeys: List<String> = emptyList()
)

object ToolRegistry {

    val allTools = listOf(
        Tool(
            name = "moveTo",
            description = "自动寻路移动到指定坐标。会自动避开障碍物。",
            parameters = listOf(
                ToolParameter("x", "number", "目标 X 坐标"),
                ToolParameter("y", "number", "目标 Y 坐标"),
                ToolParameter("z", "number", "目标 Z 坐标"),
                ToolParameter("sprint", "boolean", "是否冲刺", required = false)
            ),
            stateKeys = listOf("moveTo_result")
        ),
        Tool(
            name = "move",
            description = "短距离移动。按指定方向移动指定格数。",
            parameters = listOf(
                ToolParameter("direction", "string", "方向：forward/backward/left/right/north/south/east/west"),
                ToolParameter("ticks", "number", "移动距离（格数），默认 5", required = false)
            )
        ),
        Tool(
            name = "look",
            description = "设置视角朝向。",
            parameters = listOf(
                ToolParameter("yaw", "number", "水平角度（0=南，90=西，180=北，270=东）"),
                ToolParameter("pitch", "number", "垂直角度（-90=上看，0=平视，90=下看）")
            )
        ),
        Tool(
            name = "turn",
            description = "相对转向。",
            parameters = listOf(
                ToolParameter("direction", "string", "方向：left/right/back")
            )
        ),
        Tool(
            name = "jump",
            description = "跳跃一次。",
            parameters = emptyList()
        ),
        Tool(
            name = "attack",
            description = "攻击视线方向的实体。",
            parameters = emptyList()
        ),
        Tool(
            name = "use",
            description = "使用主手物品。",
            parameters = emptyList()
        ),
        Tool(
            name = "mineBlock",
            description = "挖掘指定坐标的方块。距离必须≤6格，否则先用moveTo靠近。",
            parameters = listOf(
                ToolParameter("x", "number", "方块 X 坐标"),
                ToolParameter("y", "number", "方块 Y 坐标"),
                ToolParameter("z", "number", "方块 Z 坐标")
            ),
            stateKeys = listOf("mineBlock_result")
        ),
        Tool(
            name = "placeBlock",
            description = "在指定坐标放置方块。需要主手有方块物品。",
            parameters = listOf(
                ToolParameter("x", "number", "放置位置 X"),
                ToolParameter("y", "number", "放置位置 Y"),
                ToolParameter("z", "number", "放置位置 Z"),
                ToolParameter("block", "string", "方块类型（如 oak_planks, cobblestone）")
            ),
            stateKeys = listOf("placeBlock_result")
        ),
        Tool(
            name = "getInventory",
            description = "查看当前背包内容。",
            parameters = emptyList(),
            stateKeys = listOf("inventory")
        ),
        Tool(
            name = "getHealth",
            description = "查看当前血量。",
            parameters = emptyList(),
            stateKeys = listOf("health")
        ),
        Tool(
            name = "getHunger",
            description = "查看当前饥饿值。",
            parameters = emptyList(),
            stateKeys = listOf("hunger")
        ),
        Tool(
            name = "scanArea",
            description = "扫描周围方块，返回指定半径内的方块统计。",
            parameters = listOf(
                ToolParameter("radius", "number", "扫描半径，默认 5", required = false)
            ),
            stateKeys = listOf("scan_result")
        ),
        Tool(
            name = "sendMessage",
            description = "向玩家发送聊天消息。",
            parameters = listOf(
                ToolParameter("message", "string", "要发送的消息内容")
            )
        ),
        Tool(
            name = "stop",
            description = "停止当前所有动作。",
            parameters = emptyList()
        ),
        Tool(
            name = "sneak",
            description = "切换潜行状态。调用一次蹲下，再调用一次站起来。",
            parameters = listOf(
                ToolParameter("duration", "number", "持续时间（毫秒），不填则持续", required = false)
            )
        ),
        Tool(
            name = "craft",
            description = "合成物品。自动使用背包中的材料合成。",
            parameters = listOf(
                ToolParameter("item", "string", "物品名称：planks/crafting_table/stick/wooden_pickaxe/wooden_axe/wooden_sword/stone_pickaxe/stone_axe/iron_pickaxe/iron_sword/furnace/torch/coal_iron_ingot 等")
            )
        )
    )

    fun getTool(name: String): Tool? = allTools.find { it.name == name }
}
