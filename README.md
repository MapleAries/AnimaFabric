# AnimaFabric (织灵)

[中文文档](README_CN.md)

A Minecraft Fabric mod that brings LLM-powered AI agents into your game. Send natural language commands via `/ai`, and watch AI-controlled bots execute them — mining, building, fighting, pathfinding, and more. **No external dependencies** — uses Mixin to directly control fake players at 20 TPS.

## Features

- **Natural Language Control** — Tell bots what to do in plain language, LLM plans the actions
- **15 Built-in Tools** — Movement, mining, building, combat, inventory queries, area scanning, etc.
- **A* Pathfinding** — Auto-navigation avoiding obstacles and hazards
- **Smart Behaviors** — Self-preservation (low health/lava/drowning), auto-combat, unstuck detection
- **World Perception** — Bots understand their surroundings: blocks, entities, terrain, time of day
- **Conversation Memory** — Per-bot chat history with automatic summarization
- **OpenAI-Compatible API** — Works with DeepSeek, OpenAI, or any compatible endpoint
- **Zero External Mod Dependencies** — Self-contained, no Carpet or other mods required

## Prerequisites

- Minecraft 26.1.2
- Java 25+
- [Fabric Loader](https://fabricmc.net/) 0.19.2+

## Setup

1. **Install Fabric** — Follow the [Fabric getting started guide](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up)
2. **Place dependencies** in your `mods/` folder:
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

```
/ai spawn Steve
```
This creates a fake player `[AI] Steve` at your current position.

### Send Commands

```
/ai Steve mine some wood
/ai Steve come to me
/ai Steve build a small house at 100 64 200
/ai Steve find diamonds
/ai Steve attack any hostile mobs nearby
```

### Simple Direct Commands

These skip the LLM and execute immediately:
```
/ai Steve forward 10
/ai Steve turn left
/ai Steve jump
/ai Steve inventory
/ai Steve health
/ai Steve scan 8
/ai Steve stop
```

### Bot Management

```
/ai list              — List all active bots
/ai stop Steve        — Stop a bot's current action
/ai kill Steve        — Remove a specific bot
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
                          ├── Simple → ActionPack (direct control)
                          └── Complex → LLM Planner → ActionExecutor → ActionPack
                                                                          ↓
                                                                    FakePlayer.tick()
                                                                          ↓
                                                                    Minecraft World
```

- **FakePlayer** — Custom `ServerPlayer` subclass with `FakeClientConnection` (EmbeddedChannel)
- **FakePlayerManager** — Spawns, tracks, and removes FakePlayer instances
- **ActionPack** — Tick-driven action state machine (movement, attack, use, jump, mining)
- **CommandRouter** — Classifies commands as simple (direct) or complex (LLM)
- **LLM Planner** — Streaming OpenAI-compatible client, returns tool calls
- **ActionExecutor** — Maps tool calls to ActionPack operations
- **WorldPerception** — Gathers world state (position, blocks, entities, inventory)
- **BehaviorModes** — Autonomous survival behaviors (flee, fight, unstuck, collect)
- **Pathfinding** — A* with slope handling and hazard avoidance, driven by PathFollower

## License

[MIT License](LICENSE)
