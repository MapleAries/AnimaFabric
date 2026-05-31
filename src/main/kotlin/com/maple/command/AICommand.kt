package com.maple.command

import com.maple.agent.AgentController
import com.maple.entity.FakePlayerManager
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

/**
 * 注册 /ai 指令：
 * - /ai spawn <名字> — 生成 AI bot
 * - /ai <名字> <指令> — 向 bot 发送指令
 * - /ai stop <名字> — 停止 bot 当前动作
 * - /ai kill <名字> — 移除 bot
 * - /ai list — 列出所有 bot
 * - /ai killall — 移除所有 bot
 */
object AICommand {

    private var controller: AgentController? = null

    fun setController(ctrl: AgentController) {
        controller = ctrl
    }

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("ai")
                    .then(Commands.literal("spawn")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .executes { spawnBot(it) }
                        )
                    )
                    .then(Commands.literal("stop")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .executes { stopBot(it) }
                        )
                    )
                    .then(Commands.literal("kill")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .executes { killBot(it) }
                        )
                    )
                    .then(Commands.literal("list")
                        .executes { listBots(it) }
                    )
                    .then(Commands.literal("killall")
                        .executes { killAllBots(it) }
                    )
                    .then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("command", StringArgumentType.greedyString())
                            .executes { sendCommand(it) }
                        )
                    )
            )
        }
    }

    private fun spawnBot(context: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(context, "name")
        val player = context.source.playerOrException
        val level = player.level() as net.minecraft.server.level.ServerLevel
        val pos = player.position()

        val ctrl = controller ?: run {
            context.source.sendFailure(Component.literal("AgentController 未初始化"))
            return 0
        }

        val bot = ctrl.spawn(name, level, pos.x, pos.y, pos.z)
        context.source.sendSuccess({
            Component.literal("已生成 AI bot: [AI] $name")
        }, true)

        return Command.SINGLE_SUCCESS
    }

    private fun sendCommand(context: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(context, "name")
        val command = StringArgumentType.getString(context, "command")

        val ctrl = controller ?: run {
            context.source.sendFailure(Component.literal("AgentController 未初始化"))
            return 0
        }

        if (!FakePlayerManager.exists(name)) {
            context.source.sendFailure(Component.literal("Bot '$name' 不存在"))
            return 0
        }

        // 异步执行，发送初始反馈
        context.source.sendSuccess({
            Component.literal("[AI-$name] 正在思考...")
        }, false)

        ctrl.sendCommand(name, command) { result ->
            context.source.server.execute {
                context.source.sendSuccess({
                    Component.literal("[AI-$name] $result")
                }, false)
            }
        }

        return Command.SINGLE_SUCCESS
    }

    private fun stopBot(context: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(context, "name")
        val ctrl = controller ?: return 0

        ctrl.stop(name)
        context.source.sendSuccess({
            Component.literal("已停止 bot '$name' 的所有动作")
        }, true)

        return Command.SINGLE_SUCCESS
    }

    private fun killBot(context: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(context, "name")
        val ctrl = controller ?: return 0

        if (!FakePlayerManager.exists(name)) {
            context.source.sendFailure(Component.literal("Bot '$name' 不存在"))
            return 0
        }

        ctrl.kill(name)
        context.source.sendSuccess({
            Component.literal("已移除 bot: $name")
        }, true)

        return Command.SINGLE_SUCCESS
    }

    private fun listBots(context: CommandContext<CommandSourceStack>): Int {
        val names = FakePlayerManager.listNames()
        if (names.isEmpty()) {
            context.source.sendSuccess({
                Component.literal("当前没有 AI bot")
            }, false)
        } else {
            context.source.sendSuccess({
                Component.literal("AI bot 列表：${names.joinToString(", ")}")
            }, false)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun killAllBots(context: CommandContext<CommandSourceStack>): Int {
        val ctrl = controller ?: return 0
        val count = FakePlayerManager.listNames().size
        ctrl.killAll()
        context.source.sendSuccess({
            Component.literal("已移除所有 $count 个 AI bot")
        }, true)
        return Command.SINGLE_SUCCESS
    }
}
