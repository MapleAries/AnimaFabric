package com.maple.mission

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 会话录制器。
 * 记录代理的观测、动作、奖励，用于调试和模仿学习。
 * 参考 Malmo 的 MissionRecordSpec。
 */
class SessionRecorder {

    /** 是否正在录制 */
    var isRecording = false
        private set

    /** 当前会话数据 */
    private var session: SessionData? = null

    /** 录制间隔（tick） */
    var recordInterval = 1 // 每 tick 都记录

    private var tickCounter = 0

    /**
     * 开始录制。
     */
    fun startRecording(missionName: String) {
        session = SessionData(
            missionName = missionName,
            startTime = System.currentTimeMillis(),
            frames = mutableListOf()
        )
        isRecording = true
        tickCounter = 0
        println("[AnimaFabric] 开始录制会话: $missionName")
    }

    /**
     * 停止录制。
     */
    fun stopRecording(): SessionData? {
        isRecording = false
        val data = session
        data?.let { it.endTime = System.currentTimeMillis() }
        session = null
        println("[AnimaFabric] 停止录制会话，共 ${data?.frames?.size ?: 0} 帧")
        return data
    }

    /**
     * 每 tick 调用，记录一帧。
     */
    fun tick(player: ServerPlayer) {
        if (!isRecording) return

        tickCounter++
        if (tickCounter % recordInterval != 0) return

        val frame = FrameData(
            tick = tickCounter,
            timestamp = System.currentTimeMillis(),
            position = PositionData(
                x = player.x,
                y = player.y,
                z = player.z,
                yaw = player.yRot,
                pitch = player.xRot
            ),
            health = player.health,
            hunger = player.foodData.foodLevel,
            inventory = captureInventory(player),
            observation = captureObservation(player)
        )

        session?.frames?.add(frame)
    }

    /**
     * 记录动作。
     */
    fun recordAction(action: String, params: Map<String, String> = emptyMap()) {
        if (!isRecording) return
        session?.frames?.lastOrNull()?.let { frame ->
            val updated = frame.copy(
                actions = frame.actions + ActionData(action, params, System.currentTimeMillis())
            )
            session?.frames?.let {
                if (it.isNotEmpty()) {
                    it[it.size - 1] = updated
                }
            }
        }
    }

    /**
     * 记录奖励。
     */
    fun recordReward(reward: Double, source: String) {
        if (!isRecording) return
        session?.frames?.lastOrNull()?.let { frame ->
            val updated = frame.copy(
                rewards = frame.rewards + RewardRecord(source, reward, System.currentTimeMillis())
            )
            session?.frames?.let {
                if (it.isNotEmpty()) {
                    it[it.size - 1] = updated
                }
            }
        }
    }

    /**
     * 保存会话到文件。
     */
    fun saveSession(path: Path) {
        val data = session ?: return
        try {
            Files.createDirectories(path.parent)
            val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
            Files.writeString(path, json.encodeToString(data))
            println("[AnimaFabric] 会话已保存: $path")
        } catch (e: Exception) {
            println("[AnimaFabric] 保存会话失败: ${e.message}")
        }
    }

    /**
     * 从文件加载会话。
     */
    fun loadSession(path: Path): SessionData? {
        return try {
            if (Files.exists(path)) {
                val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
                json.decodeFromString<SessionData>(Files.readString(path))
            } else null
        } catch (e: Exception) {
            println("[AnimaFabric] 加载会话失败: ${e.message}")
            null
        }
    }

    // ========== 辅助方法 ==========

    private fun captureInventory(player: ServerPlayer): List<InventorySnapshot> {
        val items = mutableListOf<InventorySnapshot>()
        for (i in 0 until player.inventory.containerSize) {
            val stack = player.inventory.getItem(i)
            if (!stack.isEmpty) {
                items.add(InventorySnapshot(
                    slot = i,
                    item = stack.hoverName.string,
                    count = stack.count
                ))
            }
        }
        return items
    }

    private fun captureObservation(player: ServerPlayer): Map<String, String> {
        val obs = mutableMapOf<String, String>()
        obs["position"] = "${player.x},${player.y},${player.z}"
        obs["health"] = player.health.toString()
        obs["hunger"] = player.foodData.foodLevel.toString()
        obs["onGround"] = player.onGround().toString()
        obs["yaw"] = player.yRot.toString()
        return obs
    }
}

// ========== 序列化数据类 ==========

@Serializable
data class SessionData(
    val missionName: String,
    val startTime: Long,
    var endTime: Long = 0,
    val frames: MutableList<FrameData> = mutableListOf()
)

@Serializable
data class FrameData(
    val tick: Int,
    val timestamp: Long,
    val position: PositionData,
    val health: Float,
    val hunger: Int,
    val inventory: List<InventorySnapshot> = emptyList(),
    val observation: Map<String, String> = emptyMap(),
    val actions: List<ActionData> = emptyList(),
    val rewards: List<RewardRecord> = emptyList()
)

@Serializable
data class PositionData(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float
)

@Serializable
data class InventorySnapshot(
    val slot: Int,
    val item: String,
    val count: Int
)

@Serializable
data class ActionData(
    val action: String,
    val params: Map<String, String> = emptyMap(),
    val timestamp: Long
)

@Serializable
data class RewardRecord(
    val source: String,
    val value: Double,
    val timestamp: Long
)
