# 织灵 (AnimaFabric)

[English](README.md)

一个 Minecraft Fabric Mod，让大语言模型（LLM）驱动的 AI 智能体进入你的游戏。通过 `/ai` 命令发送自然语言指令，AI 假人会自动执行——挖矿、建造、战斗、寻路，样样都行。**无外部依赖**——通过 Mixin 直接控制假人，达到 20 TPS 的同步精度。

## 特性

- **自然语言控制** — 用日常语言告诉假人该做什么，LLM 自动规划行动
- **15 个内置工具** — 移动、挖掘、建造、战斗、查看背包、扫描环境等
- **A* 寻路** — 自动导航，避开障碍物和危险地形
- **智能行为** — 自我保护（低血量/岩浆/溺水）、自动反击、卡住脱困
- **世界感知** — 假人能理解周围环境：方块、实体、地形、昼夜时间
- **对话记忆** — 每个假人独立的聊天历史，超长自动摘要
- **兼容 OpenAI API** — 支持 DeepSeek、OpenAI 或任意兼容接口
- **零外部 Mod 依赖** — 自包含，不需要 Carpet 或其他 Mod

## 环境要求

- Minecraft 26.1.2
- Java 25+
- [Fabric Loader](https://fabricmc.net/) 0.19.2+

## 安装

1. **安装 Fabric** — 参考 [Fabric 开发文档](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up)
2. **放置依赖** 到 `mods/` 文件夹：
   - `fabric-api` 0.150.0+
   - `fabric-language-kotlin`
3. **构建 Mod：**
   ```bash
   ./gradlew build
   ```
   输出 JAR 位于 `build/libs/`。
4. **配置 LLM** — 编辑 `run/config/anima-fabric.json`（首次运行自动生成）：
   ```json
   {
     "apiUrl": "https://api.deepseek.com/v1/chat/completions",
     "apiKey": "你的API密钥",
     "model": "deepseek-v4-flash",
     "maxTokens": 2048,
     "timeout": 120,
     "maxHistoryTurns": 10
   }
   ```

## 使用方法

### 生成假人

```
/ai spawn Steve
```
会在你当前位置生成一个名为 `[AI] Steve` 的假人。

### 发送指令

```
/ai Steve 去砍点木头
/ai Steve 到我这里来
/ai Steve 在 100 64 200 建一个小房子
/ai Steve 找钻石
/ai Steve 攻击附近的敌对生物
```

### 简单直发指令

这些指令不经过 LLM，直接执行：
```
/ai Steve forward 10
/ai Steve turn left
/ai Steve jump
/ai Steve inventory
/ai Steve health
/ai Steve scan 8
/ai Steve stop
```

### 假人管理

```
/ai list              — 列出所有活跃假人
/ai stop Steve        — 停止假人当前动作
/ai kill Steve        — 移除指定假人
/ai killall           — 移除所有假人
```

### 运行时配置

```
/ai config show                — 显示当前配置
/ai config url <url>           — 修改 API 地址
/ai config key <key>           — 修改 API 密钥
/ai config model <model>       — 修改模型名称
```

## 工具列表

| 工具 | 说明 |
|------|------|
| `moveTo(x, y, z)` | A* 寻路移动到指定坐标 |
| `move(direction, ticks)` | 短距离方向移动 |
| `look(yaw, pitch)` | 设置视角朝向 |
| `turn(direction)` | 相对转向（left/right/back） |
| `jump()` | 跳跃 |
| `attack()` | 攻击视线内的实体 |
| `use()` | 使用主手物品 |
| `mineBlock(x, y, z)` | 走向并挖掘指定方块 |
| `placeBlock(x, y, z, block)` | 放置方块 |
| `getInventory()` | 查看背包内容 |
| `getHealth()` | 查看血量 |
| `getHunger()` | 查看饥饿值 |
| `scanArea(radius)` | 扫描周围方块 |
| `sendMessage(message)` | 发送聊天消息 |
| `stop()` | 停止所有动作 |

## 架构

```
玩家 → /ai 指令 → CommandRouter（指令路由）
                      ├── 简单指令 → ActionPack（直接控制）
                      └── 复杂指令 → LLM 规划器 → ActionExecutor → ActionPack
                                                                        ↓
                                                                  FakePlayer.tick()
                                                                        ↓
                                                                  Minecraft 世界
```

- **FakePlayer** — 自定义 `ServerPlayer` 子类，使用 `FakeClientConnection`（EmbeddedChannel）
- **FakePlayerManager** — 生成、追踪、移除 FakePlayer 实例
- **ActionPack** — Tick 驱动的行为状态机（移动、攻击、使用、跳跃、挖掘）
- **CommandRouter** — 将指令分为简单（直发）和复杂（走 LLM）两类
- **LLM Planner** — 流式 OpenAI 兼容客户端，返回工具调用
- **ActionExecutor** — 将工具调用映射为 ActionPack 操作
- **WorldPerception** — 采集世界状态（位置、方块、实体、背包）
- **BehaviorModes** — 自主生存行为（逃跑、反击、脱困、捡物）
- **Pathfinding** — A* 寻路，支持斜坡和危险规避，由 PathFollower 驱动

## 许可证

CC0 1.0 Universal — 自由学习和借鉴，欢迎用于你自己的项目。
