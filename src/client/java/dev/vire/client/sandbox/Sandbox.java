package dev.vire.client.sandbox;

import dev.vire.client.VireClient;
import dev.vire.core.region.Box;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Client-side handle on the sandbox server (PLAN.md §5.3): start, stop, and the two queues between
 * the client thread and the sandbox thread. Everything that touches the sandbox world runs on the
 * sandbox thread through {@link #run}; results come back as {@link MirrorBatch}es in {@link #OUT}.
 */
public final class Sandbox {
	/** Sandbox → client. Drained by the client tick. */
	public static final ConcurrentLinkedQueue<MirrorBatch> OUT = new ConcurrentLinkedQueue<>();

	private static volatile SandboxServer server;
	private static volatile boolean starting;
	private static volatile String error;
	private static volatile long startedAt;
	/** Bumped for every load/reset; batches of older loads are dropped. */
	private static volatile int generation;

	private Sandbox() {
	}

	public static Path dir() {
		return VireClient.STORE.dir().resolve("sandbox");
	}

	/** Starts the sandbox in the background if it is not running or starting. */
	public static synchronized void ensureStarted() {
		if (server != null || starting) {
			return;
		}
		starting = true;
		error = null;
		startedAt = System.nanoTime();
		Thread t = new Thread(() -> {
			try {
				server = SandboxServer.start(dir());
			} catch (Throwable e) {
				error = e.toString();
				VireClient.LOG.error("[Vire] Sandbox failed to start", e);
			} finally {
				starting = false;
			}
		}, "Vire sandbox loader");
		t.setDaemon(true);
		t.start();
	}

	/** The running sandbox server (dev tools only), or {@code null}. */
	public static SandboxServer server() {
		return server;
	}

	public static boolean ready() {
		SandboxServer s = server;
		return s != null && s.isReady() && s.isRunning();
	}

	public static boolean startingOrReady() {
		return starting || server != null;
	}

	/** Why the last start failed, or {@code null}. */
	public static String error() {
		return error;
	}

	public static long secondsSinceStart() {
		return (System.nanoTime() - startedAt) / 1_000_000_000L;
	}

	/** Stops the sandbox (in the background) and wipes its scratch world. */
	public static synchronized void stop() {
		SandboxServer s = server;
		server = null;
		generation++;
		OUT.clear();
		if (s == null) {
			return;
		}
		Thread t = new Thread(() -> {
			try {
				s.halt(true);
				SandboxServer.wipe(dir());
				VireClient.LOG.info("[Vire] Sandbox stopped");
			} catch (Throwable e) {
				VireClient.LOG.warn("[Vire] Sandbox stop failed", e);
			}
		}, "Vire sandbox stopper");
		t.setDaemon(true);
		t.start();
	}

	/** Runs {@code task} on the sandbox thread; does nothing when the sandbox is not ready. */
	public static void run(Consumer<SandboxServer> task) {
		SandboxServer s = server;
		if (s != null && s.isReady()) {
			s.execute(() -> {
				try {
					task.accept(s);
				} catch (Throwable e) {
					VireClient.LOG.error("[Vire] Sandbox task failed", e);
				}
			});
		}
	}

	public static int generation() {
		return generation;
	}

	// ---- operations (called on the client thread) ---------------------------------------------------

	/**
	 * (Re)builds the region in the sandbox from the snapshot and replays the edits, then re-settles and
	 * pauses (PLAN.md §5.4–5.5, §7 reset). Returns the new generation.
	 */
	public static int load(Box box, Map<Long, BlockState> snapshot, Map<Long, CompoundTag> blockEntities, List<SandboxWorld.Edit> edits) {
		int gen = ++generation;
		OUT.clear();
		run(s -> SandboxWorld.load(s, gen, box, snapshot, blockEntities, edits));
		return gen;
	}

	/** Clears the region and pauses. Batches of the old load are dropped. */
	public static void unload() {
		generation++;
		OUT.clear();
		run(SandboxWorld::unload);
	}

	public static void edit(SandboxWorld.Edit edit) {
		run(s -> SandboxWorld.apply(s, edit, true));
	}

	public static void togglePause() {
		run(s -> s.tickRateManager().setFrozen(!s.tickRateManager().isFrozen()));
	}

	public static void step(int ticks) {
		run(s -> {
			s.tickRateManager().setFrozen(true);
			s.tickRateManager().stepGameIfPaused(ticks);
		});
	}

	public static void setTickRate(float rate) {
		run(s -> s.tickRateManager().setTickRate(rate));
	}

	/** Same block position as the client uses. */
	static BlockPos pos(long key) {
		return dev.vire.client.interact.VireWorld.pos(key);
	}
}
