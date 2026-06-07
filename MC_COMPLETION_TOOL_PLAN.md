# Minecraft Completion Tool Plan

Temporary implementation plan for evolving AnimaFabric from a command executor into an agent that can reliably attempt a full Minecraft completion run.

## Goal

Enable an AI-controlled Carpet fake player to progress through the main completion chain:

1. Gather basic resources.
2. Build tools and food supply.
3. Reach the Nether.
4. Obtain blaze rods and ender pearls.
5. Locate and activate the End portal.
6. Enter the End.
7. Defeat the Ender Dragon.

## Priority 1: Core Survival Tools

These tools unblock common survival actions that are currently too vague when expressed through generic `use()` or simplified `craft()`.

- [x] Add `equipItem(item)`.
- [x] Add `useItem(item)`.
- [x] Add `useItemOnBlock(item, x, y, z)`.
- [x] Add `eatFood()`.
- [ ] Add `hasItem(item, count)`.
- [ ] Add `ensureItem(item, count)`.
- [ ] Add `craftRecipe(item, count)`.
- [ ] Add `smeltItem(input, output, count)`.

Expected benefit:

- The LLM can ask for concrete inventory operations.
- Food, tools, flint and steel, eyes of ender, and blocks can be handled without relying on fragile generic actions.

## Priority 2: World Scanning Tools

These tools reduce hallucinated coordinates and make local tactical planning more reliable.

- [x] Add `findNearbyBlock(block, radius)`.
- [x] Add `findPortalFrame(radius)`.
- [ ] Add `findChest(radius)`.
- [ ] Add `findSpawner(radius)`.
- [ ] Add `findObsidianPillarCrystal()`.
- [ ] Return structured scan data from `WorldPerception`.

Structured perception should include:

- Dimension.
- Position.
- Health and hunger.
- Equipment.
- Inventory summary.
- Nearby useful blocks.
- Nearby dangerous entities.
- Crosshair target.
- Current biome if available.

Expected benefit:

- The planner can ground decisions in actual world state.
- The agent can recover when a target block or structure is not where expected.

## Priority 3: Nether Portal Tools

These are the first major milestone tools for the completion route.

- [x] Add `buildNetherPortal(x, y, z)`.
- [x] Add `ignitePortal(x, y, z)`.
- [x] Add `enterPortal()`.
- [ ] Add checks for required obsidian and flint and steel.
- [ ] Add fallback behavior when the portal frame is incomplete.

Expected benefit:

- The AI can reliably transition from overworld progression into Nether progression.
- The LLM no longer needs to manually compose a portal from many `placeBlock` calls.

## Priority 4: Stronghold And End Portal Tools

These tools handle the hard transition from stronghold discovery to End entry.

- [ ] Add `findEndPortalRoom()`.
- [ ] Add `fillEndPortal()`.
- [ ] Add `enterEndPortal()`.
- [ ] Add portal frame validation.
- [ ] Add missing-eye count reporting.

Expected benefit:

- `locateStructure(stronghold, radius)` becomes actionable.
- The agent can move from "found the stronghold" to "entered the End" with fewer brittle assumptions.

## Priority 5: Combat Tools

These tools are needed for Nether fortress combat and the Ender Dragon fight.

- [ ] Add `lookAtEntity(type)`.
- [ ] Add `attackEntity(type)`.
- [ ] Add `avoidEntity(type, radius)`.
- [ ] Add `keepDistanceFrom(type, distance)`.
- [ ] Add `shootEntity(type)`.
- [ ] Add `shootBlock(x, y, z)`.
- [ ] Add `retreatToSafeSpot()`.
- [ ] Add `healIfNeeded()`.
- [ ] Add `placeBlockUnderSelf(block)`.

Expected benefit:

- Blaze, Enderman, and Ender Dragon encounters can be handled as explicit tactical actions.
- The agent can recover from low health or dangerous positioning.

## Priority 6: Completion Stage Machine

Instead of asking the LLM to plan "beat Minecraft" from scratch, add a built-in stage machine and let the LLM solve local decisions inside each stage.

Suggested stages:

- [ ] Wood and basic tools.
- [ ] Stone tools.
- [ ] Food and safety.
- [ ] Iron tools and bucket.
- [ ] Diamonds or obsidian.
- [ ] Nether portal.
- [ ] Nether fortress.
- [ ] Blaze rods.
- [ ] Ender pearls and eyes of ender.
- [ ] Stronghold.
- [ ] End portal.
- [ ] Ender Dragon fight.

Each stage should define:

- Required items.
- Required tools.
- Success condition.
- Failure signals.
- Recommended recovery actions.

Expected benefit:

- Better long-horizon reliability.
- Smaller prompts and easier retries.
- More predictable behavior than a single giant free-form plan.

## Priority 7: Tool Preconditions And Recovery Hints

Each tool should expose simple metadata the planner can use.

- [ ] Required items.
- [ ] Required distance or line of sight.
- [ ] Required dimension.
- [ ] Common failure modes.
- [ ] Suggested recovery tool calls.

Examples:

- `mineBlock(x,y,z)` failure because distance is too large should suggest `moveTo(adjacentPosition)`.
- `ignitePortal(x,y,z)` failure because flint and steel is missing should suggest `ensureItem(flint_and_steel,1)`.
- `fillEndPortal()` failure because eyes are missing should suggest `ensureItem(ender_eye, missingCount)`.

## Suggested Next Implementation Slice

Start with the smallest set that makes the simulated completion route less fragile:

1. Implement `equipItem`, `useItemOnBlock`, and `eatFood`.
2. Implement `findNearbyBlock` and `findPortalFrame`.
3. Implement `buildNetherPortal`, `ignitePortal`, and `enterPortal`.

After that slice, run another paper simulation focused only on reaching the Nether without server-side shortcuts.
