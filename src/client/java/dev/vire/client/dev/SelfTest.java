package dev.vire.client.dev;

import com.mojang.blaze3d.platform.InputConstants;

import dev.vire.client.VireClient;
import dev.vire.client.interact.VireWorld;
import dev.vire.client.interact.VirtualHotbar;
import dev.vire.client.mode.PacketGuard;
import dev.vire.client.mode.VireMode;
import dev.vire.client.mode.VireSession;
import dev.vire.core.region.Box;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Dev-only M1 acceptance script (PLAN.md §16, M1). Runs only with {@code -Dvire.selftest=run} (or
 * {@code =quit} to close the game afterwards), on a test server where the player is op.
 *
 * <p>It drives Vire Mode through the real key paths ({@code KeyMapping.click/set}), so the vanilla
 * {@code handleKeybinds} code runs with Vire's interception in place. Then it checks: no packet was
 * blocked by the safety net, the body never moved, edits/undo/redo work, real server changes made
 * during Vire Mode are held back and win on exit, and every other block in the region is restored.
 */
public final class SelfTest {
	private static final String MODE = System.getProperty("vire.selftest", "");
	private static final List<String> FAILURES = new ArrayList<>();
	private static final List<Step> STEPS = new ArrayList<>();

	private record Step(int delay, String name, Consumer<Minecraft> action) {
	}

	private static int ticks;
	private static int index;
	private static int waitUntil;
	private static boolean done;

	private static Map<BlockPos, BlockState> snapshot;
	private static Box box;
	private static Vec3 body;
	private static BlockPos ground;
	private static BlockPos p1; // virtually edited, then changed for real
	private static BlockPos p2; // untouched, changed for real
	private static BlockPos doorPos;
	private static BlockState p1Before;
	private static BlockState p2Before;
	private static BlockPos cBlock;
	private static BlockPos cRepeater;
	private static BlockPos cLamp;
	private static BlockPos pistonPos;
	private static long tickMark;
	private static final List<String> offSteps = new ArrayList<>();
	private static int waited;
	private static BlockPos sandPos;
	private static final Map<BlockPos, BlockState> realChanges = new HashMap<>();
	private static long heapStart;

	private SelfTest() {
	}

	public static void init() {
		if (MODE.isEmpty()) {
			return;
		}
		VireClient.LOG.info("[selftest] enabled ({})", MODE);
		if (MODE.equals("cycles")) {
			cycleSteps();
			return;
		}
		if (MODE.equals("kit")) {
			kitSteps();
			return;
		}
		if (MODE.equals("spike")) {
			// M2 spike only: run it once in the world, wait for it, then quit
			step(0, "spike", dev.vire.client.sandbox.SandboxSpike::start);
			step(20, "wait for spike", mc -> {
				if (dev.vire.client.sandbox.SandboxSpike.running()) {
					index--; // re-run this step next tick until the spike thread is done
					return;
				}
				done = true;
				mc.stop();
			});
			return;
		}
		// Away from running circuits, so "restored exactly" can compare with the snapshot. (Near a running
		// clock, the latest real states win on exit, which is correct but not comparable to a snapshot.)
		step(0, "go to a quiet spot", mc -> mc.player.connection.sendCommand("tp @s 300 -60 300"));
		step(80, "snapshot, enter", mc -> {
			body = mc.player.position();
			BlockPos b = mc.player.blockPosition();
			box = Box.centered(b.getX(), b.getY(), b.getZ(), VireClient.config.regionSize, mc.level.getMinY(), mc.level.getMaxY());
			snapshot = new HashMap<>();
			for (int x = box.minX(); x <= box.maxX(); x++) {
				for (int y = box.minY(); y <= box.maxY(); y++) {
					for (int z = box.minZ(); z <= box.maxZ(); z++) {
						BlockPos p = new BlockPos(x, y, z);
						snapshot.put(p, mc.level.getBlockState(p));
					}
				}
			}
			ground = b.below().east(3);
			p1 = ground.above();
			p2 = ground.south(4);
			p1Before = snapshot.get(p1);
			p2Before = snapshot.get(p2);
			click(VireClient.toggleKey());
		});
		waitFor("sandbox loaded", () -> VireMode.active() && VireMode.session().loaded, 600);
		step(5, "sandbox copy matches the real world", mc -> {
			check(VireMode.session().box.equals(box), "region box as expected");
			// against Vire's own snapshot (taken at enter): the real world may change between our snapshot and enter
			VireSession vs = VireMode.session();
			int bad = 0;
			for (BlockPos p : snapshot.keySet()) {
				BlockState want = vs.snapshot.getOrDefault(VireWorld.key(p), Blocks.AIR.defaultBlockState());
				if (!mc.level.getBlockState(p).equals(want)) {
					if (bad++ < 5) {
						VireClient.LOG.warn("[selftest] copy {} expected {} got {}", p, want, mc.level.getBlockState(p));
					}
				}
			}
			check(bad == 0, "virtual view equals the copied real region after copy + re-settle (" + bad + " differ)");
			check(VireMode.session().frozen, "sandbox starts paused");
		});
		// Edits travel client -> sandbox -> client, and the client may run several ticks per frame after a
		// hitch, so every async result is awaited by condition, not by a fixed number of ticks.
		aim(() -> ground);
		step(1, "place stone", mc -> {
			click(mc.options.keyHotbarSlots[6]); // smooth stone
			click(mc.options.keyUse);
		});
		waitFor("smooth stone at p1", () -> is(p1, Blocks.SMOOTH_STONE), 100);
		aim(() -> p1);
		step(1, "place repeater", mc -> {
			click(mc.options.keyHotbarSlots[1]); // repeater
			click(mc.options.keyUse);
		});
		waitFor("repeater above p1", () -> is(p1.above(), Blocks.REPEATER), 100);
		aim(() -> p1.above());
		step(1, "break repeater", mc -> click(mc.options.keyAttack));
		waitFor("repeater removed", () -> Minecraft.getInstance().level.getBlockState(p1.above()).isAir(), 100);
		step(1, "keys that would leak", mc -> {
			click(mc.options.keyDrop);
			click(mc.options.keySwapOffhand);
			click(mc.options.keyPickItem);
			click(mc.options.keyInventory);
		});
		waitFor("inventory key opens the palette", () -> Minecraft.getInstance().gui.screen() instanceof dev.vire.client.interact.PaletteScreen, 40);
		step(1, "close palette", mc -> mc.gui.setScreen(null));
		aim(() -> p1);
		step(1, "hold use", mc -> {
			click(mc.options.keyHotbarSlots[6]);
			set(mc.options.keyUse, true); // hold: builds a short tower
		});
		step(20, "release use", mc -> set(mc.options.keyUse, false));
		waitFor("holding use repeats placement", () -> is(p1.above(), Blocks.SMOOTH_STONE), 100);
		// the held-use tower height depends on frame timing: just look down at it, any top block will do
		step(1, "aim at tower", mc -> aimDown(VireMode.session(), p1.above(3)));
		step(5, "hold attack", mc -> set(mc.options.keyAttack, true));
		step(20, "release attack, fly", mc -> {
			set(mc.options.keyAttack, false);
			set(mc.options.keyUp, true);
			set(mc.options.keyJump, true);
			set(mc.options.keyShift, false);
		});
		step(10, "stop flying, check body", mc -> {
			set(mc.options.keyUp, false);
			set(mc.options.keyJump, false);
			VireSession s = VireMode.session();
			check(s.camera.position().distanceTo(body) > 3, "camera flew away");
			check(mc.player.position().distanceToSqr(body) < 1e-6, "body did not move");
			doorPos = ground.north(3).above();
			VirtualHotbar.set(8, new ItemStack(Items.OAK_DOOR));
			click(mc.options.keyHotbarSlots[8]);
		});
		aim(() -> ground.north(3));
		step(1, "place door", mc -> click(mc.options.keyUse));
		waitFor("door, both halves", () -> {
			BlockState lower = Minecraft.getInstance().level.getBlockState(doorPos);
			BlockState upper = Minecraft.getInstance().level.getBlockState(doorPos.above());
			return lower.getBlock() instanceof DoorBlock && upper.getBlock() instanceof DoorBlock
					&& upper.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
		}, 100);
		step(1, "undo", VireWorld::undo);
		waitFor("undo removed both door halves", () -> Minecraft.getInstance().level.getBlockState(doorPos).equals(snapshot.get(doorPos))
				&& Minecraft.getInstance().level.getBlockState(doorPos.above()).equals(snapshot.get(doorPos.above())), 200);
		step(1, "redo", VireWorld::redo);
		waitFor("redo restored the door", () -> Minecraft.getInstance().level.getBlockState(doorPos.above()).getBlock() instanceof DoorBlock, 200);
		step(1, "frame a screenshot", mc -> {
			// behind and above the body, looking at the build
			VireMode.session().camera.snapTo(body.x - 4, body.y + 4, body.z - 7, -30, 35);
		});
		step(10, "screenshot", mc -> Screenshot.grab(mc.gameDirectory, "vire-selftest.png", mc.gameRenderer.mainRenderTarget(), 1,
				msg -> VireClient.LOG.info("[selftest] screenshot: {}", msg.getString())));
		step(2, "circuit: redstone block -> repeater -> lamp (paused)", mc -> {
			BlockPos base = BlockPos.containing(body).west(4);
			cBlock = base;
			cRepeater = base.south();
			cLamp = base.south(2);
			VireWorld.placeState(cLamp, Blocks.REDSTONE_LAMP.defaultBlockState());
			// facing = input side: reads from the north, powers the lamp to the south
			VireWorld.placeState(cRepeater, Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.NORTH));
			VireWorld.placeState(cBlock, Blocks.REDSTONE_BLOCK.defaultBlockState());
		});
		waitFor("circuit placed", () -> is(cBlock, Blocks.REDSTONE_BLOCK) && is(cRepeater, Blocks.REPEATER) && is(cLamp, Blocks.REDSTONE_LAMP), 100);
		step(1, "paused: repeater waits", mc -> {
			check(VireMode.session().frozen, "still paused");
			check(!powered(mc), "repeater not powered while paused");
		});
		stepOnce();
		step(1, "1 step", mc -> check(!powered(mc), "repeater not powered after 1 game tick"));
		stepOnce();
		step(1, "2 steps", mc -> {
			check(powered(mc), "repeater powered after 2 game ticks");
			check(lit(mc), "lamp lit as soon as the repeater is");
			tickMark = VireMode.session().sandboxTick;
			click(VireClient.pauseKey());
		});
		waitFor("running after K", () -> !VireMode.session().frozen, 100);
		waitFor("sandbox ticks advance while running", () -> VireMode.session().sandboxTick - tickMark >= 10, 200);
		step(1, "faster", mc -> click(VireClient.fasterKey()));
		waitFor("] raises 20 -> 40 TPS", () -> VireMode.session().tickRate == 40, 100);
		step(1, "slower, pause", mc -> {
			click(VireClient.slowerKey());
			click(VireClient.pauseKey());
		});
		waitFor("paused again at 20 TPS", () -> VireMode.session().frozen && VireMode.session().tickRate == 20, 100);
		step(1, "cut the input", mc -> VireWorld.breakAt(cBlock));
		waitFor("input removed", () -> Minecraft.getInstance().level.getBlockState(cBlock).isAir(), 100);
		for (int i = 1; i <= 6; i++) {
			step(1, "sample", mc -> offSteps.add((powered(mc) ? "R" : "r") + (lit(mc) ? "L" : "l")));
			stepOnce();
		}
		step(1, "off timing", mc -> {
			offSteps.add((powered(mc) ? "R" : "r") + (lit(mc) ? "L" : "l"));
			// after 0..6 steps: repeater off after 2 gt, lamp 4 gt after that
			check(offSteps.equals(List.of("RL", "RL", "rL", "rL", "rL", "rL", "rl")), "repeater off at 2 gt, lamp at 6 gt: " + offSteps);
			pistonPos = BlockPos.containing(body).west(7);
			VireWorld.placeState(pistonPos.above(), Blocks.SMOOTH_STONE.defaultBlockState());
			VireWorld.placeState(pistonPos, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.UP));
			VireWorld.placeState(pistonPos.north(), Blocks.REDSTONE_BLOCK.defaultBlockState());
		});
		waitFor("piston placed", () -> is(pistonPos.north(), Blocks.REDSTONE_BLOCK), 100);
		for (int i = 0; i < 4; i++) {
			stepOnce();
		}
		step(1, "piston pushed", mc -> {
			BlockState piston = mc.level.getBlockState(pistonPos);
			check(piston.is(Blocks.PISTON) && piston.getValue(PistonBaseBlock.EXTENDED), "piston extended: " + piston);
			check(mc.level.getBlockState(pistonPos.above()).is(Blocks.PISTON_HEAD), "piston head above: " + mc.level.getBlockState(pistonPos.above()));
			check(mc.level.getBlockState(pistonPos.above(2)).is(Blocks.SMOOTH_STONE), "stone pushed up: " + mc.level.getBlockState(pistonPos.above(2)));
			click(VireClient.resetKey());
		});
		waitFor("reset rebuilds the design, paused, piston retracted",
				() -> VireMode.session().frozen && is(pistonPos.above(), Blocks.SMOOTH_STONE), 200);
		step(1, "sand in the air", mc -> {
			sandPos = BlockPos.containing(body).north(6).above(5);
			VireWorld.placeState(sandPos, Blocks.SAND.defaultBlockState());
		});
		waitFor("sand placed", () -> is(sandPos, Blocks.SAND), 100);
		stepOnce();
		stepOnce();
		stepOnce();
		waitFor("falling sand is shown as an entity", () -> dev.vire.client.sandbox.EntityMirror.count() == 1
				&& !Minecraft.getInstance().level.getEntitiesOfClass(net.minecraft.world.entity.item.FallingBlockEntity.class,
						new net.minecraft.world.phys.AABB(sandPos).inflate(3)).isEmpty(), 100);
		step(1, "run", mc -> click(VireClient.pauseKey()));
		waitFor("sand landed, entity gone", () -> is(sandPos.below(5), Blocks.SAND) && dev.vire.client.sandbox.EntityMirror.count() == 0, 200);
		step(1, "pause", mc -> click(VireClient.pauseKey()));
		waitFor("paused", () -> VireMode.session().frozen, 100);
		step(5, "real server changes", mc -> {
			mc.player.connection.sendCommand("setblock " + p1.getX() + " " + p1.getY() + " " + p1.getZ() + " minecraft:gold_block");
			mc.player.connection.sendCommand("setblock " + p2.getX() + " " + p2.getY() + " " + p2.getZ() + " minecraft:gold_block");
		});
		waitFor("server updates intercepted", () -> VireMode.session().shadow.interceptedUpdates() >= 2, 200);
		step(5, "real changes held back", mc -> {
			check(!mc.level.getBlockState(p1).is(Blocks.GOLD_BLOCK), "virtual block at p1 stays while active");
			check(mc.level.getBlockState(p2).equals(p2Before), "real change at p2 not shown while active");
			check(VireMode.session().shadow.interceptedUpdates() >= 2, "server updates intercepted: " + VireMode.session().shadow.interceptedUpdates());
			check(PacketGuard.dropped() == 0, "no packets reached the safety net: " + PacketGuard.droppedByType());
			// whatever the server changed meanwhile (our /setblocks, but also e.g. grass decaying) must win on exit
			VireSession vs = VireMode.session();
			realChanges.clear();
			for (long k : vs.shadow.serverUpdatedKeys()) {
				realChanges.put(VireWorld.pos(k), vs.shadow.restoreList().get(k));
			}
			click(VireClient.toggleKey());
		});
		waitFor("Vire Mode left", () -> !VireMode.active(), 100);
		step(3, "restore exact", mc -> {
			int wrong = countWrong(mc, true);
			check(wrong == 0, "region restored exactly (" + wrong + " wrong of " + snapshot.size() + ")");
			check(mc.player.position().distanceToSqr(body) < 1e-6, "body still in place after exit");
			click(VireClient.toggleKey());
		});
		waitFor("re-entered", VireMode::active, 200);
		step(5, "damage ends Vire Mode", mc -> {
			mc.player.connection.sendCommand("damage " + mc.player.getName().getString() + " 1");
		});
		step(30, "check forced exit, clean up", mc -> {
			check(!VireMode.active(), "damage forced exit");
			check(PacketGuard.dropped() == 0, "no packets reached the safety net in session 2");
			mc.player.connection.sendCommand("setblock " + p1.getX() + " " + p1.getY() + " " + p1.getZ() + " " + BlockStateParser.serialize(p1Before));
			mc.player.connection.sendCommand("setblock " + p2.getX() + " " + p2.getY() + " " + p2.getZ() + " " + BlockStateParser.serialize(p2Before));
			mc.player.connection.sendCommand("effect give @s minecraft:instant_health 1 1");
		});
		step(10, "report", mc -> {
			if (FAILURES.isEmpty()) {
				VireClient.LOG.info("[selftest] VIRE SELFTEST PASS ({} steps)", STEPS.size());
			} else {
				VireClient.LOG.error("[selftest] VIRE SELFTEST FAIL: {}", FAILURES);
			}
			done = true;
			if (MODE.equals("quit")) {
				mc.stop();
			}
		});
	}

	public static void tick(Minecraft mc) {
		if (MODE.isEmpty() || done || mc.player == null || mc.level == null) {
			return;
		}
		ticks++;
		if (ticks < 100 || !mc.player.onGround() || ticks < waitUntil || index >= STEPS.size()) {
			return;
		}
		Step s = STEPS.get(index++);
		VireClient.LOG.info("[selftest] step {}: {}", index, s.name());
		try {
			s.action().accept(mc);
		} catch (RuntimeException e) {
			FAILURES.add(s.name() + ": " + e);
			VireClient.LOG.error("[selftest] step {} threw", s.name(), e);
			done = true;
			VireMode.exit(mc, "selftest error", true);
			VireClient.LOG.error("[selftest] VIRE SELFTEST FAIL: {}", FAILURES);
			return;
		}
		if (index < STEPS.size()) {
			waitUntil = ticks + STEPS.get(index).delay();
		}
	}

	private static final List<List<Map<String, String>>> REAL = new ArrayList<>();
	private static boolean markerGold;

	/** Circuit test kit (PLAN.md §17): real server vs sandbox, tick by tick. */
	private static void kitSteps() {
		step(0, "go near the kit", mc -> {
			mc.player.connection.sendCommand("tp @s 395 -60 395");
			dev.vire.client.sandbox.Sandbox.ensureStarted();
		});
		waitFor("sandbox ready", dev.vire.client.sandbox.Sandbox::ready, 2400);
		step(40, "start the sandbox side", mc -> KitTest.startSandboxSide(dev.vire.client.sandbox.Sandbox.server()));
		List<KitTest.Circuit> circuits = KitTest.circuits();
		for (int i = 0; i < circuits.size(); i++) {
			KitTest.Circuit c = circuits.get(i);
			step(1, "kit clear: " + c.name(), mc -> {
				for (String cmd : c.clear()) {
					mc.player.connection.sendCommand(cmd);
				}
			});
			step(30, "kit build: " + c.name(), mc -> {
				REAL.add(new ArrayList<>());
				for (String cmd : c.setup()) {
					mc.player.connection.sendCommand(cmd);
				}
				toggleMarker(mc, c);
			});
			waitFor("marker", () -> markerArrived(c), 200);
			step(1, "record", mc -> REAL.getLast().add(c.read(mc.level)));
			for (int k = 0; k < KitTest.STEPS; k++) {
				step(1, "tick step", mc -> {
					mc.player.connection.sendCommand("tick step 1");
					toggleMarker(mc, c);
				});
				waitFor("marker", () -> markerArrived(c), 200);
				step(1, "record", mc -> REAL.getLast().add(c.read(mc.level)));
			}
			step(1, "unfreeze", mc -> mc.player.connection.sendCommand("tick unfreeze"));
		}
		waitFor("sandbox side done", () -> KitTest.SANDBOX_RESULT.get() != null, 2400);
		step(1, "compare", mc -> {
			List<List<Map<String, String>>> sb = KitTest.SANDBOX_RESULT.get();
			check(sb.size() == circuits.size(), "sandbox ran every circuit (" + sb.size() + ")");
			for (int i = 0; i < Math.min(sb.size(), circuits.size()); i++) {
				KitTest.Circuit c = circuits.get(i);
				String d = KitTest.diff(c, REAL.get(i), sb.get(i));
				check(d == null, d == null ? c.name() + ": identical for " + KitTest.STEPS + " game ticks" : d);
				// show that something happened, so "identical" is not "both did nothing"
				long changes = 0;
				for (int t = 1; t < REAL.get(i).size(); t++) {
					if (!REAL.get(i).get(t).equals(REAL.get(i).get(t - 1))) {
						changes++;
					}
				}
				VireClient.LOG.info("[selftest] kit {}: state changed in {} of {} ticks", c.name(), changes, KitTest.STEPS);
				check(changes > 0, c.name() + " actually does something");
			}
		});
		step(1, "report", SelfTest::report);
	}

	/** Leak check: 10 enter/exit cycles, then 3 sandbox restarts; heap must not keep growing. */
	private static void cycleSteps() {
		step(0, "quiet spot", mc -> {
			mc.player.connection.sendCommand("tp @s 300 -60 300");
			dev.vire.client.sandbox.Sandbox.ensureStarted();
		});
		waitFor("sandbox ready", dev.vire.client.sandbox.Sandbox::ready, 2400);
		step(40, "heap at start", mc -> heapStart = heapMb());
		for (int i = 0; i < 10; i++) {
			step(1, "enter", mc -> click(VireClient.toggleKey()));
			waitFor("loaded", () -> VireMode.active() && VireMode.session().loaded, 400);
			step(5, "exit", mc -> click(VireClient.toggleKey()));
			waitFor("left", () -> !VireMode.active(), 100);
		}
		step(40, "heap after 10 sessions", mc -> {
			long h = heapMb();
			VireClient.LOG.info("[selftest] heap {} MB -> {} MB after 10 enter/exit cycles", heapStart, h);
			check(h - heapStart < 60, "10 enter/exit cycles leave the heap within 60 MB (" + (h - heapStart) + " MB)");
		});
		for (int i = 0; i < 3; i++) {
			step(1, "stop sandbox", mc -> dev.vire.client.sandbox.Sandbox.stop());
			step(100, "start sandbox", mc -> dev.vire.client.sandbox.Sandbox.ensureStarted());
			waitFor("sandbox ready again", dev.vire.client.sandbox.Sandbox::ready, 2400);
		}
		step(100, "heap after 3 restarts", mc -> {
			long h = heapMb();
			VireClient.LOG.info("[selftest] heap {} MB -> {} MB after 3 sandbox restarts", heapStart, h);
			check(h - heapStart < 100, "3 sandbox restarts leave the heap within 100 MB (" + (h - heapStart) + " MB)");
		});
		step(1, "report", SelfTest::report);
	}

	private static long heapMb() {
		System.gc();
		Runtime rt = Runtime.getRuntime();
		return (rt.totalMemory() - rt.freeMemory()) >> 20;
	}

	private static void toggleMarker(Minecraft mc, KitTest.Circuit c) {
		markerGold = !markerGold;
		BlockPos m = c.marker();
		mc.player.connection.sendCommand("setblock " + m.getX() + " " + m.getY() + " " + m.getZ() + " minecraft:" + (markerGold ? "gold_block" : "iron_block"));
	}

	private static boolean markerArrived(KitTest.Circuit c) {
		return is(c.marker(), markerGold ? Blocks.GOLD_BLOCK : Blocks.IRON_BLOCK);
	}

	private static void report(Minecraft mc) {
		if (FAILURES.isEmpty()) {
			VireClient.LOG.info("[selftest] VIRE SELFTEST PASS ({} steps)", STEPS.size());
		} else {
			VireClient.LOG.error("[selftest] VIRE SELFTEST FAIL: {}", FAILURES);
		}
		done = true;
		if (!MODE.equals("run")) {
			mc.stop();
		}
	}

	/** Points the camera down at {@code target} and waits until the crosshair is on it. */
	private static void aim(java.util.function.Supplier<BlockPos> target) {
		step(1, "aim", mc -> aimDown(VireMode.session(), target.get()));
		waitFor("aimed", () -> Minecraft.getInstance().hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
				&& hit.getBlockPos().equals(target.get()), 200);
	}

	/** Presses the step key and waits until the sandbox clock moved one tick. */
	private static void stepOnce() {
		step(1, "step", mc -> {
			tickMark = VireMode.session().sandboxTick;
			click(VireClient.stepKey());
		});
		waitFor("stepped", () -> VireMode.session().sandboxTick == tickMark + 1, 100);
	}

	private static boolean is(BlockPos pos, net.minecraft.world.level.block.Block block) {
		return Minecraft.getInstance().level.getBlockState(pos).is(block);
	}

	/** A step that repeats every tick until {@code done} holds; fails after {@code maxTicks}. */
	private static void waitFor(String name, java.util.function.BooleanSupplier done, int maxTicks) {
		step(1, name, mc -> {
			if (done.getAsBoolean()) {
				waited = 0;
				if (!name.equals("aimed") && !name.equals("stepped") && !name.equals("marker") && !name.equals("loaded")
						&& !name.equals("left")) {
					check(true, name);
				}
			} else if (++waited > maxTicks) {
				waited = 0;
				check(false, name + " (timed out)");
			} else {
				index--;
			}
		});
	}

	private static boolean powered(Minecraft mc) {
		BlockState s = mc.level.getBlockState(cRepeater);
		return s.getBlock() instanceof RepeaterBlock && s.getValue(RepeaterBlock.POWERED);
	}

	private static boolean lit(Minecraft mc) {
		BlockState s = mc.level.getBlockState(cLamp);
		return s.is(Blocks.REDSTONE_LAMP) && s.getValue(RedstoneLampBlock.LIT);
	}

	/** Positions in the region whose client state differs from the snapshot (p1/p2 expected gold if {@code gold}). */
	private static int countWrong(Minecraft mc, boolean gold) {
		int wrong = 0;
		for (Map.Entry<BlockPos, BlockState> e : snapshot.entrySet()) {
			BlockPos p = e.getKey();
			BlockState want = gold && (p.equals(p1) || p.equals(p2)) ? Blocks.GOLD_BLOCK.defaultBlockState()
					: gold && realChanges.containsKey(p) ? realChanges.get(p) : e.getValue();
			BlockState got = mc.level.getBlockState(p);
			if (!got.equals(want)) {
				if (wrong < 10) {
					VireClient.LOG.warn("[selftest] {} expected {} got {}", p, want, got);
				}
				wrong++;
			}
		}
		return wrong;
	}

	private static void step(int delay, String name, Consumer<Minecraft> action) {
		STEPS.add(new Step(delay, name, action));
	}

	private static void click(KeyMapping key) {
		KeyMapping.click(KeyMappingHelper.getBoundKeyOf(key));
	}

	private static void set(KeyMapping key, boolean down) {
		InputConstants.Key k = KeyMappingHelper.getBoundKeyOf(key);
		KeyMapping.set(k, down);
	}

	/** Puts the free camera 3 blocks above {@code target}, looking straight down at its top face. */
	private static void aimDown(VireSession s, BlockPos target) {
		s.camera.snapTo(target.getX() + 0.5, target.getY() + 3.2, target.getZ() + 0.5, 0, 90);
	}

	private static void check(boolean ok, String what) {
		if (ok) {
			VireClient.LOG.info("[selftest] ok: {}", what);
		} else {
			FAILURES.add(what);
			VireClient.LOG.error("[selftest] FAILED: {}", what);
		}
	}

	private static <T> T need(T value, String what) {
		if (value == null) {
			throw new IllegalStateException(what);
		}
		return value;
	}
}
