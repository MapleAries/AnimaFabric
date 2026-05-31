package com.maple.agent

import com.maple.entity.FakePlayer
import com.maple.pathfinding.AStarPathfinder
import com.maple.pathfinding.PathFollower
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.world.phys.Vec3

/**
 * 工具分发 + 执行逻辑。
 */
class ActionExecutor(private val bot: FakePlayer) {

    private val pathFollower = PathFollower()

    /**
     * 执行单个工具调用，返回执行结果。
     */
    suspend fun execute(toolName: String, params: Map<String, Any>): String {
        return when (toolName) {
            "moveTo" -> executeMoveTo(params)
            "move" -> executeMove(params)
            "look" -> executeLook(params)
            "turn" -> executeTurn(params)
            "jump" -> executeJump()
            "attack" -> executeAttack()
            "use" -> executeUse()
            "mineBlock" -> executeMineBlock(params)
            "placeBlock" -> executePlaceBlock(params)
            "getInventory" -> executeGetInventory()
            "getHealth" -> executeGetHealth()
            "getHunger" -> executeGetHunger()
            "scanArea" -> executeScanArea(params)
            "sendMessage" -> executeSendMessage(params)
            "stop" -> executeStop()
            else -> "未知工具：$toolName"
        }
    }

    private suspend fun executeMoveTo(params: Map<String, Any>): String {
        val x = (params["x"] as? Number)?.toInt() ?: return "缺少参数 x"
        val y = (params["y"] as? Number)?.toInt() ?: return "缺少参数 y"
        val z = (params["z"] as? Number)?.toInt() ?: return "缺少参数 z"

        val start = bot.blockPosition()
        val end = BlockPos(x, y, z)
        val level = bot.level() as ServerLevel

        val path = AStarPathfinder.findPath(level, start, end)
        if (path.isEmpty()) {
            return "无法找到从 (${start.x}, ${start.y}, ${start.z}) 到 ($x, $y, $z) 的路径"
        }

        pathFollower.setPath(path)

        var ticks = 0
        val maxTicks = 200
        while (pathFollower.tick(bot) && ticks < maxTicks) {
            ticks++
            kotlinx.coroutines.delay(50)
        }

        return if (pathFollower.isComplete()) {
            "已到达 ($x, $y, $z)"
        } else if (pathFollower.isFailed()) {
            "移动失败，可能被卡住"
        } else {
            "移动超时"
        }
    }

    /**
     * 短距离移动 - 使用 teleportTo 逐格移动。
     */
    private suspend fun executeMove(params: Map<String, Any>): String {
        val direction = params["direction"] as? String ?: return "缺少参数 direction"
        val ticks = (params["ticks"] as? Number)?.toInt() ?: 20

        val speed = 0.15 // 每 tick 移动距离
        val yaw = Math.toRadians(bot.yRot.toDouble())

        // 根据方向计算移动向量
        val (dx, dz) = when (direction.lowercase()) {
            "forward" -> Pair(-Math.sin(yaw) * speed, Math.cos(yaw) * speed)
            "backward" -> Pair(Math.sin(yaw) * speed, -Math.cos(yaw) * speed)
            "left" -> Pair(-Math.cos(yaw) * speed, -Math.sin(yaw) * speed)
            "right" -> Pair(Math.cos(yaw) * speed, Math.sin(yaw) * speed)
            else -> return "无效方向：$direction"
        }

        // 每 tick 移动并同步到客户端
        for (i in 0 until ticks) {
            val newX = bot.x + dx
            val newZ = bot.z + dz
            // 使用 teleportTo 触发位置同步
            bot.teleportTo(newX, bot.y, newZ)
            // 强制同步位置给所有客户端
            bot.connection.resetPosition()
            kotlinx.coroutines.delay(50)
        }

        return "已向 $direction 移动 ${ticks}tick"
    }

    private fun executeLook(params: Map<String, Any>): String {
        val yaw = (params["yaw"] as? Number)?.toFloat() ?: return "缺少参数 yaw"
        val pitch = (params["pitch"] as? Number)?.toFloat() ?: return "缺少参数 pitch"

        bot.yRot = yaw
        bot.xRot = pitch.coerceIn(-90f, 90f)

        return "视角已设置为 yaw=$yaw, pitch=$pitch"
    }

    private fun executeTurn(params: Map<String, Any>): String {
        val direction = params["direction"] as? String ?: return "缺少参数 direction"

        when (direction.lowercase()) {
            "left" -> bot.yRot += 90f
            "right" -> bot.yRot -= 90f
            "back" -> bot.yRot += 180f
            else -> return "无效方向：$direction"
        }

        return "已转向 $direction"
    }

    private fun executeJump(): String {
        bot.jumpFromGround()
        return "已跳跃"
    }

    private fun executeAttack(): String {
        bot.actionPack.start(ActionPack.ActionType.ATTACK, ActionPack.Action.once(ActionPack.ActionType.ATTACK))
        return "已攻击"
    }

    private fun executeUse(): String {
        bot.actionPack.start(ActionPack.ActionType.USE, ActionPack.Action.once(ActionPack.ActionType.USE))
        return "已使用物品"
    }

    private fun executeMineBlock(params: Map<String, Any>): String {
        val x = (params["x"] as? Number)?.toInt() ?: return "缺少参数 x"
        val y = (params["y"] as? Number)?.toInt() ?: return "缺少参数 y"
        val z = (params["z"] as? Number)?.toInt() ?: return "缺少参数 z"

        val pos = BlockPos(x, y, z)
        val level = bot.level() as ServerLevel
        val state = level.getBlockState(pos)

        if (state.isAir) {
            return "($x, $y, $z) 处没有方块"
        }

        bot.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3(x + 0.5, y + 0.5, z + 0.5))

        bot.actionPack.start(ActionPack.ActionType.ATTACK, ActionPack.Action.continuous(ActionPack.ActionType.ATTACK))

        return "开始挖掘 ($x, $y, $z) 的 ${state.block.name.string}"
    }

    private fun executePlaceBlock(params: Map<String, Any>): String {
        val x = (params["x"] as? Number)?.toInt() ?: return "缺少参数 x"
        val y = (params["y"] as? Number)?.toInt() ?: return "缺少参数 y"
        val z = (params["z"] as? Number)?.toInt() ?: return "缺少参数 z"
        val blockName = params["block"] as? String ?: return "缺少参数 block"

        val pos = BlockPos(x, y, z)
        val level = bot.level() as ServerLevel

        val block = BuiltInRegistries.BLOCK.getOptional(Identifier.tryParse(blockName)).orElse(null)
            ?: return "未知方块类型：$blockName"

        bot.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3(x + 0.5, y + 0.5, z + 0.5))

        level.setBlock(pos, block.defaultBlockState(), 3)

        return "已在 ($x, $y, $z) 放置 $blockName"
    }

    private fun executeGetInventory(): String {
        val items = mutableListOf<String>()
        for (i in 0 until bot.inventory.containerSize) {
            val stack = bot.inventory.getItem(i)
            if (!stack.isEmpty) {
                items.add("${stack.hoverName.string} x${stack.count}")
            }
        }
        return if (items.isEmpty()) "背包为空" else items.joinToString("\n")
    }

    private fun executeGetHealth(): String {
        return "血量：${bot.health.toInt()}/${bot.maxHealth.toInt()}"
    }

    private fun executeGetHunger(): String {
        return "饥饿值：${bot.foodData.foodLevel}/20"
    }

    private fun executeScanArea(params: Map<String, Any>): String {
        val radius = (params["radius"] as? Number)?.toInt() ?: 5
        val pos = bot.blockPosition()
        val level = bot.level() as ServerLevel

        val blockCounts = mutableMapOf<String, Int>()
        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                for (dz in -radius..radius) {
                    val bp = pos.offset(dx, dy, dz)
                    val state = level.getBlockState(bp)
                    if (!state.isAir) {
                        val name = state.block.name.string
                        blockCounts[name] = (blockCounts[name] ?: 0) + 1
                    }
                }
            }
        }

        return if (blockCounts.isEmpty()) {
            "半径 ${radius} 格内没有方块"
        } else {
            blockCounts.entries
                .sortedByDescending { it.value }
                .take(10)
                .joinToString("\n") { "${it.key} x${it.value}" }
        }
    }

    private fun executeSendMessage(params: Map<String, Any>): String {
        val message = params["message"] as? String ?: return "缺少参数 message"
        val server = bot.level().server
        server.playerList.broadcastSystemMessage(
            net.minecraft.network.chat.Component.literal("[AI-${bot.botName}] $message"),
            false
        )
        return "已发送消息"
    }

    private fun executeStop(): String {
        bot.actionPack.stopAll()
        pathFollower.stop()
        return "已停止所有动作"
    }
}
