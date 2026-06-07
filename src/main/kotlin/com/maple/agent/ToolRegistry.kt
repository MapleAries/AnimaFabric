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
            name = "equipItem",
            description = "Equip an item in the bot's main hand.",
            parameters = listOf(
                ToolParameter("item", "string", "Item id or common item name")
            )
        ),
        Tool(
            name = "useItem",
            description = "Use the specified item, or the current main hand item if no item is provided.",
            parameters = listOf(
                ToolParameter("item", "string", "Item id or common item name", required = false)
            )
        ),
        Tool(
            name = "useItemOnBlock",
            description = "Equip an item, look at a block, and use the item on that block.",
            parameters = listOf(
                ToolParameter("item", "string", "Item id or common item name"),
                ToolParameter("x", "number", "Target block X"),
                ToolParameter("y", "number", "Target block Y"),
                ToolParameter("z", "number", "Target block Z")
            )
        ),
        Tool(
            name = "eatFood",
            description = "Equip and eat a food item. Defaults to bread.",
            parameters = listOf(
                ToolParameter("item", "string", "Food item name", required = false)
            )
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
            name = "findNearbyBlock",
            description = "Find nearby blocks of a given type and return their coordinates.",
            parameters = listOf(
                ToolParameter("block", "string", "Block id or common block name"),
                ToolParameter("radius", "number", "Search radius, default 8", required = false)
            ),
            stateKeys = listOf("nearby_block")
        ),
        Tool(
            name = "findPortalFrame",
            description = "Find nearby end portal frame blocks.",
            parameters = listOf(
                ToolParameter("radius", "number", "Search radius, default 16", required = false)
            ),
            stateKeys = listOf("portal_frame")
        ),
        Tool(
            name = "buildNetherPortal",
            description = "Build a 4x5 obsidian nether portal frame. Axis x means width along X, axis z means width along Z.",
            parameters = listOf(
                ToolParameter("x", "number", "Frame lower-left X"),
                ToolParameter("y", "number", "Frame lower-left Y"),
                ToolParameter("z", "number", "Frame lower-left Z"),
                ToolParameter("axis", "string", "x or z, default x", required = false)
            )
        ),
        Tool(
            name = "ignitePortal",
            description = "Ignite a nether portal frame built by buildNetherPortal.",
            parameters = listOf(
                ToolParameter("x", "number", "Frame lower-left X"),
                ToolParameter("y", "number", "Frame lower-left Y"),
                ToolParameter("z", "number", "Frame lower-left Z"),
                ToolParameter("axis", "string", "x or z, default x", required = false)
            )
        ),
        Tool(
            name = "enterPortal",
            description = "Move into the nearest nether portal block.",
            parameters = listOf(
                ToolParameter("radius", "number", "Search radius, default 8", required = false)
            )
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
            name = "locateStructure",
            description = "定位最近的世界结构，支持具体结构或结构标签，如 village、ancient_city、desert_pyramid、mansion、stronghold、minecraft:village、#minecraft:village。",
            parameters = listOf(
                ToolParameter("structure", "string", "结构名称、结构 ID 或结构标签"),
                ToolParameter("radius", "number", "搜索半径（chunk），默认 100", required = false)
            ),
            stateKeys = listOf("structure_location")
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
            description = "调试模式给予物品。当前实现通过命令给予物品，并不消耗背包材料。",
            parameters = listOf(
                ToolParameter("item", "string", "物品名称：planks/crafting_table/stick/wooden_pickaxe/wooden_axe/wooden_sword/stone_pickaxe/stone_axe/iron_pickaxe/iron_sword/furnace/torch/cobblestone 等")
            )
        ),
        Tool(
            name = "drop",
            description = "丢出物品。",
            parameters = listOf(
                ToolParameter("slot", "string", "槽位：mainhand/offhand/0-35/all", required = false),
                ToolParameter("continuous", "boolean", "是否持续丢出", required = false)
            )
        ),
        Tool(
            name = "hotbar",
            description = "切换快捷栏槽位。",
            parameters = listOf(
                ToolParameter("slot", "number", "槽位 1-9")
            )
        ),
        Tool(
            name = "swapHands",
            description = "交换主副手物品。",
            parameters = emptyList()
        ),
        Tool(
            name = "mount",
            description = "骑乘坐骑或实体。",
            parameters = listOf(
                ToolParameter("anything", "boolean", "是否骑乘任意实体", required = false)
            )
        ),
        Tool(
            name = "dismount",
            description = "下马。",
            parameters = emptyList()
        ),
        Tool(
            name = "sprint",
            description = "冲刺/停止冲刺。",
            parameters = listOf(
                ToolParameter("enable", "boolean", "true=冲刺, false=停止", required = false)
            )
        )
    )

    fun getTool(name: String): Tool? = allTools.find { it.name == name }
}
