# Vire — implementation plan

> Name: **Vire** (Virtual Redstone / Virtual Wire). Mod id `vire`, package `dev.vire`, command
> `/vire`, toggle key `V`. The Modrinth slugs `vire`, `vire-mod` and `virtual-redstone` were free
> on 2026-11-11.

A client-side Fabric mod. A survival player presses one key to enter **Vire Mode**: a private,
client-only sandbox over a region of the world they are standing in. They fly a free camera,
place and break any block, and run the circuit with **exact vanilla redstone**, with pause,
single-step and speed control. The real server never sees any of it. On exit, the design becomes
a **ghost overlay** in the real world plus a **bill of materials**, or a `.litematic` file for
Litematica and printer mods.

Source of the idea: a Gemini brainstorm spec (2026-11-10). Section 21 lists what this plan
changes from that spec and why.

---

## 1. Goals and non-goals

### Goals
- **Zero leaks.** In Vire Mode no block, item, inventory or interaction packet reaches the
  server. The player's body stays still on the server. This rule wins over every other goal.
- **Exact vanilla behavior.** Quasi-connectivity, update order, 0-tick pulses, piston timing and
  observer chains behave as in a real 26.3 world, because a real `ServerLevel` runs them (§5).
- **Debugging tools.** Play, pause, step N ticks, tick rate 1–100 TPS, signal levels on
  components, delay and frequency measurement between marked blocks.
- **From design to real build.** Ghost overlay of the design, a BOM checked against the player's
  inventory, ghost blocks that clear when the real block is placed.
- **Litematica export.** Standard `.litematic` file in `schematics/`; optional auto-load as a
  placement when Litematica is installed.
- **Works on any server.** Client-only. No server mod, no op, no cheats.

### Non-goals (v1)
- NeoForge or Forge. Fabric only.
- Checking nearby chests for BOM items. The BOM checks the player inventory only.
- Entities in the sandbox (mobs, item frames, minecarts). See §5.6 for falling blocks and TNT.
- Sharing designs between players.
- Placing real blocks automatically. That is the printer mods' job; Vire only hands them a file.

---

## 2. Tech stack and project setup

Reuse the setup of Gauge / Ghost Mason (`C:\Users\henk\Documents\gauge`).

| Item | Value |
|---|---|
| Minecraft | 26.3 (unobfuscated, Mojang names) |
| Loader | Fabric Loader `0.20.1` |
| Loom | `net.fabricmc.fabric-loom` `1.19-SNAPSHOT` |
| Fabric API | `0.165.2+26.3` |
| Java | 25 (`C:\Users\henk\.jdks\jdk-25.0.5+8`; system Java is 17) |
| JSON | Gson (bundled with Minecraft) |
| Tests | JUnit 5 in `src/test` (core only) |
| Optional | ModMenu `21.0.0` (config button); Litematica `0.29.x` + MaLiLib `0.30.x` (soft dependency, §10) |

`fabric.mod.json`: `"environment": "client"`, entrypoint `client` → `dev.vire.client.VireClient`.
Depends: `fabricloader >=0.20.1`, `minecraft ~26.3`, `java >=25`, `fabric-api *`.
Suggests: `modmenu`, `litematica`.

Loom: `splitEnvironmentSourceSets()`. Client run `-Xms256M -Xmx2G`, server run in `run-server`
with `-Xmx1G` (8 GB laptop). `-Pdevshots` scripted screenshots as in Gauge.

Build:
```bash
JAVA_HOME=/c/Users/henk/.jdks/jdk-25.0.5+8 ./gradlew build
```

`.gitignore`: `run/`, `run-server/`, `build/`, `.gradle/`.

### Files on disk

Rule: **every file Vire writes lives in `config/vire/`**, resolved with
`FabricLoader.getInstance().getConfigDir().resolve("vire")`. Nothing loose in `config/`.

| Path | Content |
|---|---|
| `config/vire/config.json` | Settings (§13) |
| `config/vire/designs/<name>.json` | Saved designs (edit list + origin + metadata), so a ghost survives a restart |
| `config/vire/sandbox/` | Scratch world of the sandbox server. Wiped on every start and on exit. |

The one exception is the `.litematic` export: it goes to `.minecraft/schematics/`, because that
is where Litematica looks. The user asked for that file there.

### Source layout

Rule: **`src/main` never imports Minecraft classes.** It holds the design model, diff, BOM math,
`.litematic` writer, delay tracker and config, so it is unit-testable with plain JUnit.
Everything that touches the game lives in `src/client`.

```
src/main/java/dev/vire/
  core/design/    BlockKey (pos), StateRef (block id + property map, MC-free), Edit,
                  Design (origin, bounds, edit list, final states), DesignDiff
  core/bom/       Bom, BomLine, ItemNeed, Inventory snapshot (MC-free counts)
  core/litematic/ NbtWriter (tiny gzip NBT writer), LitematicWriter, BitPacker, Palette
  core/measure/   DelayTracker, FrequencyMeter (input → output tick math)
  core/config/    VireConfig, ConfigStore (config/vire/, backup on parse error)
src/client/java/dev/vire/client/
  VireClient                 entrypoint: keys, commands, tick hooks, HUD
  mode/          VireMode (state machine, §3), ModeGuard (packet safety net, §4.3)
  camera/        FreeCamera, CameraInput (movement keys go to the camera, not the player)
  interact/      VireInteraction (place/break/use in the sandbox), VirtualHotbar,
                 PaletteScreen (block picker; never sends creative packets)
  sandbox/       SandboxServer (extends MinecraftServer, §5), SandboxBootstrap,
                 SandboxBridge (thread-safe queues client ⇄ sandbox), RegionCopier,
                 ChangeCollector (mixin-fed list of changed positions per tick)
  mirror/        ClientMirror (writes sandbox states into ClientLevel, §6),
                 RealUpdateBuffer (holds server block updates for the region while in Vire Mode)
  overlay/       SignalLabels, DelayMarkers, RegionBox, GhostRenderer (reuse Ghost Mason)
  ghost/         GhostLayer, BuildAssist, BomPanel
  litematica/    LitematicaHook (reflection, only when the mod is loaded)
  ui/            ControlBar (play/pause/step/speed), VireScreen (designs, export, settings)
  command/       VireCommands
  mixin/         MultiPlayerGameModeMixin, ConnectionMixin, LocalPlayerMixin,
                 ClientPacketListenerMixin, GameRendererMixin/CameraMixin,
                 ServerLevelMixin (sandbox only: change collection)
src/client/resources/
  vire.client.mixins.json, assets/vire/lang/en_us.json, assets/vire/icon.png
src/test/java/dev/vire/core/...
```

---

## 3. Vire Mode state machine

```
OFF ──enter──▶ STARTING ──sandbox ready──▶ ACTIVE ──exit──▶ STOPPING ──▶ OFF
                  │                          │  ▲
                  └─fail/abort──▶ OFF        └──┘ play/pause/step/edit
```

- **enter** (key `V` or `/vire`): checks (§3.1), freeze the body, start the free camera, copy
  the region, boot or reuse the sandbox server, show "Starting sandbox…" with progress.
- **ACTIVE**: all input goes to the sandbox. HUD shows the control bar and a clear
  `VIRE MODE` frame so the user never forgets they are not in the real world.
- **exit**: choose *Keep as ghost*, *Export .litematic*, *Save design*, or *Discard*. Restore
  the real blocks in the region (§6.3), stop the camera, give input back to the player.
- **Forced exit** (no prompt, design auto-saved to `config/vire/designs/_autosave.json`):
  disconnect, dimension change, player death, the player body takes damage (setting, default on),
  or the server teleports the player.

### 3.1 Enter checks
- Not riding, not in a vehicle, not gliding, on the ground or in water. A falling or gliding body
  that stops sending movement would look like a hack and could die.
- Warning, not a block: hostile mobs within 16 blocks, HP below 50 %, standing in lava/fire.
- Region fits inside loaded chunks. Unloaded parts are copied as air and shown with a hatched
  border.

### 3.2 The body while in Vire Mode
The real player stays where they stood. As built in M1:
- The camera entity is the `FreeCamera`, so vanilla `LocalPlayer.sendPosition` sends **no**
  movement packets at all (it only sends while the player is the camera). The server sees a
  player that stands still.
- `LocalPlayer.input` is swapped for a blank `ClientInput`, so no key state (sneak, jump) is sent
  in `ServerboundPlayerInputPacket`. Movement keys are read directly by the camera.
- `LocalPlayer.move` is cancelled for the body, so a removed virtual block under it cannot make it
  fall client-side.
- If the server moves the body (teleport, knockback), Vire Mode ends.

Risk to state in the README: the body is in the real world. Mobs and players can hurt it. Vire
does not protect it.

---

## 4. Input isolation (no leaks)

Two layers. Layer 1 makes sure no action is created. Layer 2 drops anything that slips through.

### 4.1 Layer 1: redirect actions at the source
| Vanilla path | 26.3 method | In Vire Mode |
|---|---|---|
| Right-click a block | `MultiPlayerGameMode.useItemOn(LocalPlayer, InteractionHand, BlockHitResult)` | cancel; `VireInteraction.use` on the sandbox |
| Right-click air | `MultiPlayerGameMode.useItem(Player, InteractionHand)` | cancel |
| Start / continue / stop breaking | `startDestroyBlock`, `continueDestroyBlock`, `stopDestroyBlock` | cancel; instant break in the sandbox |
| Attack an entity | `attack(Player, Entity)` | cancel |
| Hotbar scroll / number keys | `Inventory.setSelectedSlot` → `ServerboundSetCarriedItemPacket` | redirect to `VirtualHotbar` |
| Pick block (middle click) | `Minecraft.pickBlock…` | redirect to `VirtualHotbar` |
| Drop item, swap hands, open inventory | key handlers | cancel (inventory key opens `PaletteScreen`) |
| Creative item add / drop | `handleCreativeModeItemAdd`, `handleCreativeModeItemDrop` | never called by Vire's own UI |

Raycasts for placing and breaking start from the **free camera**, not the player's eyes, and hit
the mirrored sandbox blocks in `ClientLevel` (§6). Reach: 64 blocks (setting).

### 4.2 Virtual hotbar and palette
Vire draws its own hotbar and its own block picker. The survival inventory is not touched and
not shown. `PaletteScreen` lists every block item with search and tabs, like the creative
screen, but it only fills `VirtualHotbar`. It is not `CreativeModeInventoryScreen`, because that
sends creative packets.

### 4.3 Layer 2: packet safety net
As built in M1 this is an **allow-list** (`PacketGuard`): only connection upkeep, chat/commands,
teleport acks, blank player input and container close may leave; every other serverbound packet
is dropped and logged with a stack trace. New packet types in later versions are dropped by
default. The original block-list idea, for reference:

Mixin on `Connection.send(Packet, …)`: while Vire Mode is active, drop these packets and log a
warning with a stack trace (a dropped packet is a bug in layer 1):
`ServerboundUseItemOnPacket`, `ServerboundUseItemPacket`, `ServerboundPlayerActionPacket`,
`ServerboundInteractPacket`, `ServerboundSetCarriedItemPacket`, `ServerboundContainerClickPacket`,
`ServerboundSetCreativeModeSlotPacket`, `ServerboundPickItemFromBlockPacket`,
`ServerboundPickItemFromEntityPacket`, `ServerboundSwingPacket`, `ServerboundPlayerCommandPacket`
(sprint/sneak changes), `ServerboundSignUpdatePacket`, `ServerboundEditBookPacket`.

Movement packets (`ServerboundMovePlayerPacket.*`) are rewritten, not dropped: position and
rotation are pinned to the frozen body (§3.2). Keep-alive, chat, commands and pong pass through.
Verify the exact 26.3 packet class names in M1; the list is the intent.

Dev check (M1): `-Dvire.packetlog=true` logs every outgoing packet class while active. The M1
test plays 2 minutes of building in Vire Mode and asserts the log holds only movement,
keep-alive and client-tick packets.

---

## 5. Sandbox server (simulation)

### 5.1 Why a real server
Redstone in 26.3 runs on the server side: `BlockBehaviour.tick(BlockState, ServerLevel, …)`,
scheduled block ticks, block events (`triggerEvent`, used by pistons), and the neighbor update
order (`Orientation`). `ClientLevel` has none of these. A hand-made engine would never match
vanilla. So Vire runs a real, private `MinecraftServer` in the same JVM.

### 5.2 `SandboxServer`
- `SandboxServer extends MinecraftServer` (26.3 constructor: `Thread, LevelStorageAccess,
  PackRepository, WorldStem, Optional<GameRules>, Proxy, DataFixer, Services,
  LevelLoadListener, boolean, NotificationManager`). Started with `MinecraftServer.spin(…)`.
- Implements the abstract methods with "offline, no network, no players" answers:
  `initServer` creates the level only; `isDedicatedServer=false`, `isPublished=false`,
  `useNativeTransport=false`, no RCON, no tick-time logging.
- **No network listener and no player.** The client never connects to it. All traffic goes
  through `SandboxBridge` (two lock-free queues), and every sandbox call runs on the sandbox
  thread via `server.execute(…)`.
- World: one level, flat **void** preset (`minecraft:the_void` biome, no structures), stored in
  `config/vire/sandbox/` and wiped on start. Game rules: `doDaylightCycle=false`,
  `doMobSpawning=false`, `doWeatherCycle=false`, `randomTickSpeed=3` (setting; 0 freezes crops
  and copper), `doTileDrops=false`, `doEntityDrops=false`.
- `WorldStem` built with `WorldLoader.load(…)` using the vanilla data pack only. Datapack
  blocks from the real server are not present in the sandbox (risk §20).
- Not `IntegratedServer`: that class is tied to `Minecraft.singleplayerServer` and to a local
  player connection. A separate subclass keeps the real multiplayer connection untouched.

### 5.3 Lifecycle and cost
- Boot is slow (registries, data pack). Measure in M2. Target: under 5 s on the laptop.
- Setting `keepWarm` (default **off**): keep the sandbox running between sessions in the same
  game session, with the level cleared. Off by default because RAM is tight on 8 GB.
- Stop on Vire exit (unless warm), on disconnect and on game shutdown. Budget: sandbox heap use
  under 300 MB for a 64³ region. Measure in M2.
- Fabric API lifecycle events (`ServerLifecycleEvents`, `ServerTickEvents`) **fire for the
  sandbox too**. Other mods may react as if a singleplayer world started. Mitigation: mark the
  server (`SandboxServer.isSandbox`), document it, and test with common client mods installed
  (Litematica, Sodium, Gauge). Risk §20.

### 5.4 Region copy
- Region: cube centered on the player, size 32³ (default) or 64³, or a custom box chosen with two
  corner clicks. Shown as an outlined box.
- Copy from `ClientLevel`: block states, plus the block entity data the client has (signs,
  banners, comparator output is not synced, see §5.5). Air outside the region in the sandbox.
- The region is placed at the **same coordinates** in the sandbox, so positions map 1:1 and
  the overlay needs no transform.
- Unknown or modded block states: copy as `minecraft:barrier` and mark them.

### 5.5 What the copy cannot know
The client does not receive everything the server knows. After the copy, Vire **re-settles**
the sandbox: it schedules a neighbor update on every redstone component in the region and runs
the sandbox frozen for a few ticks so powered states, comparator outputs and hopper locks become
consistent. Container contents are known only for containers the player opened (same rule as
Gauge); comparators reading unknown containers read 0. This is shown in the HUD as "re-settled N
components".

### 5.6 Entities
v1 mirrors only the entity types redstone needs to look right: **falling blocks**, **primed
TNT** and **items** (hopper/dropper testing). They are shown as client-only entities in a
separate list, never added to `ClientLevel`'s real entity list. Other entities are not
simulated. Explosions in the sandbox destroy sandbox blocks only.

### 5.7 Time control
`ServerTickRateManager` (26.3 public API):
| Control | Call |
|---|---|
| Pause / Play | `setFrozen(true/false)` |
| Step 1 tick / N ticks | `stepGameIfPaused(n)` |
| Speed slider 1–100 TPS | `setTickRate(rate)` |
| Sprint (run as fast as possible for N ticks) | `requestGameToSprint(n)` |

The sandbox starts **paused** after the re-settle, so the user sees the copied circuit before
anything moves. The control bar shows the sandbox game tick counter.

---

## 6. Mirror: showing the sandbox in the real client

### 6.1 Approach
Write sandbox block states **into `ClientLevel`** for the region. Then vanilla rendering,
lighting, block outlines, raycasts and other client mods (Gauge, Sodium) all see the sandbox for
free.

- `ChangeCollector`: mixin on the sandbox `ServerLevel.sendBlockUpdated` (and chunk section
  changes) collects changed positions each sandbox tick.
- End of sandbox tick: send `(pos, state, blockEntityTag?)` batches through `SandboxBridge`.
- Client tick: `ClientMirror` applies batches with `ClientLevel.setBlock(pos, state, flags)`
  (no neighbor updates on the client). Block events (piston start) are forwarded so pistons
  animate.

### 6.2 Holding back the real server
While Vire Mode is active, real server updates for positions inside the region would overwrite
the mirror. `ClientPacketListenerMixin` sends block updates (`ClientboundBlockUpdatePacket`,
`ClientboundSectionBlocksUpdatePacket`, block entity data) **inside the region** to
`RealUpdateBuffer` instead of the level. Updates outside the region apply as normal. Chunk
reloads (`ClientboundLevelChunkWithLightPacket`) that cover the region are re-patched with the
mirror afterwards and also stored as the new real state.

### 6.3 Restore on exit
On exit, write back the real states: the copy taken on enter, with all buffered real updates
applied on top, in order. The world then shows exactly what the server has. Unit test the
buffer-merge order (M1).

---

## 7. Design model (what the user built vs what the simulation did)

The ghost and the BOM need the **design**, not the current simulation state (a powered dust or an
extended piston is not what you build).

- `Design` = the region origin and bounds + the **ordered list of user edits** (place `StateRef`
  at pos, break at pos). A placed state stores the state as placed (facing, delay, mode,
  half, etc.).
- **Reset** (button): rebuild the sandbox from the snapshot + edits, re-settle, pause. This
  is also how the user undoes a test run.
- **Undo / Redo**: drop or re-add the last edit, then reset. Ctrl+Z / Ctrl+Y.
- `DesignDiff(real snapshot, design)` → list of `(pos, wantState)`, where `wantState` is a
  state or "air". **Normalized**: properties that the simulation changes are ignored when
  comparing (`power`, `powered`, `lit`, `triggered`, `extended`, `enabled`, `locked`, redstone
  wire side connections, `note` is kept). The normalization table lives in core and is unit
  tested.

---

## 8. Overlays in Vire Mode

Drawn with vanilla `Gizmos` from the client tick, as in Gauge and Ortho.

- **Signal labels**: number 0–15 on dust, repeater/comparator output, powered blocks, and
  observer/target outputs. Only within 24 blocks of the camera, only facing sides. Gauge's label
  code is reused (copy, not a dependency).
- **Flow arrows** (toggle): observer facing, hopper output, repeater/comparator direction,
  dropper/dispenser facing.
- **Delay tracker**: mark one block as **input** and one as **output** (keys or tool). Vire
  records the sandbox tick when the input changes state and when the output changes next. Shows
  `Δ = 6 gt (3 rt, 0.30 s)`. With repeated pulses it also shows period and frequency
  (`2.5 Hz`). Exact, because both ticks come from the sandbox tick counter.
- **Region box** and **unknown block markers** (§5.4).
- **Control bar** at the top (HUD, not a screen, so the game stays playable):
  `[⏸] [⏭ 1] [⏭ N] [▶]  20 TPS  tick 1234  |  Reset  Undo  Mark in/out  |  Exit`.
  Keys: `V` exit, `P` pause/play, `.` step 1, `,` step N, `[` `]` speed. All rebindable.

---

## 9. Ghost layer and build assist (after exit)

- On *Keep as ghost*, `DesignDiff` becomes the active `GhostLayer`, saved to
  `config/vire/designs/<name>.json`. Only one active ghost in v1; the design list can switch.
- Rendering: translucent blocks. Reuse Ghost Mason's renderer (copy the class). Colors: ghost
  block = to place, red outline = real block in the way that must go.
- **Build assist**: on every real block update inside the ghost bounds, compare the real state
  with `wantState` (normalized, §7). Match → the ghost block disappears. Wrong state at the
  position (e.g. repeater facing the wrong way) → yellow outline + tooltip "facing: north
  expected".
- **BOM panel** (toggle key `B`): one line per item: needed, in inventory, missing. Green when
  the inventory has enough, red when not. Updates as the player builds (counts only ghost blocks
  still left) and as the inventory changes. **Inventory only**: no chest scanning.
- Item mapping: `Block.asItem()`, with core rules for special cases: double blocks count once
  (doors, beds, tall plants), redstone wire → redstone dust, `water`/`lava` → buckets, slabs
  `type=double` → 2 slabs, candles/sea pickles/turtle eggs by count property, snow layers by
  `layers`. Unit tested.
- Ghost clears itself when every block matches; a toast says "Design complete".

---

## 10. Litematica integration

### 10.1 Export (no dependency)
- `LitematicWriter` in core writes the documented `.litematic` format: gzipped NBT, root with
  `Version`, `SubVersion`, `MinecraftDataVersion`, `Metadata` (Name, Author, Description,
  RegionCount, TotalVolume, TotalBlocks, TimeCreated, TimeModified, EnclosingSize) and
  `Regions/<name>` with `Position`, `Size`, `BlockStatePalette`, `BlockStates` (long array,
  bits per entry = max(2, ceil(log2(palette size))), entries may span longs), `TileEntities`,
  `Entities`, `PendingBlockTicks`, `PendingFluidTicks`.
- What is exported: the **design** (§7), not the simulation state, cut to the bounding box of
  the edits (option: whole region). Air in the design is exported as air so the printer clears
  those spots (option: "skip air").
- Version numbers: read Litematica 0.29.x's writer once in M5 and match its `Version` /
  `SubVersion`; `MinecraftDataVersion` from `SharedConstants` at runtime.
- File: `.minecraft/schematics/vire/<design name>.litematic`.
- Golden-file test: export a known design, load it in Litematica 0.29.1 in the dev client, and
  check rotations of observers, repeaters, comparators, pistons and stairs.

### 10.2 Auto-load (optional)
- Only when `FabricLoader.isModLoaded("litematica")`. `LitematicaHook` uses Litematica's API
  through a `modCompileOnly` dependency inside a class that is only loaded when the mod is there.
- On export (setting, default on): load the schematic and create a placement at the design's
  real origin, so the printer (`litematica-printer` 3.2.x supports 26.3) can start at once.
- If the API call fails (version drift), fall back to "Saved to schematics/vire/… — load it in
  Litematica" and log the error.

---

## 11. Commands (client commands)

| Command | Action |
|---|---|
| `/vire` | Toggle Vire Mode (same as `V`) |
| `/vire size <16..64>` | Region size for the next enter |
| `/vire designs` | Open the design list |
| `/vire ghost <name> \| off` | Show a saved design as ghost / hide the ghost |
| `/vire export <name>` | Export a saved design as `.litematic` |
| `/vire bom` | Print the BOM to chat |
| `/vire status` | Sandbox state, tick, TPS, memory use |

---

## 12. User interface

- **HUD in Vire Mode**: tinted frame + `VIRE MODE` label, control bar (§8), virtual hotbar,
  small info line (region size, re-settled count, dropped-packet counter which must stay 0).
- **Exit dialog**: name field, buttons *Keep as ghost*, *Export .litematic*, *Save*, *Discard*.
- **VireScreen** (from `/vire designs` or ModMenu): designs list (rename, delete, show ghost,
  export), settings.
- **BOM panel**: side panel on the HUD, toggled with `B`.
- Entry effect: short fade of the tint; no blur (blur costs frames on the laptop).

---

## 13. Config (`config/vire/config.json`)

| Key | Default | Note |
|---|---|---|
| `regionSize` | 32 | 16–64 |
| `reach` | 64 | blocks, free camera raycast |
| `cameraSpeed` | 1.0 | fly speed multiplier |
| `keepWarm` | false | §5.3 |
| `randomTickSpeed` | 3 | sandbox game rule |
| `exitOnDamage` | true | §3 |
| `labelRange` | 24 | §8 |
| `autoLoadLitematica` | true | §10.2 |
| `exportSkipAir` | false | §10.1 |
| `packetLog` | false | §4.3 |

Broken JSON: back it up as `config.json.broken-<time>`, load defaults, tell the user once.

---

## 14. Performance budget

| Item | Budget |
|---|---|
| Client tick cost in Vire Mode (mirror + overlays) | < 1 ms at 20 TPS, 64³ region |
| Mirror batch per tick | apply at most 4096 changes per client tick; the rest next tick |
| Sandbox at 100 TPS | must not drop the client below 30 FPS on the laptop; cap TPS if it does |
| Sandbox memory | < 300 MB for 64³ (measure M2) |
| Ghost layer | < 0.5 ms per frame for 5000 ghost blocks (reuse Ghost Mason batching) |

---

## 15. Security and fair play

- Vire gives no advantage on the real server: no real block changes, no information the client
  did not already have (the copy is built from `ClientLevel`).
- Fake movement packets (§3.2) are the same as a player standing still. Vire never sends a
  position different from the body's real one.
- README states plainly: some servers ban freecam-style mods. Vire does not try to hide itself.

---

## 16. Milestones

Each milestone ends with a build that runs.

**M0 — Scaffold** (½ day)
- Gradle project copied from Gauge; env `client`; empty entrypoint; `en_us.json`; icon
  placeholder; JUnit wired; README stub; git init; `config/vire/` helper.
- *Done when*: `./gradlew build` passes and `runClient` loads the mod.

**M1 — Mode toggle, free camera, isolation, local edits (no simulation)**
- State machine, enter checks, frozen body, free camera, layer 1 + layer 2 isolation, virtual
  hotbar, palette screen, region copy, `RealUpdateBuffer` + restore. Edits go straight into
  `ClientLevel` (no physics yet). Design model + undo.
- *Done when*: on the run-server test server, 2 minutes of building in Vire Mode logs zero
  forbidden packets (§4.3); exit restores the real blocks exactly, including a block another
  player (Carpet bot via RCON) changed inside the region meanwhile.
- **Status 2026-11-11: done.** `runClient -Pselftest=quit` (op'd `VireTester` on run-server)
  drives the real key paths: place, break, hold use/attack, drop/swap/pick/inventory keys, fly,
  door + undo/redo, real `/setblock` changes during the session, exit, damage exit. Result: PASS;
  only `ChatCommand`, `ClientTickEnd`, `KeepAlive`, `ChunkBatchReceived` sent, 0 dropped; region
  of 20480 blocks restored exactly with the real changes on top; no movement warnings on the
  server. Region copy into a sandbox moved to M2 (nothing to copy into yet).

**M2 — Sandbox server** (the big one; start with a spike)
- Spike first: boot `SandboxServer` with a void world in the dev client, set one block, tick it,
  read it back. Measure boot time and memory. **Stop and report** if boot > 15 s or memory >
  600 MB; then reconsider (see §20).
- **Spike result 2026-11-11: PASS** (`/vire spike`, or `runClient -Pselftest=spike`; dev client
  on the 8 GB laptop, connected to run-server). `SandboxServer` = `MinecraftServer` subclass
  modeled on vanilla `GameTestServer`: vanilla data pack only, one overworld with the
  `the_void` flat preset, no network, no players, never saved.
  | Measure | Result |
  |---|---|
  | Boot to ready | 9.2 s (6.4 s of it is data pack loading); first run 11.4 s |
  | Heap | +30 MB while running; 20 MB above the start value after stop (check for leaks over 10 cycles) |
  | Stop | 3.2 s (was 11.6 s before skipping chunk saves of the scratch world) |
  | Tick rate | 20 TPS default, 98 at `setTickRate(100)` |
  | Vanilla timing | repeater (delay 1) off 2 gt after input removed, lamp 4 gt after that, using `setFrozen` + `stepGameIfPaused(1)` |

  Boot is over the 5 s target, so: start the sandbox in the background when the player joins a
  world (setting, default on) and keep it warm while connected; V then enters instantly. Fall
  back to "Starting sandbox…" when it is not ready yet.
- Then: region copy into the sandbox, re-settle, `ChangeCollector`, mirror, block events
  (pistons), play/pause/step/speed, reset, falling blocks/TNT/items mirror.
- *Done when*: a test kit of circuits (§17) behaves the same in the sandbox as in a real
  singleplayer world, tick for tick.

**M3 — Debug overlays**
- Signal labels, flow arrows, delay tracker, frequency meter, control bar polish.
- *Done when*: the delay tracker reads the right ticks on the test kit (repeater chain = sum of
  delays; observer clock period = 2 gt).

**M4 — Ghost layer, BOM, build assist**
- Exit dialog, designs saved in `config/vire/designs/`, ghost rendering, normalized diff, BOM
  panel (inventory only), build assist, "design complete".
- *Done when*: build a design from the ghost in survival on the test server; every ghost block
  clears; BOM counts match by hand.

**M5 — Litematica**
- `.litematic` writer + golden tests, export button + command, auto-load hook.
- *Done when*: the exported file loads in Litematica 0.29.1 with correct rotations, and
  Litematica Printer 3.2.x prints it in survival on the test server.

**M6 — Release 0.1**
- Polish, README with screenshots and the "body stays in the world" warning, icon, Modrinth
  page (ask first), changelog. Tag `0.1.0+26.3`.

**M7 — Extras** (any order)
- Several ghost designs at once; move/rotate a design before building; copy/paste inside the
  sandbox; more entity types; Stonecutter multi-version.

---

## 17. Testing strategy

- **Unit (src/test, no MC)**: design edit list + undo/redo, normalization table, diff, BOM item
  mapping and counts, BitPacker (entries spanning two longs, palette sizes 1, 2, 3, 17, 300),
  NBT writer (golden bytes), delay/frequency math, update-buffer merge order, config round-trip
  and broken-file recovery.
- **Circuit test kit** (`tools/testkit/*.json`, placed by a dev command): repeater chain with
  mixed delays, observer clock, comparator subtraction, quasi-connected piston (BUD), 0-tick
  piston, sticky piston block-drop, hopper clock, falling sand on a piston, TNT on a timer. For
  each: expected state per tick, recorded once in a real singleplayer world, compared with the
  sandbox (dev command `/vire selftest`).
- **Leak test**: §4.3 packet log on the run-server test server (RCON pw `viretest`), plus the
  server's own log shows no block changes and no movement warnings ("moved too quickly",
  "moved wrongly").
- **In-game checklist**:
  - Enter next to a running clock in the real world → real clock keeps running for others; the
    sandbox copy starts paused.
  - Real player (Carpet bot) breaks a block in the region during Vire Mode → after exit the
    block is gone.
  - Take damage in Vire Mode → force exit, design auto-saved.
  - Disconnect in Vire Mode → sandbox stops, autosave exists, no crash.
  - Rejoin → `/vire ghost _autosave` shows the ghost.
  - Memory: `/vire status` before, during and after 10 enter/exit cycles (no leak).

---

## 18. Commands for the dev loop

```bash
JAVA_HOME=/c/Users/henk/.jdks/jdk-25.0.5+8 ./gradlew runServer --args=nogui
```
```bash
JAVA_HOME=/c/Users/henk/.jdks/jdk-25.0.5+8 ./gradlew runClient --args="--username VireTester --quickPlayMultiplayer localhost:25565"
```

Do not run client + test server + sandbox on the laptop at high region sizes: keep the test
region at 32³ until the M2 memory numbers are known.

---

## 19. Prior art (checked on Modrinth 2026-11-11)

| Mod | Overlap | Difference |
|---|---|---|
| Litematica (+ Printer forks) | Schematics, placement, printing | No simulation; Vire feeds it files |
| BuildPrint | Ghost of a schematic, missing items | Needs a schematic; no design or simulation |
| Freecam | Detached camera | No editing, no simulation |
| Reden | Redstone debugging, undo | Server/singleplayer; no 26.3 version found |
| Gauge (own) | Signal labels on real circuits | Vire reuses its label code inside the sandbox |
| Ghost Mason (own) | Ghost rendering | Vire reuses the renderer |

No mod found that combines a client-only sandbox with vanilla-exact simulation on a multiplayer
server.

---

## 20. Risks and open questions

| Item | Note |
|---|---|
| Sandbox boot cost | Unknown until the M2 spike. Fallback: keep it warm, or load registries once and reuse them. If it is far over budget, revisit a reduced engine — but accept inexact redstone only as a last resort. |
| Other mods reacting to the sandbox server | Fabric lifecycle events fire for it (§5.3). Test with popular client mods. Possible fix: a mixin that skips event invokers when the server is a `SandboxServer` (only for events Vire does not need). |
| Server datapack / modded blocks | Not in the sandbox; copied as barriers and marked. |
| Data the client lacks | Container contents, comparator BE values, scheduled ticks in progress. Re-settle (§5.5) makes the copy consistent, not identical. Documented. |
| Mirror vs real server races | Region updates are buffered (§6.2). Chunk resend edge cases need testing (moving far from the body, render distance). Keep the camera within render distance of the region. |
| Anti-cheat | Standing still is normal. Some servers ban freecam mods outright; README warns. |
| 26.x API churn | Packet class names, `MultiPlayerGameMode` signatures (checked on 26.3 jars 2026-11-11), `ServerTickRateManager`, `WorldLoader`. Keep MC calls in `src/client`. |
| Litematica format version | Match 0.29.x output in M5; golden test guards it. |
| Open | Region bigger than 64³ for large builds? Memory first, decide after M2. |
| Open | Should a design be movable/rotatable before building (M7)? |

---

## 21. Changes from the Gemini spec

| Spec | Plan | Why |
|---|---|---|
| Intercept outgoing block packets | Cancel at `MultiPlayerGameMode` + packet safety net | Packets should never be built; the net only catches bugs |
| Player gets fly mode | Free camera; body stays still | Client-side flight makes the server rubber-band and flag the player |
| "Lightweight local tick runner" | Private in-process `MinecraftServer` with a void world | Redstone needs `ServerLevel`; only real vanilla code is exact |
| Tick control built by hand | `ServerTickRateManager` freeze/step/rate/sprint | Already in vanilla |
| BOM checks nearby chests | Inventory only | User decision 2026-11-11 (clients do not know unopened chest contents anyway) |
| Fabric/NeoForge | Fabric only | User decision 2026-11-11 |
| Ghost = all changed blocks | Ghost = normalized design diff | Powered/extended states are not what you build |
| Visual blur on enter | Tint fade | Cheaper on the laptop |
