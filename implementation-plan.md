# 去 Carpet 化：自建 FakePlayer + Mixin 控制方案

## 目标
移除对 Carpet mod 的依赖，利用已有的 `FakePlayer`/`FakeClientConnection`/`ActionPack` 代码（目前是死代码），通过 Mixin 劫持 `ServerPlayer` tick 逻辑，实现直接控制 AI 假人。

## 核心思路
项目已有完整的 FakePlayer 体系但从未启用。所有控制逻辑都通过 Carpet 的 `/player` 命令字符串间接执行。本次重构就是将这些死代码激活，并将 `executeCarpetCommand()` 替换为直接操作 `ActionPack` 和 `ServerPlayer` API。

---

## 步骤

### 1. 移除 Carpet 依赖
**文件：** `build.gradle.kts`
- 删除 Carpet maven 仓库（第 16-19 行）
- 删除 Carpet JAR 依赖（第 50 行）

**文件/目录：** `libs/fabric-carpet-26.1+v260402.jar`
- 删除整个 `libs/` 目录

### 2. 创建 Mixin：ServerPlayerTickMixin
**新建文件：** `src/main/java/com/maple/mixin/ServerPlayerTickMixin.java`
- Mixin 目标：`net.minecraft.server.level.ServerPlayer`
- 注入点：`tick()` 方法的 HEAD
- 逻辑：检查 `this` 是否为 `FakePlayer` 实例，如果是则调用 `actionPack.onUpdate(this)` 驱动行为状态机
- 这样每个 tick（50ms = 20 TPS）都会驱动 AI 行为

**注册：** `src/main/resources/anima-fabric.mixins.json`
- 在 `mixins` 数组中添加 `"ServerPlayerTickMixin"`

### 3. 重写 FakePlayerManager — 直接管理 FakePlayer 实例
**文件：** `src/main/kotlin/com/maple/entity/FakePlayerManager.kt`

改为内部维护一个 `ConcurrentHashMap<String, FakePlayer>` 来追踪所有 AI 假人：
- `spawn(server, name, x, y, z)` — 调用 `FakePlayer.create()` 生成假人并注册到 map
- `getBot(server, name)` — 先查内部 map，再 fallback 到 `server.playerList`
- `kill(server, name)` — 从 map 中取出，调用 `FakePlayer.remove()`，从 map 中移除
- `killAll(server)` — 遍历 map 逐个移除
- `listNames(server)` — 返回 map 中所有名称
- `exists(server, name)` — 检查 map 中是否存在

不再依赖 Carpet 的 `/player` 命令来 spawn/kill。

### 4. 改造 ActionPack — 添加高层控制接口
**文件：** `src/main/kotlin/com/maple/agent/ActionPack.kt`

在现有基础上添加高层方法，供 ActionExecutor 等直接调用：
- `setMovement(forward: Float, strafing: Float)` — 设置移动输入
- `startContinuousAction(type: ActionType)` — 开始持续动作（攻击/使用）
- `stopContinuousAction(type: ActionType)` — 停止持续动作
- `lookAt(yaw: Float, pitch: Float)` — 设置视角（直接设置 player.yRot/xRot）
- `lookAtBlock(pos: BlockPos)` — 看向指定方块坐标
- `turnLeft() / turnRight() / turnBack()` — 相对转向

### 5. 改造 ActionExecutor — 直接操作 ActionPack
**文件：** `src/main/kotlin/com/maple/agent/ActionExecutor.kt`

核心变更：删除 `executeCarpetCommand()` 方法，所有操作改为直接操作 `FakePlayer` 的 `ActionPack`。

需要增加一个获取 FakePlayer 引用的机制（通过 FakePlayerManager）。

各方法改造：
- `executeMoveTo` — 计算路径（激活 A*），通过 ActionPack.setMovement 驱动移动
- `executeMove` — 通过 ActionPack.setMovement 设置方向和持续时间
- `executeLook` — 通过 ActionPack.lookAt 直接设置视角
- `executeTurn` — 通过 ActionPack.turnLeft/Right/Back
- `executeJump` — 通过 ActionPack 添加 JUMP 动作
- `executeAttack` — 通过 ActionPack.startContinuousAction(ATTACK)
- `executeUse` — 通过 ActionPack.startContinuousAction(USE)
- `executeMineBlock` — 看向方块 + ActionPack 挖掘状态机（已有实现）
- `executePlaceBlock` — 看向位置 + use
- `executeStop` — 通过 ActionPack.stopAll()
- 查询类（getInventory/getHealth/getHunger/scanArea）— 不变，已经是直接 API 调用

### 6. 改造 SimpleCommandExecutor — 同样直操作 ActionPack
**文件：** `src/main/kotlin/com/maple/agent/SimpleCommandExecutor.kt`

与 ActionExecutor 相同的改造思路，删除 `executeCarpetCommand()`，改为通过 FakePlayerManager 获取 FakePlayer 后直接操作。

### 7. 改造 BehaviorModes — 直接操作 ActionPack
**文件：** `src/main/kotlin/com/maple/agent/BehaviorModes.kt`

删除 `executeCarpetCommand()`，改为直接操作 FakePlayer 的 ActionPack：
- `checkSelfPreservation` — 通过 ActionPack.setMovement + jump
- `checkUnstuck` — 通过 ActionPack.jump + setMovement
- `checkSelfDefense` — 通过 ActionPack.lookAt + attack
- `checkItemCollecting` — 通过 ActionPack.lookAt + setMovement

### 8. 改造 AgentController — 使用新 FakePlayerManager
**文件：** `src/main/kotlin/com/maple/agent/AgentController.kt`

- `sendCommand` — 不再检查 Carpet 假人，改用 FakePlayerManager.getBot
- `stop` — 通过 FakePlayer 的 ActionPack.stopAll()
- `kill` — 通过 FakePlayerManager.kill()
- 错误消息更新：不再提示 "/player spawn"

### 9. 改造 AICommand — 支持 /ai spawn
**文件：** `src/main/kotlin/com/maple/command/AiCommand.kt`

新增 `/ai spawn <name>` 子命令，调用 FakePlayerManager.spawn()，不再需要用户手动执行 Carpet 的 `/player spawn`。

### 10. 激活寻路系统
**文件：** `src/main/kotlin/com/maple/agent/ActionExecutor.kt`

`executeMoveTo` 当前是简化实现（算角度 + move forward + 延时）。改为：
1. 调用 `AStarPathfinder.findPath()` 计算路径
2. 将路径传给 `PathFollower`
3. PathFollower 每 tick 驱动移动（通过 ActionPack.setMovement）

### 11. 更新 README
**文件：** `README.md`, `README_CN.md`
- 移除 Carpet 相关说明
- 更新安装步骤（不再需要 Carpet JAR）
- 更新使用方法（`/ai spawn` 替代 `/player spawn`）
- 更新架构图

### 12. 清理
- 删除 `FakePlayer.kt` 中的 Carpet 相关注释
- 删除 `ActionPack.kt` 中的 Carpet 相关注释
- 清理 `build.gradle.kts` 中无用的 Carpet maven 仓库引用
- 删除 `run/config/carpet/` 目录（如果存在）

---

## 关键文件变更清单

| 文件 | 操作 |
|------|------|
| `build.gradle.kts` | 编辑：移除 Carpet 依赖 |
| `libs/` | 删除整个目录 |
| `src/main/java/com/maple/mixin/ServerPlayerTickMixin.java` | **新建** |
| `src/main/resources/anima-fabric.mixins.json` | 编辑：注册 mixin |
| `src/main/kotlin/com/maple/entity/FakePlayerManager.kt` | 重写：直接管理 FakePlayer |
| `src/main/kotlin/com/maple/entity/FakePlayer.kt` | 小改：清理注释 |
| `src/main/kotlin/com/maple/agent/ActionPack.kt` | 编辑：添加高层控制接口 |
| `src/main/kotlin/com/maple/agent/ActionExecutor.kt` | 重写：直接操作 ActionPack |
| `src/main/kotlin/com/maple/agent/SimpleCommandExecutor.kt` | 重写：直接操作 ActionPack |
| `src/main/kotlin/com/maple/agent/BehaviorModes.kt` | 重写：直接操作 ActionPack |
| `src/main/kotlin/com/maple/agent/AgentController.kt` | 编辑：使用新 FakePlayerManager |
| `src/main/kotlin/com/maple/command/AiCommand.kt` | 编辑：添加 /ai spawn |
| `README.md` | 更新：移除 Carpet 说明 |
| `README_CN.md` | 更新：移除 Carpet 说明 |
