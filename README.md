# Vire

Client-side redstone sandbox for Minecraft 26.3 (Fabric).

Press `V` in survival, on any server, to enter **Vire Mode**: a private copy of the area around
you. Fly a free camera, place and break any block, and run the circuit with exact vanilla
redstone (pause, step, speed control). The server never sees it. When you leave, the design
becomes a ghost overlay with a bill of materials, or a `.litematic` file for Litematica.

**Status:** in development. Milestone M0 (scaffold). See [PLAN.md](PLAN.md).

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
