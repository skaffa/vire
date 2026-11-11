package dev.vire.client.dev;

import com.mojang.blaze3d.platform.InputConstants;

import dev.vire.client.VireClient;
import dev.vire.client.interact.VireWorld;
import dev.vire.client.interact.VirtualHotbar;
import dev.vire.client.mode.PacketGuard;
import dev.vire.client.mode.VireMode;
import dev.vire.client.mode.VireSession;
import dev.vire.core.region.Box;

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

	private SelfTest() {
	}

	public static void init() {
		if (MODE.isEmpty()) {
			return;
		}
		VireClient.LOG.info("[selftest] enabled ({})", MODE);
		// Away from running circuits, so "restored exactly" can compare with the snapshot. (Near a running
		// clock, the latest real states win on exit, which is correct but not comparable to a snapshot.)
		step(0, "go to a quiet spot", mc -> mc.player.connection.sendCommand("tp @s 300 -60 300"));
		step(80, "enter", mc -> {
			click(VireClient.toggleKey());
		});
		step(3, "snapshot", mc -> {
			VireSession s = need(VireMode.session(), "Vire Mode did not start");
			box = s.box;
			body = mc.player.position();
			snapshot = new HashMap<>();
			for (int x = box.minX(); x <= box.maxX(); x++) {
				for (int y = box.minY(); y <= box.maxY(); y++) {
					for (int z = box.minZ(); z <= box.maxZ(); z++) {
						BlockPos p = new BlockPos(x, y, z);
						snapshot.put(p, mc.level.getBlockState(p));
					}
				}
			}
			ground = mc.player.blockPosition().below().east(3);
			p1 = ground.above();
			p2 = ground.south(4);
			p1Before = snapshot.get(p1);
			p2Before = snapshot.get(p2);
			aimDown(s, ground);
		});
		step(3, "place stone", mc -> {
			click(mc.options.keyHotbarSlots[6]); // smooth stone
			click(mc.options.keyUse);
		});
		step(3, "check stone + place repeater", mc -> {
			check(mc.level.getBlockState(p1).is(Blocks.SMOOTH_STONE), "smooth stone at p1, got " + mc.level.getBlockState(p1));
			aimDown(VireMode.session(), p1);
			click(mc.options.keyHotbarSlots[1]); // repeater
			click(mc.options.keyUse);
		});
		step(3, "check repeater + break it", mc -> {
			check(mc.level.getBlockState(p1.above()).getBlock() instanceof RepeaterBlock, "repeater above p1, got " + mc.level.getBlockState(p1.above()));
			aimDown(VireMode.session(), p1.above());
			click(mc.options.keyAttack);
		});
		step(3, "check broken + keys that would leak", mc -> {
			check(mc.level.getBlockState(p1.above()).isAir(), "repeater removed");
			click(mc.options.keyDrop);
			click(mc.options.keySwapOffhand);
			click(mc.options.keyPickItem);
			click(mc.options.keyInventory);
		});
		step(3, "palette opened", mc -> {
			check(mc.gui.screen() instanceof dev.vire.client.interact.PaletteScreen, "inventory key opens the palette, got " + mc.gui.screen());
			mc.gui.setScreen(null);
			aimDown(VireMode.session(), p1);
			click(mc.options.keyHotbarSlots[6]);
			set(mc.options.keyUse, true); // hold: builds a short tower
		});
		step(20, "release use, hold attack", mc -> {
			set(mc.options.keyUse, false);
			check(!mc.level.getBlockState(p1.above()).isAir(), "holding use repeats placement");
			aimDown(VireMode.session(), p1.above(3));
			set(mc.options.keyAttack, true);
		});
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
		});
		step(2, "door + undo/redo", mc -> {
			VireSession s = VireMode.session();
			doorPos = ground.north(3).above();
			VirtualHotbar.set(8, new ItemStack(Items.OAK_DOOR));
			click(mc.options.keyHotbarSlots[8]);
			aimDown(s, ground.north(3));
		});
		step(3, "place door", mc -> click(mc.options.keyUse));
		step(3, "check door, undo", mc -> {
			check(mc.level.getBlockState(doorPos).getBlock() instanceof DoorBlock, "door lower half");
			BlockState upper = mc.level.getBlockState(doorPos.above());
			check(upper.getBlock() instanceof DoorBlock && upper.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER, "door upper half");
			VireWorld.undo(mc);
		});
		step(2, "check undo, redo", mc -> {
			check(mc.level.getBlockState(doorPos).equals(snapshot.get(doorPos)), "undo removed door lower half");
			check(mc.level.getBlockState(doorPos.above()).equals(snapshot.get(doorPos.above())), "undo removed door upper half");
			VireWorld.redo(mc);
		});
		step(2, "frame a screenshot", mc -> {
			check(mc.level.getBlockState(doorPos.above()).getBlock() instanceof DoorBlock, "redo restored door");
			// behind and above the body, looking at the build (yaw -45 = towards +x +z)
			VireMode.session().camera.snapTo(body.x - 4, body.y + 4, body.z - 7, -30, 35);
		});
		step(10, "screenshot", mc -> Screenshot.grab(mc.gameDirectory, "vire-selftest.png", mc.gameRenderer.mainRenderTarget(), 1,
				msg -> VireClient.LOG.info("[selftest] screenshot: {}", msg.getString())));
		step(5, "real server changes", mc -> {
			mc.player.connection.sendCommand("setblock " + p1.getX() + " " + p1.getY() + " " + p1.getZ() + " minecraft:gold_block");
			mc.player.connection.sendCommand("setblock " + p2.getX() + " " + p2.getY() + " " + p2.getZ() + " minecraft:gold_block");
		});
		step(30, "real changes held back", mc -> {
			check(!mc.level.getBlockState(p1).is(Blocks.GOLD_BLOCK), "virtual block at p1 stays while active");
			check(mc.level.getBlockState(p2).equals(p2Before), "real change at p2 not shown while active");
			check(VireMode.session().shadow.interceptedUpdates() >= 2, "server updates intercepted: " + VireMode.session().shadow.interceptedUpdates());
			check(PacketGuard.dropped() == 0, "no packets reached the safety net: " + PacketGuard.droppedByType());
			click(VireClient.toggleKey());
		});
		step(3, "restore exact", mc -> {
			check(!VireMode.active(), "Vire Mode left");
			int wrong = 0;
			for (Map.Entry<BlockPos, BlockState> e : snapshot.entrySet()) {
				BlockPos p = e.getKey();
				BlockState want = p.equals(p1) || p.equals(p2) ? Blocks.GOLD_BLOCK.defaultBlockState() : e.getValue();
				BlockState got = mc.level.getBlockState(p);
				if (!got.equals(want)) {
					if (wrong < 10) {
						VireClient.LOG.warn("[selftest] {} expected {} got {}", p, want, got);
					}
					wrong++;
				}
			}
			check(wrong == 0, "region restored exactly (" + wrong + " wrong of " + snapshot.size() + ")");
			check(mc.player.position().distanceToSqr(body) < 1e-6, "body still in place after exit");
			click(VireClient.toggleKey());
		});
		step(5, "damage ends Vire Mode", mc -> {
			check(VireMode.active(), "re-entered");
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
