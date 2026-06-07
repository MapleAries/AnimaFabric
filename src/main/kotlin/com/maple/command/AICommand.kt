package com.maple.command

import com.maple.agent.AgentController
import com.maple.config.AnimaFabricConfig
import com.maple.entity.FakePlayerManager
import com.maple.locate.StructureLocator
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.core.BlockPos
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

/**
 * 注册 /anima 指令。
 * 控制 Carpet 生成的假人，不自己生成。
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
                Commands.literal("anima")
                    .requires { hasConfiguredPermission(it) }
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
                    .then(Commands.literal("chat")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                            .executes { chat(it) }
                        )
                    )
                    .then(Commands.literal("locate")
                        .then(Commands.argument("structure", StringArgumentType.greedyString())
                            .executes { locateStructure(it) }
                        )
                    )
                    .then(Commands.literal("killall")
                        .executes { killAllBots(it) }
                    )
                    .then(Commands.literal("plan")
                        .executes { listPlans(it) }
                        .then(Commands.literal("resume")
                            .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("file", StringArgumentType.greedyString())
                                    .executes { resumePlan(it) }
                                )
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
                        .then(Commands.literal("permission")
                            .then(Commands.argument("level", IntegerArgumentType.integer(0, 4))
                                .executes { setConfigPermission(it) }
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

    private fun hasConfiguredPermission(source: CommandSourceStack): Boolean {
        val permissionCheck = when ((config?.requiredPermissionLevel ?: 2).coerceIn(0, 4)) {
            0 -> Commands.LEVEL_ALL
            1 -> Commands.LEVEL_MODERATORS
            2 -> Commands.LEVEL_GAMEMASTERS
            3 -> Commands.LEVEL_ADMINS
            else -> Commands.LEVEL_OWNERS
        }
        return Commands.hasPermission<CommandSourceStack>(permissionCheck).test(source)
    }

    private fun sendCommand(context: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(context, "name")
        val command = StringArgumentType.getString(context, "command")

        val ctrl = controller ?: run {
            context.source.sendFailure(Component.literal("AgentController 未初始化"))
            return 0
        }

        if (!FakePlayerManager.exists(context.source.server, name)) {
            context.source.sendFailure(Component.literal("假人 '$name' 不存在。请先使用 /player <name> spawn 生成假人。"))
            return 0
        }

        // 获取指令发送者（真实玩家）
        val sender = try {
            context.source.playerOrException
        } catch (e: Exception) {
            null
        }

        context.source.sendSuccess({
            Component.literal("[$name] 正在思考...")
        }, false)

        ctrl.sendCommand(name, command, sender) { result ->
            context.source.server.execute {
                context.source.sendSuccess({
                    Component.literal("[$name] $result")
                }, false)
            }
        }

        return Command.SINGLE_SUCCESS
    }

    private fun chat(context: CommandContext<CommandSourceStack>): Int {
        val message = StringArgumentType.getString(context, "message")
        val ctrl = controller ?: run {
            context.source.sendFailure(Component.literal("AgentController 未初始化"))
            return 0
        }

        context.source.sendSuccess({
            Component.literal("[AI] 正在思考...")
        }, false)

        ctrl.chat(message) { result ->
            context.source.server.execute {
                context.source.sendSuccess({
                    Component.literal("[AI] $result")
                }, false)
            }
        }

        return Command.SINGLE_SUCCESS
    }

    private fun locateStructure(context: CommandContext<CommandSourceStack>): Int {
        val raw = StringArgumentType.getString(context, "structure").trim()
        if (raw.isBlank()) {
            context.source.sendFailure(Component.literal("用法：/anima locate <结构名> [搜索半径chunk]"))
            return 0
        }

        val (structure, radius) = parseLocateArgument(raw)
        val source = context.source
        val origin = BlockPos.containing(source.position)
        val result = StructureLocator.locate(source.level, origin, structure, radius)

        source.sendSuccess({
            Component.literal("[locate] $result")
        }, false)

        return Command.SINGLE_SUCCESS
    }

    private fun parseLocateArgument(raw: String): Pair<String, Int> {
        val parts = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
        val maybeRadius = parts.lastOrNull()?.toIntOrNull()
        if (maybeRadius != null && parts.size > 1) {
            return parts.dropLast(1).joinToString(" ") to maybeRadius
        }
        return raw to 100
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
                Component.literal("当前没有假人。请使用 /player <name> spawn 生成假人。")
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
            Component.literal("使用 /anima plan resume <假人名> <文件名> 恢复执行")
        }, false)

        return Command.SINGLE_SUCCESS
    }

    private fun resumePlan(context: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(context, "name")
        val fileName = StringArgumentType.getString(context, "file")
        val planDir = com.maple.agent.TaskPlanManager.getPlanDir()
        val planPath = planDir.resolve(fileName).normalize()

        if (!planPath.startsWith(planDir)) {
            context.source.sendFailure(Component.literal("计划文件路径无效: $fileName"))
            return 0
        }

        if (!java.nio.file.Files.exists(planPath)) {
            context.source.sendFailure(Component.literal("计划文件不存在: $fileName"))
            return 0
        }

        val ctrl = controller ?: run {
            context.source.sendFailure(Component.literal("AgentController 未初始化"))
            return 0
        }

        context.source.sendSuccess({
            Component.literal("[$name] 正在恢复计划: $fileName")
        }, false)

        ctrl.resumePlan(name, planPath) { result ->
            context.source.server.execute {
                context.source.sendSuccess({
                    Component.literal("[$name] $result")
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
                API Key: ${formatApiKey(cfg)}
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

    private fun formatApiKey(cfg: AnimaFabricConfig): String {
        val key = cfg.effectiveApiKey()
        if (key.isBlank()) return "<not set>"

        val masked = when {
            key.length <= 8 -> "****"
            key.length <= 12 -> "${key.take(4)}...${key.takeLast(2)}"
            else -> "${key.take(8)}...${key.takeLast(4)}"
        }

        return if (cfg.isApiKeyFromEnvironment()) {
            "$masked (from ${AnimaFabricConfig.API_KEY_ENV})"
        } else {
            masked
        }
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

    private fun setConfigPermission(context: CommandContext<CommandSourceStack>): Int {
        val value = IntegerArgumentType.getInteger(context, "level")
        val cfg = config ?: return 0
        val newConfig = cfg.copy(requiredPermissionLevel = value)
        newConfig.save()
        updateConfig(newConfig)
        context.source.sendSuccess({
            Component.literal("Anima permission level set to: $value")
        }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun updateConfig(newConfig: AnimaFabricConfig) {
        config = newConfig
        controller?.updateConfig(newConfig)
    }
}
