# Vire

Client-side redstone sandbox for Minecraft 26.3 (Fabric).

Press `V` in survival, on any server, to enter **Vire Mode**: a private copy of the area around
you. Fly a free camera, place and break any block, and run the circuit with exact vanilla
redstone (pause, step, speed control). The server never sees it. When you leave, the design
becomes a ghost overlay with a bill of materials, or a `.litematic` file for Litematica.

**Status:** in development. M1 (Vire Mode, free camera, isolation) and M2 (a private sandbox
server with exact vanilla redstone: pause/step/speed, pistons, falling blocks, TNT, items) are
done. A test kit checks the sandbox against a real server tick by tick. Next: M3 signal overlays.
See [PLAN.md](PLAN.md).

### Vire Mode

| Key | Action |
|---|---|
| `V` | Enter / leave Vire Mode |
| WASD, Space, Shift, Ctrl | Fly the camera (Ctrl = faster) |
| Right / left click | Place / break (hold to repeat) |
| Middle click | Pick block into the virtual hotbar |
| `E` | Block palette (click = selected slot, hover + 1–9 = that slot) |
| Ctrl+Z / Ctrl+Y | Undo / redo (rebuilds the circuit, paused) |
| `K` | Run / pause the circuit (starts paused) |
| `.` / `,` | Step 1 / 10 game ticks |
| `[` / `]` | Slower / faster (1–100 TPS) |
| `R` | Reset the circuit to your design |
| `N` / `M` | Mark the block you look at as delay input / output (again = unmark) |
| `J` | Flow arrows on / off |

In Vire Mode, components within 24 blocks show their signal level (dust power, repeater, comparator
and observer output; `S`/`W` = strongly/weakly powered block). With an input and an output marked,
the bar shows the delay (`Δ 12 gt (6 rt, 0.60 s)`), and for a repeating signal the period,
frequency and on time.

Commands: `/vire`, `/vire size <16..64>`, `/vire status`, `/vire spike` (sandbox self-check).

The simulation runs in a small private Minecraft server inside your client. It starts in the
background when you join a world (about 10 s, ~30 MB) so V is instant; turn `keepWarm` off in
`config/vire/config.json` to start it only when needed.

Your body stays in the real world while you are in Vire Mode. Taking damage, dying, being
moved by the server or changing dimension ends Vire Mode. Some servers ban freecam-style mods;
Vire does not hide itself.

## Development

`./gradlew runClient -Pselftest=quit --args="--username VireTester --quickPlayMultiplayer 127.0.0.1:25565"`
runs the scripted acceptance test against the local test server (`./gradlew runServer`,
`VireTester` must be op). Other modes: `-Pselftest=kit` (sandbox vs real server, tick by tick),
`-Pselftest=probe` (delay tracker and frequency meter), `-Pselftest=cycles` (leak check), `-Pselftest=spike` (sandbox boot cost). `-Ppacketlog` logs every
packet sent while Vire Mode is on.

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
