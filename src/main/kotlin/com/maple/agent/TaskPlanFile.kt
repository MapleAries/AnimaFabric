package com.maple.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 文件化任务计划系统。
 *
 * 将 LLM 生成的任务计划写入 JSON 文件，支持：
 * - 人工审查和编辑计划
 * - 逐步执行并实时更新进度
 * - 崩溃恢复（从文件读取未完成的计划）
 */
@Serializable
data class TaskPlan(
    val task: String,
    val created: String = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
    var status: PlanStatus = PlanStatus.PENDING,
    var currentStep: Int = 0,
    val steps: MutableList<PlanStep>
)

@Serializable
data class PlanStep(
    val id: Int,
    val description: String,
    val command: String,
    var status: StepStatus = StepStatus.PENDING,
    var result: String = ""
)

@Serializable
enum class PlanStatus {
    PENDING, EXECUTING, COMPLETED, FAILED, PAUSED
}

@Serializable
enum class StepStatus {
    PENDING, EXECUTING, DONE, FAILED, SKIPPED
}

object TaskPlanManager {

    private val PLAN_DIR: Path = FabricLoader.getInstance().configDir.resolve("anima-fabric-plans")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    init {
        Files.createDirectories(PLAN_DIR)
    }

    /**
     * 保存任务计划到文件。
     * @return 文件路径
     */
    fun save(plan: TaskPlan): Path {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val safeName = plan.task.take(20).replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fff]"), "_")
        val filename = "plan_${timestamp}_$safeName.json"
        val path = PLAN_DIR.resolve(filename)
        Files.writeString(path, json.encodeToString(plan))
        println("[AnimaFabric] 任务计划已保存: $path")
        return path
    }

    /**
     * 更新计划文件（实时更新进度）。
     */
    fun update(plan: TaskPlan, path: Path) {
        try {
            Files.writeString(path, json.encodeToString(plan))
        } catch (e: Exception) {
            println("[AnimaFabric] 更新计划文件失败: ${e.message}")
        }
    }

    /**
     * 从文件加载任务计划。
     */
    fun load(path: Path): TaskPlan? {
        return try {
            if (Files.exists(path)) {
                json.decodeFromString<TaskPlan>(Files.readString(path))
            } else null
        } catch (e: Exception) {
            println("[AnimaFabric] 加载任务计划失败: ${e.message}")
            null
        }
    }

    /**
     * 获取最新的未完成计划。
     */
    fun getLatestPending(): Pair<TaskPlan, Path>? {
        val files = listPlanFiles()
        for (file in files) {
            val plan = load(file) ?: continue
            if (plan.status == PlanStatus.PENDING || plan.status == PlanStatus.PAUSED) {
                return plan to file
            }
        }
        return null
    }

    /**
     * 获取最新的执行中计划。
     */
    fun getLatestExecuting(): Pair<TaskPlan, Path>? {
        val files = listPlanFiles()
        for (file in files) {
            val plan = load(file) ?: continue
            if (plan.status == PlanStatus.EXECUTING) {
                return plan to file
            }
        }
        return null
    }

    /**
     * 列出所有计划文件（按时间倒序）。
     */
    fun listPlanFiles(): List<Path> {
        return try {
            Files.list(PLAN_DIR)
                .filter { it.toString().endsWith(".json") }
                .sorted { a, b ->
                    Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a))
                }
                .toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 获取计划目录路径。
     */
    fun getPlanDir(): Path = PLAN_DIR

    /**
     * 从 TaskPlanner 的步骤创建 TaskPlan。
     */
    fun createPlan(task: String, steps: List<TaskStep>): TaskPlan {
        return TaskPlan(
            task = task,
            steps = steps.mapIndexed { index, step ->
                PlanStep(
                    id = index + 1,
                    description = step.description,
                    command = step.command
                )
            }.toMutableList()
        )
    }
}
