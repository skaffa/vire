# Vire

Client-side redstone sandbox for Minecraft 26.3 (Fabric).

Press `V` in survival, on any server, to enter **Vire Mode**: a private copy of the area around
you. Fly a free camera, place and break any block, and run the circuit with exact vanilla
redstone (pause, step, speed control). The server never sees it. When you leave, the design
becomes a ghost overlay with a bill of materials, or a `.litematic` file for Litematica.

**Status:** in development. Milestone M1 done: Vire Mode with a free camera, virtual
place/break/undo in the world you see, and full isolation from the server. Redstone simulation
comes in M2. See [PLAN.md](PLAN.md).

### Vire Mode (M1)

| Key | Action |
|---|---|
| `V` | Enter / leave Vire Mode |
| WASD, Space, Shift, Ctrl | Fly the camera (Ctrl = faster) |
| Right / left click | Place / break (hold to repeat) |
| Middle click | Pick block into the virtual hotbar |
| `E` | Block palette (click = selected slot, hover + 1–9 = that slot) |
| Ctrl+Z / Ctrl+Y | Undo / redo |

Commands: `/vire`, `/vire size <16..64>`, `/vire status`.

Your body stays in the real world while you are in Vire Mode. Taking damage, dying, being
moved by the server or changing dimension ends Vire Mode. Some servers ban freecam-style mods;
Vire does not hide itself.

## Development

`./gradlew runClient -Pselftest=quit --args="--username VireTester --quickPlayMultiplayer 127.0.0.1:25565"`
runs the scripted M1 acceptance test against the local test server (`./gradlew runServer`,
`VireTester` must be op). `-Ppacketlog` logs every packet sent while Vire Mode is on.

## Files

Everything Vire writes lives in `config/vire/`. The only exception is `.litematic` exports,
which go to `schematics/vire/` so Litematica finds them.

## Build

Needs Java 25.

```bash
./gradlew build
```

## License

MIT
