package com.maple.command

import com.maple.agent.AgentController
import com.maple.config.AnimaFabricConfig
import com.maple.entity.FakePlayerManager
import net.minecraft.server.level.ServerPlayer
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

/**
 * 注册 /ai 指令。
 * 控制 carpet 生成的假人，不再自己生成。
 */
object AICommand {

    private var controller: AgentController? = null
    private var config: AnimaFabricConfig? = null

    fun setController(ctrl: AgentController) {
        controller = ctrl
    }

    fun setConfig(cfg: AnimaFabricConfig) {
        config = cfg
    }

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("ai")
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
                    .then(Commands.literal("spawn")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .executes { spawnBot(it) }
                        )
                    )
                    .then(Commands.literal("plan")
                        .executes { listPlans(it) }
                        .then(Commands.literal("resume")
                            .then(Commands.argument("file", StringArgumentType.greedyString())
                                .executes { resumePlan(it) }
                            )
                        )
                    )
                    .then(Commands.literal("config")
                        .then(Commands.literal("show")
                            .executes { showConfig(it) }
                        )
                        .then(Commands.literal("url")
                            .then(Commands.argument("value", StringArgumentType.greedyString())
                                .executes { setConfigUrl(it) }
                            )
                        )
                        .then(Commands.literal("key")
                            .then(Commands.argument("value", StringArgumentType.greedyString())
                                .executes { setConfigKey(it) }
                            )
                        )
                        .then(Commands.literal("model")
                            .then(Commands.argument("value", StringArgumentType.word())
                                .executes { setConfigModel(it) }
                            )
                        )
                    )
                    .then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("command", StringArgumentType.greedyString())
                            .executes { sendCommand(it) }
                        )
                    )
            )
        }
    }

    private fun sendCommand(context: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(context, "name")
        val command = StringArgumentType.getString(context, "command")

        val ctrl = controller ?: run {
            context.source.sendFailure(Component.literal("AgentController 未初始化"))
            return 0
        }

        if (!FakePlayerManager.exists(context.source.server, name)) {
            context.source.sendFailure(Component.literal("假人 '$name' 不存在。请使用 /ai spawn <名称> 生成假人。"))
            return 0
        }

        context.source.sendSuccess({
            Component.literal("[$name] 正在思考...")
        }, false)

        ctrl.sendCommand(name, command) { result ->
            context.source.server.execute {
                context.source.sendSuccess({
                    Component.literal("[$name] $result")
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
            Component.literal("已停止假人 '$name' 的所有动作")
        }, true)

        return Command.SINGLE_SUCCESS
    }

    private fun killBot(context: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(context, "name")
        val ctrl = controller ?: return 0

        if (!FakePlayerManager.exists(context.source.server, name)) {
            context.source.sendFailure(Component.literal("假人 '$name' 不存在"))
            return 0
        }

        ctrl.kill(name)
        context.source.sendSuccess({
            Component.literal("已移除假人: $name")
        }, true)

        return Command.SINGLE_SUCCESS
    }

    private fun listBots(context: CommandContext<CommandSourceStack>): Int {
        val names = FakePlayerManager.listNames(context.source.server)
        if (names.isEmpty()) {
            context.source.sendSuccess({
                Component.literal("当前没有假人。请使用 /ai spawn <名称> 生成假人。")
            }, false)
        } else {
            context.source.sendSuccess({
                Component.literal("假人列表：${names.joinToString(", ")}")
            }, false)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun killAllBots(context: CommandContext<CommandSourceStack>): Int {
        val ctrl = controller ?: return 0
        val count = FakePlayerManager.listNames(context.source.server).size
        ctrl.killAll()
        context.source.sendSuccess({
            Component.literal("已移除所有 $count 个假人")
        }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun spawnBot(context: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(context, "name")
        val source = context.source

        if (FakePlayerManager.exists(source.server, name)) {
            source.sendFailure(Component.literal("假人 '$name' 已存在"))
            return 0
        }

        // 在命令执行者当前位置生成
        val player = source.playerOrException
        val pos = player.position()

        try {
            FakePlayerManager.spawn(source.server, name, pos.x, pos.y, pos.z, player.yRot, player.xRot)
            source.sendSuccess({
                Component.literal("已生成假人 '[AI] $name' 在 (${pos.x.toInt()}, ${pos.y.toInt()}, ${pos.z.toInt()})")
            }, true)
        } catch (e: Exception) {
            source.sendFailure(Component.literal("生成假人失败: ${e.message}"))
            return 0
        }

        return Command.SINGLE_SUCCESS
    }

    private fun listPlans(context: CommandContext<CommandSourceStack>): Int {
        val plans = com.maple.agent.TaskPlanManager.listPlanFiles()
        if (plans.isEmpty()) {
            context.source.sendSuccess({
                Component.literal("没有任务计划文件。计划目录: ${com.maple.agent.TaskPlanManager.getPlanDir()}")
            }, false)
            return Command.SINGLE_SUCCESS
        }

        context.source.sendSuccess({
            Component.literal("=== 任务计划列表 ===")
        }, false)

        for ((index, path) in plans.take(10).withIndex()) {
            val plan = com.maple.agent.TaskPlanManager.load(path)
            if (plan != null) {
                val status = when (plan.status) {
                    com.maple.agent.PlanStatus.PENDING -> "⏳ 待执行"
                    com.maple.agent.PlanStatus.EXECUTING -> "🔄 执行中"
                    com.maple.agent.PlanStatus.COMPLETED -> "✅ 已完成"
                    com.maple.agent.PlanStatus.FAILED -> "❌ 失败"
                    com.maple.agent.PlanStatus.PAUSED -> "⏸ 已暂停"
                }
                val doneCount = plan.steps.count { it.status == com.maple.agent.StepStatus.DONE }
                context.source.sendSuccess({
                    Component.literal("${index + 1}. $status ${plan.task.take(30)} [$doneCount/${plan.steps.size}] — ${path.fileName}")
                }, false)
            }
        }

        context.source.sendSuccess({
            Component.literal("使用 /ai plan resume <文件名> 恢复执行")
        }, false)

        return Command.SINGLE_SUCCESS
    }

    private fun resumePlan(context: CommandContext<CommandSourceStack>): Int {
        val fileName = StringArgumentType.getString(context, "file")
        val planPath = com.maple.agent.TaskPlanManager.getPlanDir().resolve(fileName)

        if (!java.nio.file.Files.exists(planPath)) {
            context.source.sendFailure(Component.literal("计划文件不存在: $fileName"))
            return 0
        }

        val ctrl = controller ?: run {
            context.source.sendFailure(Component.literal("AgentController 未初始化"))
            return 0
        }

        context.source.sendSuccess({
            Component.literal("正在恢复计划: $fileName")
        }, false)

        // 在协程中执行
        val botName = "plan-resume"
        ctrl.sendCommand(botName, "resume:$planPath") { result ->
            context.source.server.execute {
                context.source.sendSuccess({
                    Component.literal("[计划] $result")
                }, false)
            }
        }

        return Command.SINGLE_SUCCESS
    }

    private fun showConfig(context: CommandContext<CommandSourceStack>): Int {
        val cfg = config ?: run {
            context.source.sendFailure(Component.literal("配置未加载"))
            return 0
        }
        context.source.sendSuccess({
            Component.literal("""
                === 织灵配置 ===
                API URL: ${cfg.apiUrl}
                API Key: ${cfg.apiKey.take(8)}...${cfg.apiKey.takeLast(4)}
                模型: ${cfg.model}
                最大 Token: ${cfg.maxTokens}
                超时: ${cfg.timeout}秒
                历史轮数: ${cfg.maxHistoryTurns}
            """.trimIndent())
        }, false)
        return Command.SINGLE_SUCCESS
    }

    private fun setConfigUrl(context: CommandContext<CommandSourceStack>): Int {
        val value = StringArgumentType.getString(context, "value")
        val cfg = config ?: return 0
        val newConfig = cfg.copy(apiUrl = value)
        newConfig.save()
        updateConfig(newConfig)
        context.source.sendSuccess({
            Component.literal("API URL 已设置为: $value")
        }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun setConfigKey(context: CommandContext<CommandSourceStack>): Int {
        val value = StringArgumentType.getString(context, "value")
        val cfg = config ?: return 0
        val newConfig = cfg.copy(apiKey = value)
        newConfig.save()
        updateConfig(newConfig)
        context.source.sendSuccess({
            Component.literal("API Key 已设置为: ${value.take(8)}...${value.takeLast(4)}")
        }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun setConfigModel(context: CommandContext<CommandSourceStack>): Int {
        val value = StringArgumentType.getString(context, "value")
        val cfg = config ?: return 0
        val newConfig = cfg.copy(model = value)
        newConfig.save()
        updateConfig(newConfig)
        context.source.sendSuccess({
            Component.literal("模型已设置为: $value")
        }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun updateConfig(newConfig: AnimaFabricConfig) {
        config = newConfig
    }
}
