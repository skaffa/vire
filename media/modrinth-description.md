**Test redstone without touching the world.** Press **V** in survival, on any server, and Vire gives you a private copy of the area around you. Fly a free camera, place any block, and run the circuit with exact vanilla redstone: pause it, step it one game tick at a time, slow it down or speed it up. Nothing you do there reaches the server. When you leave, keep the design as a see-through ghost to build from, with the materials you still need, or export it for Litematica.

Vire is client-side only. It needs nothing on the server.

## Exact vanilla redstone

The copy runs in a small private Minecraft server inside your client, so repeaters, comparators, observers, pistons (block events, quasi-connectivity), falling blocks, TNT and items behave exactly as in the real game. A test kit checks it against a real server, tick by tick.

- Starts paused. **K** runs or pauses, **,** steps 1 game tick, **.** steps 10.
- **[** and **]** change the speed from 1 to 100 ticks per second.
- **R** resets the circuit to your design; **Ctrl+Z** / **Ctrl+Y** undo and redo.
- Right click flips levers, presses buttons, opens doors and gates, and changes repeater delay and comparator mode. Ctrl + right click places against them.

## See what it does

- Power levels on dust, outputs of repeaters, comparators and observers, and `S`/`W` on strongly or weakly powered blocks.
- Arrows showing where repeaters, comparators, observers, hoppers, droppers and pistons output (**J**).
- **Delay meter**: mark an input (**N**) and an output (**M**). Vire shows the delay as `Δ 12 gt (6 rt, 0.60 s)`, and for a clock its period, frequency and on time. Exact, because both ends are measured on the same game tick counter.

## A clear overview

A values panel shows the clock, speed, tick, edits, the delay meter, the block you look at and your body's health. A keys panel lists every key as you have bound it (**H** hides it). The crosshair and block outline turn green on something you can flip or change, and red outside the region.

## Your session is kept

Leave and press **V** again in the same region: you continue where you left off. The session is also saved every few seconds, so after a crash or kick the next join asks whether to restore it.

## Build it for real

When you leave Vire Mode, name your design and:

- **Keep as ghost**: missing blocks show see-through in the real world. A block in the way gets a red box; the right block in the wrong state a yellow box with what is expected (`facing: west expected`, `delay: 2 expected`). Built blocks disappear, and a toast tells you when the design is complete.
- The **materials panel** (**B**) lists what you still need against what is in your inventory.
- **Export .litematic**: saved to `schematics/vire/`. With Litematica installed, it is loaded and placed at its real position at once.

## Good to know

- **Your body stays in the real world** while you are in Vire Mode. Taking damage, dying, being moved by the server or changing dimension ends Vire Mode (your design is autosaved). Enter only where you are safe.
- Some servers do not allow freecam-style mods. Vire does not hide itself; check the rules.
- The private server starts in the background when you join (about 10 s, ~30 MB), so V is instant. Turn off "Keep sandbox warm" in the settings to start it only when needed.
- The copy only knows what your client knows: unopened container contents are not there.
- **Settings** (region size, camera speed, reach, overlays, ghost range and more) are in Mod Menu, or open them with `/vire config`.
- Commands you type in Vire Mode go to the real server: `/kill` still kills your real body.
- Vire's files live in `config/vire/`; `.litematic` exports go to `schematics/vire/`. `/vire help` lists every key and command.

Requires Fabric API. Litematica and Mod Menu are optional.
