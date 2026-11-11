package dev.vire.client.sandbox;

import dev.vire.client.VireClient;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * M2 spike (PLAN.md §16): can Vire run a private server inside the client, and what does it cost?
 * Boots {@link SandboxServer}, builds redstone block → repeater → lamp, checks vanilla timing with
 * pause and single steps, measures boot time, memory and tick rate, then shuts down.
 *
 * <p>Runs on its own thread so the game keeps rendering; results go to chat and the log.
 */
public final class SandboxSpike {
	private static final AtomicBoolean RUNNING = new AtomicBoolean();

	private SandboxSpike() {
	}

	public static boolean running() {
		return RUNNING.get();
	}

	public static void start(Minecraft mc) {
		if (!RUNNING.compareAndSet(false, true)) {
			say(mc, "Spike already running", ChatFormatting.YELLOW);
			return;
		}
		Thread t = new Thread(() -> {
			try {
				run(mc);
			} catch (Throwable e) {
				VireClient.LOG.error("[Vire] Spike failed", e);
				say(mc, "Spike failed: " + e, ChatFormatting.RED);
			} finally {
				RUNNING.set(false);
			}
		}, "Vire spike");
		t.setDaemon(true);
		t.start();
	}

	private static void run(Minecraft mc) throws Exception {
		List<String> report = new ArrayList<>();
		List<String> fails = new ArrayList<>();
		Path dir = VireClient.STORE.dir().resolve("sandbox");

		long heapBefore = usedHeapMb();
		long t0 = System.nanoTime();
		say(mc, "Spike: starting sandbox server…", ChatFormatting.AQUA);
		SandboxServer server = SandboxServer.start(dir);
		while (!server.isReady()) {
			if (!server.isRunning() && server.getTickCount() == 0 && System.nanoTime() - t0 > 120_000_000_000L) {
				throw new IllegalStateException("sandbox did not start within 120 s");
			}
			Thread.sleep(10);
		}
		long bootMs = (System.nanoTime() - t0) / 1_000_000;
		report.add("boot " + bootMs + " ms (resources " + server.resourceLoadMillis + " ms)");

		BlockPos block = new BlockPos(0, 64, 0);
		BlockPos repeater = block.east();
		BlockPos lamp = repeater.east();
		on(server, () -> {
			ServerLevel level = server.overworld();
			level.setChunkForced(0, 0, true);
			for (int x = 0; x <= 2; x++) {
				level.setBlock(new BlockPos(x, 63, 0), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
			}
			level.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState(), Block.UPDATE_ALL);
			// facing = the input side: the repeater reads from the west and powers the lamp to the east
			level.setBlock(repeater, Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.WEST), Block.UPDATE_ALL);
			level.setBlock(block, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
			return null;
		});
		Thread.sleep(1000); // ~20 ticks at 20 TPS
		BlockState[] s = on(server, () -> new BlockState[] {server.overworld().getBlockState(repeater), server.overworld().getBlockState(lamp)});
		check(fails, s[0].getValue(RepeaterBlock.POWERED), "repeater powered after running");
		check(fails, s[1].getValue(RedstoneLampBlock.LIT), "lamp lit after running");

		// pause, cut the input, then step: a 1-tick repeater turns off 2 game ticks later, a lamp 4 later
		ServerTickRateManager rate = server.tickRateManager();
		on(server, () -> {
			rate.setFrozen(true);
			server.overworld().setBlock(block, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			return null;
		});
		Thread.sleep(300);
		check(fails, on(server, () -> server.overworld().getBlockState(repeater).getValue(RepeaterBlock.POWERED)),
				"paused: repeater still powered after 300 ms");
		int[] offAt = {-1, -1};
		for (int step = 1; step <= 8; step++) {
			on(server, () -> rate.stepGameIfPaused(1));
			waitForSteps(server, rate);
			int st = step;
			on(server, () -> {
				ServerLevel l = server.overworld();
				if (offAt[0] < 0 && !l.getBlockState(repeater).getValue(RepeaterBlock.POWERED)) {
					offAt[0] = st;
				}
				if (offAt[1] < 0 && !l.getBlockState(lamp).getValue(RedstoneLampBlock.LIT)) {
					offAt[1] = st;
				}
				return null;
			});
		}
		report.add("step: repeater off after " + offAt[0] + " gt, lamp after " + offAt[1] + " gt");
		check(fails, offAt[0] == 2, "repeater (delay 1) turns off after 2 game ticks, got " + offAt[0]);
		check(fails, offAt[1] == offAt[0] + 4, "lamp turns off 4 game ticks after its input, got " + offAt[1]);

		// tick rate: normal, then 100 TPS
		on(server, () -> {
			rate.setFrozen(false);
			return null;
		});
		int tps20 = measureTps(server, 2000);
		on(server, () -> {
			rate.setTickRate(100);
			return null;
		});
		int tps100 = measureTps(server, 2000);
		report.add("tps " + tps20 + " at 20, " + tps100 + " at 100");
		check(fails, tps20 >= 18 && tps20 <= 22, "~20 TPS by default, got " + tps20);
		check(fails, tps100 >= 80, "~100 TPS when asked, got " + tps100);

		long heapRunning = usedHeapMb();
		report.add("heap " + heapBefore + " MB before, " + heapRunning + " MB running (+" + (heapRunning - heapBefore) + ")");

		long t1 = System.nanoTime();
		server.halt(true);
		long stopMs = (System.nanoTime() - t1) / 1_000_000;
		SandboxServer.wipe(dir);
		long heapAfter = usedHeapMb();
		report.add("stop " + stopMs + " ms, heap after " + heapAfter + " MB");

		String verdict = fails.isEmpty() ? "PASS" : "FAIL " + fails;
		VireClient.LOG.info("[Vire] SPIKE {}: {}", verdict, report);
		for (String r : report) {
			say(mc, "Spike: " + r, ChatFormatting.AQUA);
		}
		say(mc, "Spike: " + verdict, fails.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.RED);
	}

	/** After a step request, wait until the stepping is done. */
	private static void waitForSteps(SandboxServer server, ServerTickRateManager rate) throws InterruptedException {
		long until = System.nanoTime() + 2_000_000_000L;
		while (on(server, rate::isSteppingForward) && System.nanoTime() < until) {
			Thread.sleep(5);
		}
	}

	private static int measureTps(SandboxServer server, long millis) throws InterruptedException {
		int a = on(server, server::getTickCount);
		Thread.sleep(millis);
		int b = on(server, server::getTickCount);
		return (int) Math.round((b - a) * 1000.0 / millis);
	}

	/** Runs {@code task} on the sandbox thread and waits for the result. */
	private static <T> T on(SandboxServer server, Supplier<T> task) {
		return server.submit(task).join();
	}

	private static void check(List<String> fails, boolean ok, String what) {
		VireClient.LOG.info("[Vire] spike {}: {}", ok ? "ok" : "FAILED", what);
		if (!ok) {
			fails.add(what);
		}
	}

	private static long usedHeapMb() {
		System.gc();
		Runtime rt = Runtime.getRuntime();
		return (rt.totalMemory() - rt.freeMemory()) >> 20;
	}

	private static void say(Minecraft mc, String msg, ChatFormatting color) {
		mc.execute(() -> {
			if (mc.player != null) {
				mc.player.sendSystemMessage(Component.literal("[Vire] ").withStyle(ChatFormatting.DARK_AQUA)
						.append(Component.literal(msg).withStyle(color)));
			}
		});
	}
}
