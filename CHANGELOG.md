# Changelog

## 0.2.1+26.3

- **Settings screen** in Mod Menu and with `/vire config`: region size, camera speed, reach, leave
  when hurt, keep sandbox warm, random tick speed, overlays, ghost range and Litematica placement,
  each with a tooltip. No more editing `config.json` by hand.

## 0.2.0+26.3

- **Use blocks in Vire Mode**: right click flips levers, presses buttons, opens doors, trapdoors and
  gates, and changes repeater delay, comparator mode, note block pitch and daylight detector mode.
  Ctrl + right click places against them. Flips are inputs (reset puts them back); settings are edits.
- **Sessions are kept**: V in the same region continues your last Vire session. It is saved every few
  seconds; after a crash or kick the next join asks to restore it. `/vire discard` forgets it.
- **New HUD**: values panel (clock, speed, tick, edits, delay meter, targeted block, body health) and a
  keys panel (`H` hides it) instead of the top bar. Vire crosshair and a block outline, green on
  usable blocks, red outside the region. The XP bar is hidden in Vire Mode, so the item name is clear.
- Reset flashes the region outline and shows in the panel.
- Block palette: the search box no longer takes the 1–9 keys; type a letter to search, Enter to stop.
- `/vire help`.
- Step keys swapped so they go low to high: `,` steps 1 game tick, `.` steps 10.
- Fix: edits older than the last 500 undo steps were lost on reset.

## 0.1.0+26.3

First release, for Minecraft 26.3 with Fabric.

- **Vire Mode** (`V`): a private copy of the area around you with a free camera, a virtual hotbar and a
  block palette. Nothing reaches the server: actions are redirected in the client, and a packet
  allow-list catches anything else. The real blocks come back exactly when you leave, including
  changes other players made meanwhile.
- **Exact vanilla redstone** in a private server inside the client: run, pause, step 1 or 10 game
  ticks, 1–100 TPS, reset, undo and redo. Pistons, falling blocks, TNT and items included.
- **Overlays**: signal levels, strongly/weakly powered blocks, flow arrows (`J`), delay meter with
  period and frequency (`N` / `M`).
- **Designs**: exit dialog (keep as ghost, save, export, discard), autosave on Escape and forced
  exits, saved in `config/vire/designs/`.
- **Ghost and build assist**: see-through missing blocks, red for blocks in the way, yellow with a hint
  for the wrong state, materials panel against the inventory (`B`), "Design complete" toast.
- **Litematica**: `.litematic` export (`/vire export`, or the dialog) and automatic placement when
  Litematica is installed.
