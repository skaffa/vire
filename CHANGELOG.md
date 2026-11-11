# Changelog

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
