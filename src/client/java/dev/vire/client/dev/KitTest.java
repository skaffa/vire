package dev.vire.client.dev;

import dev.vire.client.VireClient;
import dev.vire.client.sandbox.SandboxServer;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit test kit (PLAN.md §17): the same {@code /setblock} commands run on the real test server and
 * in the sandbox, both frozen and stepped one game tick at a time with {@code /tick step 1}. The block
 * states of each circuit are recorded after every tick and must be identical. Using the same
 * commands on both sides makes the placement semantics equal, so only the simulation is compared.
 */
public final class KitTest {
	/** Steps recorded per circuit (plus the state right after setup). */
	public static final int STEPS = 30;

	/** One circuit: name, origin, the commands that build it (relative to the origin), the area to watch. */
	public record Circuit(String name, BlockPos origin, List<String> build, int sizeX, int sizeY, int sizeZ) {
		/**
		 * Phase 1, while the world runs: load and clear the area. Running matters: block ticks still queued
		 * from an earlier run at these positions (a clock) fire on empty air instead of the new circuit.
		 */
		public List<String> clear() {
			List<String> out = new ArrayList<>();
			out.add("tick unfreeze");
			out.add(String.format("forceload add %d %d %d %d", origin.getX() - 2, origin.getZ() - 2, origin.getX() + sizeX + 2, origin.getZ() + sizeZ + 2));
			out.add(String.format("fill %d %d %d %d %d %d minecraft:air", origin.getX() - 1, origin.getY(), origin.getZ() - 1,
					origin.getX() + sizeX, origin.getY() + sizeY, origin.getZ() + sizeZ));
			out.add(String.format("fill %d %d %d %d %d %d minecraft:smooth_stone", origin.getX() - 1, origin.getY() - 1, origin.getZ() - 1,
					origin.getX() + sizeX, origin.getY() - 1, origin.getZ() + sizeZ));
			return out;
		}

		/** Phase 2: freeze, then build (the last command usually triggers the circuit). */
		public List<String> setup() {
			List<String> out = new ArrayList<>();
			out.add("tick freeze");
			for (String b : build) {
				out.add(abs(b));
			}
			return out;
		}

		/** "setblock 1 0 0 x" relative to the origin → absolute coordinates. */
		private String abs(String cmd) {
			String[] p = cmd.split(" ", 5);
			int x = origin.getX() + Integer.parseInt(p[1]);
			int y = origin.getY() + Integer.parseInt(p[2]);
			int z = origin.getZ() + Integer.parseInt(p[3]);
			return p[0] + " " + x + " " + y + " " + z + " " + p[4];
		}

		/**
		 * Real-world marker, toggled after every step so the client knows the step arrived. It sits in the
		 * same chunk section as the circuit, so both arrive in one block-update packet.
		 */
		public BlockPos marker() {
			return origin.offset(0, 0, sizeZ + 2);
		}

		/** States of the watched area, keyed "dx,dy,dz". */
		public Map<String, String> read(Level level) {
			Map<String, String> m = new LinkedHashMap<>();
			for (int x = -1; x <= sizeX; x++) {
				for (int y = 0; y <= sizeY; y++) {
					for (int z = -1; z <= sizeZ; z++) {
						m.put(x + "," + y + "," + z, level.getBlockState(origin.offset(x, y, z)).toString());
					}
				}
			}
			return m;
		}
	}

	public static List<Circuit> circuits() {
		List<Circuit> c = new ArrayList<>();
		int x = 400;
		// 1. repeater chain with mixed delays into a lamp; triggered by a redstone block
		c.add(new Circuit("repeater chain 1-3-2", new BlockPos(x, -60, 400), List.of(
				"setblock 1 0 0 minecraft:repeater[facing=west,delay=1]",
				"setblock 2 0 0 minecraft:repeater[facing=west,delay=3]",
				"setblock 3 0 0 minecraft:repeater[facing=west,delay=2]",
				"setblock 4 0 0 minecraft:redstone_lamp",
				"setblock 0 0 0 minecraft:redstone_block"), 5, 1, 1));
		// 2. observer clock: two observers watching each other; placing the second starts it
		c.add(new Circuit("observer clock", new BlockPos(x + 12, -60, 400), List.of(
				"setblock 1 0 0 minecraft:observer[facing=east]",
				"setblock 0 0 0 minecraft:redstone_lamp",
				"setblock 2 0 0 minecraft:observer[facing=west]"), 4, 1, 1));
		// 3. comparator in subtract mode: rear 15 (block) minus side 11 (dust chain) = 4
		c.add(new Circuit("comparator subtract", new BlockPos(x + 24, -60, 400), List.of(
				"setblock 1 0 0 minecraft:comparator[facing=west,mode=subtract]",
				"setblock 2 0 0 minecraft:redstone_wire",
				"setblock 3 0 0 minecraft:redstone_wire",
				"setblock 1 0 1 minecraft:redstone_wire",
				"setblock 1 0 2 minecraft:redstone_wire",
				"setblock 1 0 3 minecraft:redstone_wire",
				"setblock 1 0 4 minecraft:redstone_wire",
				"setblock 1 0 5 minecraft:redstone_wire",
				"setblock 1 0 6 minecraft:redstone_block",
				"setblock 0 0 0 minecraft:redstone_block"), 5, 1, 7));
		// 4. quasi-connectivity: a redstone block powers the space above the piston (BUD), then a block
		//    update next to the piston makes it fire and push sand up
		c.add(new Circuit("QC piston + BUD", new BlockPos(x + 36, -60, 400), List.of(
				"setblock 1 0 0 minecraft:sticky_piston[facing=up]",
				"setblock 1 1 0 minecraft:sand",
				"setblock 2 1 0 minecraft:redstone_block",
				"setblock 0 0 0 minecraft:stone"), 3, 3, 2));
		// 5. gravity: sand placed in the air falls, lands on a slab; a repeater line runs next to it
		c.add(new Circuit("falling sand", new BlockPos(x + 48, -60, 400), List.of(
				"setblock 0 0 0 minecraft:smooth_stone_slab",
				"setblock 0 4 0 minecraft:sand",
				"setblock 0 5 0 minecraft:gravel"), 1, 6, 1));
		return c;
	}

	/** Runs every circuit in the sandbox on a background thread; blocks until done. Sandbox must be ready. */
	public static List<List<Map<String, String>>> runInSandbox(SandboxServer server) throws InterruptedException {
		List<List<Map<String, String>>> all = new ArrayList<>();
		for (Circuit c : circuits()) {
			List<Map<String, String>> seq = new ArrayList<>();
			on(server, () -> {
				for (String cmd : c.clear()) {
					run(server, cmd);
				}
				return null;
			});
			long until = System.nanoTime() + 10_000_000_000L;
			while (!on(server, () -> dev.vire.client.sandbox.SandboxWorld.allTicking(server.overworld(), (c.origin().getX() - 2) >> 4,
					(c.origin().getZ() - 2) >> 4, (c.origin().getX() + c.sizeX() + 2) >> 4, (c.origin().getZ() + c.sizeZ() + 2) >> 4))
					&& System.nanoTime() < until) {
				Thread.sleep(20);
			}
			Thread.sleep(1000); // let anything queued in the cleared area run out
			on(server, () -> {
				for (String cmd : c.setup()) {
					run(server, cmd);
				}
				return null;
			});
			seq.add(on(server, () -> c.read(server.overworld())));
			StringBuilder times = new StringBuilder();
			times.append(on(server, () -> server.overworld().getGameTime() + (server.tickRateManager().isFrozen() ? "F" : "R")));
			for (int i = 0; i < STEPS; i++) {
				long t = on(server, () -> server.overworld().getGameTime());
				on(server, () -> {
					run(server, "tick step 1");
					return null;
				});
				long stepUntil = System.nanoTime() + 2_000_000_000L;
				while (on(server, () -> server.overworld().getGameTime()) == t && System.nanoTime() < stepUntil) {
					Thread.sleep(2);
				}
				seq.add(on(server, () -> c.read(server.overworld())));
				times.append(' ').append(on(server, () -> server.overworld().getGameTime() + (server.tickRateManager().isFrozen() ? "F" : "R")));
			}
			VireClient.LOG.info("[selftest] kit sandbox {} game times: {}", c.name(), times);
			on(server, () -> {
				run(server, "tick unfreeze");
				return null;
			});
			all.add(seq);
		}
		return all;
	}

	private static void run(SandboxServer server, String cmd) {
		try {
			int r = server.getCommands().getDispatcher().execute(cmd, server.createCommandSourceStack().withSuppressedOutput());
			if (r <= 0) {
				VireClient.LOG.warn("[selftest] sandbox command returned {}: {}", r, cmd);
			}
		} catch (Exception e) {
			VireClient.LOG.warn("[selftest] sandbox command failed: {}: {}", cmd, e.getMessage());
		}
	}

	private static <T> T on(SandboxServer server, java.util.function.Supplier<T> task) {
		return server.submit(task).join();
	}

	/** First difference between the real and sandbox sequences of one circuit, or {@code null}. */
	public static String diff(Circuit c, List<Map<String, String>> real, List<Map<String, String>> sandbox) {
		int n = Math.min(real.size(), sandbox.size());
		for (int i = 0; i < n; i++) {
			for (Map.Entry<String, String> e : real.get(i).entrySet()) {
				String s = sandbox.get(i).get(e.getKey());
				if (!e.getValue().equals(s)) {
					StringBuilder sb = new StringBuilder();
					for (int t = 0; t < n; t++) {
						sb.append("\n  t=").append(t).append(" real ").append(short_(real.get(t).get(e.getKey())))
								.append(" | sandbox ").append(short_(sandbox.get(t).get(e.getKey())));
					}
					VireClient.LOG.warn("[selftest] kit {} at {}:{}", c.name(), e.getKey(), sb);
					return c.name() + " differs after " + i + " game ticks at " + e.getKey() + ": real " + e.getValue() + ", sandbox " + s;
				}
			}
		}
		return real.size() != sandbox.size() ? c.name() + ": " + real.size() + " real vs " + sandbox.size() + " sandbox samples" : null;
	}

	private static String short_(String state) {
		return state == null ? "null" : state.replace("Block{minecraft:", "").replace("}", "");
	}

	/** Holder for the sandbox side's result, filled by a background thread. */
	public static final AtomicReference<List<List<Map<String, String>>>> SANDBOX_RESULT = new AtomicReference<>();

	public static void startSandboxSide(SandboxServer server) {
		Thread t = new Thread(() -> {
			try {
				SANDBOX_RESULT.set(runInSandbox(server));
			} catch (Throwable e) {
				VireClient.LOG.error("[selftest] kit sandbox side failed", e);
				SANDBOX_RESULT.set(List.of());
			}
		}, "Vire kit");
		t.setDaemon(true);
		t.start();
	}

	static Minecraft mc() {
		return Minecraft.getInstance();
	}
}
