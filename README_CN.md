# 织灵 (AnimaFabric)

[English](README.md)

一个 Minecraft Fabric Mod，让大语言模型（LLM）驱动的 AI 智能体进入你的游戏。通过 `/anima` 命令发送自然语言指令，AI 假人会自动执行——挖矿、建造、战斗、寻路，样样都行。基于 [Carpet Mod](https://github.com/gnembon/fabric-carpet) 实现可靠的假人控制。

## 特性

- **自然语言控制** — 用日常语言告诉假人该做什么，LLM 自动规划行动
- **20+ 内置工具** — 移动、挖掘、建造、战斗、背包、调试物品给予、骑乘等
- **A* 寻路** — 自动导航，按可执行移动步骤行走，并用位置反馈确认到位
- **智能行为** — 自我保护（低血量/岩浆/溺水）、自动反击、卡住脱困
- **世界感知** — 假人理解周围环境：方块、实体、地形、准星目标
- **文件化任务计划** — 复杂任务分解为 JSON 计划文件，可编辑、可重试、可按假人恢复
- **对话记忆** — 每个假人独立的聊天历史，超长自动摘要
- **兼容 OpenAI API** — 支持 DeepSeek、OpenAI 或任意兼容接口

## 环境要求

- Minecraft 26.1.2
- Java 25+
- [Fabric Loader](https://fabricmc.net/) 0.19.2+
- [Carpet Mod](https://github.com/gnembon/fabric-carpet)（已包含在 `libs/` 中）

## 安装

1. **安装 Fabric** — 参考 [Fabric 开发文档](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up)
2. **放置依赖** 到 `mods/` 文件夹：
   - `fabric-api` 0.150.0+
   - `fabric-language-kotlin`
   - `fabric-carpet`（已包含在 `libs/` 中）
3. **构建 Mod：**
   ```bash
   ./gradlew build
   ```
   输出 JAR 位于 `build/libs/`。
4. **配置 LLM** — 编辑 `run/config/anima-fabric.json`（首次运行自动生成）：
   ```json
   {
     "apiUrl": "https://api.deepseek.com/chat/completions",
     "apiKey": "你的API密钥",
     "model": "deepseek-v4-flash",
     "maxTokens": 2048,
     "timeout": 300,
     "maxHistoryTurns": 10,
     "maxRetries": 3
   }
   ```

## 使用方法

### 生成假人（通过 Carpet）

```
/player Steve spawn
```
Carpet 负责假人生成。用 `/gamemode creative Steve` 切换游戏模式。

### 发送指令

所有指令都经过 LLM 智能规划：
```
/anima Steve 去砍点木头
/anima Steve 到 100 64 200
/anima Steve 挖附近的石头
/anima Steve 在 100 64 200 建一个小房子
/anima Steve 找钻石
```

### 与 AI 聊天

使用 `/anima chat` 可以直接和大模型对话。它会读取最近活跃假人的记忆，因此可以追问刚才任务里的信息。

```
/anima Steve 在 100 64 200 放置一个工作台
/anima chat 工作台放在哪了
```

### 假人管理

```
/anima list              — 列出所有活跃假人
/anima stop Steve        — 停止假人当前动作
/anima kill Steve        — 移除指定假人
/anima killall           — 移除所有假人
```

### 结构定位

既可以用自然语言让假人寻找，也可以从玩家当前位置直接查询：

```
/anima Steve 找最近的村庄
/anima locate village
/anima locate ancient_city 200
/anima locate #minecraft:village
```

### 任务计划

复杂任务会自动分解为 JSON 计划文件：
```
/anima plan              — 列出所有计划文件
/anima plan resume <假人名> <文件名> — 为指定假人恢复执行暂停的计划
```

### 运行时配置

```
/anima config show                — 显示当前配置
/anima config url <url>           — 修改 API 地址
/anima config key <key>           — 修改 API 密钥
/anima config model <model>       — 修改模型名称
```

## 工具列表

| 工具 | 说明 |
|------|------|
| `moveTo(x, y, z)` | A* 寻路移动到指定坐标，按可执行移动步骤行走并验证位置 |
| `move(direction, ticks)` | 方向移动（forward/backward/left/right） |
| `look(direction)` | 看向（north/south/east/west/up/down/at x y z） |
| `turn(direction)` | 相对转向（left/right/back） |
| `jump()` | 跳跃（once/continuous/interval） |
| `attack()` | 攻击（once/continuous/interval） |
| `use()` | 使用物品（once/continuous/interval） |
| `sneak()` / `sprint()` | 切换潜行/冲刺 |
| `mineBlock(x, y, z)` | 走向并挖掘方块；方块未破坏会返回失败 |
| `placeBlock(x, y, z, block)` | 自动把方块准备到主手，点击支撑面，并验证放置结果 |
| `craft(item)` | 调试模式给予物品；当前不会消耗材料进行真实配方合成 |
| `drop(slot)` | 丢出物品 |
| `hotbar(slot)` | 切换快捷栏 |
| `swapHands()` | 交换主副手 |
| `mount()` / `dismount()` | 骑乘/下马 |
| `getInventory()` | 查看背包 |
| `getHealth()` / `getHunger()` | 查看血量/饥饿值 |
| `scanArea(radius)` | 扫描周围方块 |
| `locateStructure(structure, radius)` | 定位最近的已注册结构或结构标签，如 `village`、`ancient_city`、`minecraft:mansion`、`#minecraft:village` |
| `sendMessage(message)` | 发送聊天消息 |
| `stop()` | 停止所有动作 |

## 架构

```
玩家 → /anima 指令 → TaskPlanner（任务规划器）
                      ├── LLM 分解 → JSON 计划文件
                      └── 逐步执行
                            ↓
                      ActionExecutor → Carpet /player 命令
                                          ↓
                                    Minecraft 世界
```

- **TaskPlanner** — LLM 任务分解、文件化计划管理、重试逻辑
- **ActionExecutor** — 将工具调用映射为 Carpet `/player` 命令，准备方块放置并验证动作结果
- **WorldPerception** — 采集世界状态 + 准星目标（假人 + 发送者）
- **BehaviorModes** — 通过 Carpet 命令实现自主生存行为
- **AStarPathfinder** — 带可执行移动步骤的 A* 寻路、超时机制、生物回避
- **BlockClassifier** — 方块类型分类系统
- **LLMClient** — 流式 OpenAI 兼容客户端，支持思考内容提取
- **TaskPlanManager** — JSON 计划持久化、恢复和进度追踪
- **GameThreadDispatcher** — 确保 Minecraft 世界访问和命令执行回到服务器线程
- **ActionResultClassifier** — 统一各执行路径的动作失败判定

## 许可证

[MIT License](LICENSE)
