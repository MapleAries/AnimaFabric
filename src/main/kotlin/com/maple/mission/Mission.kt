package com.maple.mission

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * 声明式任务模板（Malmo 风格 JSON Mission Schema）。
 * 用 JSON 定义任务的目标、条件、奖励，分离规格和实现。
 */

@Serializable
data class Mission(
    val name: String,
    val description: String = "",
    val agent: AgentConfig = AgentConfig(),
    val goal: GoalConfig? = null,
    val rewards: List<RewardConfig> = emptyList(),
    val quitConditions: List<QuitCondition> = emptyList(),
    val timeout: Long = 300000 // 5 分钟默认超时
)

@Serializable
data class AgentConfig(
    val startPosition: Position? = null,
    val startInventory: List<InventoryItem> = emptyList(),
    val mode: String = "survival" // survival, creative, adventure
)

@Serializable
data class Position(
    val x: Double = 0.0,
    val y: Double = 64.0,
    val z: Double = 0.0,
    val yaw: Float = 0f,
    val pitch: Float = 0f
)

@Serializable
data class InventoryItem(
    val slot: Int = 0,
    val item: String,
    val count: Int = 1
)

@Serializable
data class GoalConfig(
    val type: GoalType,
    val description: String = "",
    /** 目标位置（用于 reach_position 类型） */
    val position: Position? = null,
    /** 目标方块（用于 touch_block / break_block 类型） */
    val blockType: String? = null,
    /** 目标物品（用于 collect_item 类型） */
    val itemType: String? = null,
    /** 目标数量 */
    val count: Int = 1,
    /** 容差（格） */
    val tolerance: Double = 1.5
)

@Serializable
enum class GoalType {
    REACH_POSITION,     // 到达指定位置
    COLLECT_ITEM,       // 收集指定物品
    BREAK_BLOCK,        // 破坏指定方块
    PLACE_BLOCK,        // 放置指定方块
    CRAFT_ITEM,         // 合成指定物品
    KILL_ENTITY,        // 击杀指定实体
    SURVIVE_TIME,       // 存活指定时间
    BUILD_STRUCTURE     // 建造结构
}

@Serializable
data class RewardConfig(
    val type: RewardType,
    /** 奖励值 */
    val value: Double = 1.0,
    /** 触发条件 */
    val condition: String = "",
    /** 奖励密度 */
    val density: RewardDensity = RewardDensity.ONCE
)

@Serializable
enum class RewardType {
    TOUCH_BLOCK,        // 触碰方块
    COLLECT_ITEM,       // 收集物品
    KILL_ENTITY,        // 击杀实体
    REACH_POSITION,     // 到达位置
    TIME_PENALTY,       // 时间惩罚
    HEALTH_CHANGE,      // 血量变化
    COMMAND_SENT        // 发送命令
}

@Serializable
enum class RewardDensity {
    ONCE,               // 一次性
    PER_TICK,           // 每 tick
    PER_ACTION          // 每次行动
}

@Serializable
data class QuitCondition(
    val type: QuitType,
    /** 超时时间（毫秒） */
    val timeoutMs: Long = 0,
    /** 目标位置 */
    val position: Position? = null,
    /** 容差 */
    val tolerance: Double = 1.5
)

@Serializable
enum class QuitType {
    TIMEOUT,            // 超时
    REACH_POSITION,     // 到达位置
    AGENT_DIED,         // 代理死亡
    GOAL_REACHED        // 目标达成
}

/**
 * 任务管理器 - 加载、保存、执行任务模板。
 */
object MissionManager {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * 从 JSON 文件加载任务。
     */
    fun load(path: Path): Mission? {
        return try {
            if (Files.exists(path)) {
                json.decodeFromString<Mission>(Files.readString(path))
            } else null
        } catch (e: Exception) {
            println("[AnimaFabric] 加载任务失败: ${e.message}")
            null
        }
    }

    /**
     * 保存任务到 JSON 文件。
     */
    fun save(mission: Mission, path: Path) {
        try {
            Files.createDirectories(path.parent)
            Files.writeString(path, json.encodeToString(mission))
        } catch (e: Exception) {
            println("[AnimaFabric] 保存任务失败: ${e.message}")
        }
    }

    /**
     * 从 JSON 字符串解析任务。
     */
    fun parse(jsonStr: String): Mission? {
        return try {
            json.decodeFromString<Mission>(jsonStr)
        } catch (e: Exception) {
            println("[AnimaFabric] 解析任务失败: ${e.message}")
            null
        }
    }

    /**
     * 将任务序列化为 JSON 字符串。
     */
    fun serialize(mission: Mission): String {
        return json.encodeToString(mission)
    }
}
