# AnimaFabric (织灵)

[中文文档](README_CN.md)

A Minecraft Fabric mod that brings LLM-powered AI agents into your game. Send natural language commands via `/ai`, and watch AI-controlled bots execute them — mining, building, fighting, pathfinding, and more.

## Features

- **Natural Language Control** — Tell bots what to do in plain language, LLM plans the actions
- **15 Built-in Tools** — Movement, mining, building, combat, inventory queries, area scanning, etc.
- **A* Pathfinding** — Auto-navigation avoiding obstacles and hazards
- **Smart Behaviors** — Self-preservation (low health/lava/drowning), auto-combat, unstuck detection
- **World Perception** — Bots understand their surroundings: blocks, entities, terrain, time of day
- **Conversation Memory** — Per-bot chat history with automatic summarization
- **OpenAI-Compatible API** — Works with DeepSeek, OpenAI, or any compatible endpoint

## Prerequisites

- Minecraft 26.1.2
- Java 25+
- [Fabric Loader](https://fabricmc.net/) 0.19.2+

## Setup

1. **Install Fabric** — Follow the [Fabric getting started guide](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up)
2. **Place dependencies** in your `mods/` folder:
   - `fabric-carpet-26.1+v260402.jar` (included in `libs/`)
   - `fabric-api` 0.150.0+
   - `fabric-language-kotlin`
3. **Build the mod:**
   ```bash
   ./gradlew build
   ```
   Output JAR is in `build/libs/`.
4. **Configure LLM** — Edit `run/config/anima-fabric.json` (auto-created on first run):
   ```json
   {
     "apiUrl": "https://api.deepseek.com/v1/chat/completions",
     "apiKey": "your-api-key",
     "model": "deepseek-v4-flash",
     "maxTokens": 2048,
     "timeout": 120,
     "maxHistoryTurns": 10
   }
   ```

## Usage

### Spawn a Bot

Use Carpet's `/player` command:
```
/player [AI]Steve spawn
```
Bots with `[AI]` in their name are automatically detected by AnimaFabric.

### Send Commands

```
/ai [AI]Steve mine some wood
/ai [AI]Steve come to me
/ai [AI]Steve build a small house at 100 64 200
/ai [AI]Steve find diamonds
/ai [AI]Steve attack any hostile mobs nearby
```

### Simple Direct Commands

These skip the LLM and execute immediately:
```
/ai [AI]Steve forward 10
/ai [AI]Steve turn left
/ai [AI]Steve jump
/ai [AI]Steve inventory
/ai [AI]Steve health
/ai [AI]Steve scan 8
/ai [AI]Steve stop
```

### Bot Management

```
/ai list              — List all active bots
/ai stop [AI]Steve    — Stop a bot's current action
/ai kill [AI]Steve    — Remove a specific bot
/ai killall           — Remove all bots
```

### Runtime Config

```
/ai config show                — Display current config
/ai config url <url>           — Change API endpoint
/ai config key <key>           — Change API key
/ai config model <model>       — Change model name
```

## Available Tools

| Tool | Description |
|------|-------------|
| `moveTo(x, y, z)` | A* pathfind to coordinates |
| `move(direction, ticks)` | Short-distance directional movement |
| `look(yaw, pitch)` | Set view direction |
| `turn(direction)` | Relative turn (left/right/back) |
| `jump()` | Jump |
| `attack()` | Attack entity in line of sight |
| `use()` | Use item in hand |
| `mineBlock(x, y, z)` | Walk to and mine a block |
| `placeBlock(x, y, z, block)` | Place a block |
| `getInventory()` | List inventory contents |
| `getHealth()` | Check health |
| `getHunger()` | Check hunger level |
| `scanArea(radius)` | Scan surrounding blocks |
| `sendMessage(message)` | Send chat message |
| `stop()` | Stop all actions |

## Architecture

```
Player → /ai command → CommandRouter
                          ├── Simple → Direct Carpet command
                          └── Complex → LLM Planner → ToolExecutor → Carpet /player
                                                                        ↓
                                                                  Minecraft World
```

- **Carpet Mod** — Bot spawning and low-level control via `/player` commands
- **CommandRouter** — Classifies commands as simple (direct) or complex (LLM)
- **LLM Planner** — Streaming OpenAI-compatible client, returns tool calls
- **ToolExecutor** — Maps tool calls to Carpet actions with safety checks
- **WorldPerception** — Gathers world state (position, blocks, entities, inventory)
- **BehaviorModes** — Autonomous survival behaviors (flee, fight, unstuck, collect)
- **Pathfinding** — A* with slope handling and hazard avoidance

## License

CC0 1.0 Universal — feel free to learn from it and incorporate it in your own projects.
