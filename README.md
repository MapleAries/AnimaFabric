# AnimaFabric (织灵)

[中文文档](README_CN.md)

A Minecraft Fabric mod that brings LLM-powered AI agents into your game. Send natural language commands via `/ai`, and watch AI-controlled bots execute them — mining, building, fighting, pathfinding, and more. Built on [Carpet Mod](https://github.com/gnembon/fabric-carpet) for reliable bot control.

## Features

- **Natural Language Control** — Tell bots what to do in plain language, LLM plans the actions
- **20+ Built-in Tools** — Movement, mining, building, combat, inventory, debug item provisioning, riding, etc.
- **A* Pathfinding** — Auto-navigation with executable movement steps and position feedback
- **Smart Behaviors** — Self-preservation (low health/lava/drowning), auto-combat, unstuck detection
- **World Perception** — Bots understand surroundings: blocks, entities, terrain, crosshair targets
- **Pronoun Resolution** — "我面前的方块" = your crosshair target, "你面前的方块" = bot's crosshair target
- **File-based Task Plans** — Complex tasks decomposed into JSON plans, editable, retryable, and resumable per bot
- **Conversation Memory** — Per-bot chat history with automatic summarization
- **OpenAI-Compatible API** — Works with DeepSeek, OpenAI, or any compatible endpoint

## Prerequisites

- Minecraft 26.1.2
- Java 25+
- [Fabric Loader](https://fabricmc.net/) 0.19.2+
- [Carpet Mod](https://github.com/gnembon/fabric-carpet) (included in `libs/`)

## Setup

1. **Install Fabric** — Follow the [Fabric getting started guide](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up)
2. **Place dependencies** in your `mods/` folder:
   - `fabric-api` 0.150.0+
   - `fabric-language-kotlin`
   - `fabric-carpet` (included in `libs/`)
3. **Build the mod:**
   ```bash
   ./gradlew build
   ```
   Output JAR is in `build/libs/`.
4. **Configure LLM** — Edit `run/config/anima-fabric.json` (auto-created on first run):
   ```json
   {
     "apiUrl": "https://api.deepseek.com/chat/completions",
     "apiKey": "your-api-key",
     "model": "deepseek-v4-flash",
     "maxTokens": 2048,
     "timeout": 300,
     "maxHistoryTurns": 10,
     "maxRetries": 3
   }
   ```

## Usage

### Spawn a Bot (via Carpet)

```
/player Steve spawn
```
Carpet handles bot spawning. Use `/gamemode creative Steve` to switch modes.

### Send Commands

All commands go through LLM for intelligent planning:
```
/ai Steve mine some wood
/ai Steve come to me
/ai Steve dig the block I'm looking at
/ai Steve build a small house at 100 64 200
/ai Steve find diamonds
```

### Pronoun Support

- "我" (I/me) = the player who sent the command
- "你" (you) = the bot

```
/ai Steve 挖我面前的方块    → Uses YOUR crosshair target
/ai Steve 走到我这里来      → Moves to YOUR position
/ai Steve 挖你面前的方块    → Uses BOT's crosshair target
```

### Bot Management

```
/ai list              — List all active bots
/ai stop Steve        — Stop a bot's current action
/ai kill Steve        — Remove a specific bot
/ai killall           — Remove all bots
```

### Task Plans

Complex tasks are decomposed into JSON plan files:
```
/ai plan              — List all plan files
/ai plan resume <bot> <file> — Resume a paused plan for a bot
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
| `moveTo(x, y, z)` | A* pathfind to coordinates; executes reachable movement steps and verifies bot position |
| `move(direction, ticks)` | Directional movement (forward/backward/left/right) |
| `look(direction)` | Look direction (north/south/east/west/up/down/at x y z) |
| `turn(direction)` | Relative turn (left/right/back) |
| `jump()` | Jump (once/continuous/interval) |
| `attack()` | Attack (once/continuous/interval) |
| `use()` | Use item (once/continuous/interval) |
| `sneak()` / `sprint()` | Toggle sneak/sprint |
| `mineBlock(x, y, z)` | Walk to and mine a block; reports failure if the block is not broken |
| `placeBlock(x, y, z, block)` | Prepare the block in main hand, click a support face, and verify placement |
| `craft(item)` | Debug-provision an item with a server command; does not consume recipe ingredients yet |
| `drop(slot)` | Drop items |
| `hotbar(slot)` | Switch hotbar slot |
| `swapHands()` | Swap main/offhand |
| `mount()` / `dismount()` | Mount/dismount entities |
| `getInventory()` | List inventory contents |
| `getHealth()` / `getHunger()` | Check health/hunger |
| `scanArea(radius)` | Scan surrounding blocks |
| `sendMessage(message)` | Send chat message |
| `stop()` | Stop all actions |

## Architecture

```
Player → /ai command → TaskPlanner
                          ├── LLM decomposition → JSON plan file
                          ├── Pronoun resolution (我/你)
                          └── Step-by-step execution
                                ↓
                          ActionExecutor → Carpet /player commands
                                              ↓
                                        Minecraft World
```

- **TaskPlanner** — LLM-based task decomposition, file-based plan management, retry logic
- **ActionExecutor** — Maps tool calls to Carpet `/player` commands, prepares block placement, and verifies action results
- **WorldPerception** — Gathers world state + crosshair targets (bot + sender)
- **BehaviorModes** — Autonomous survival behaviors via Carpet commands
- **AStarPathfinder** — A* with executable movement steps, timeout, mob avoidance
- **BlockClassifier** — Block type classification for pathfinding
- **LLMClient** — Streaming OpenAI-compatible client with thinking extraction
- **TaskPlanManager** — JSON plan persistence, recovery, and progress tracking
- **GameThreadDispatcher** — Keeps Minecraft world and command operations on the server thread
- **ActionResultClassifier** — Centralizes action failure detection across execution paths

## License

[MIT License](LICENSE)
