package dev.vire.core.region;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongFunction;

/**
 * Keeps track of the real world while the client level shows virtual blocks (PLAN.md §6).
 *
 * <p>Invariant: for every position, the client level shows the real state <em>unless</em> the
 * position is in {@link #real}; then {@code real} holds the real state. So restoring on exit only has
 * to write back {@code real}.
 *
 * <ul>
 *   <li>Before Vire writes a virtual state, the current level state is captured (once) as real.</li>
 *   <li>Real server block updates inside the box do not reach the level: they only update
 *       {@code real}, so the virtual view stays stable.</li>
 *   <li>Block entity data from the server inside the box is held and replayed after restore.</li>
 *   <li>A full chunk resend overwrites the level with real data; virtual states in that chunk must then
 *       be written again ({@link #onChunkReloaded}).</li>
 * </ul>
 *
 * @param <S> block state type (Minecraft's {@code BlockState} in the client, strings in tests)
 * @param <B> block entity data packet type
 */
public final class ShadowRegion<S, B> {
	private final Box box;
	private final Map<Long, S> real = new HashMap<>();
	private final Map<Long, S> virtual = new HashMap<>();
	private final LinkedHashMap<Long, B> pendingBlockEntityData = new LinkedHashMap<>();
	private int interceptedUpdates;
	private final java.util.Set<Long> serverUpdated = new java.util.HashSet<>();

	public ShadowRegion(Box box) {
		this.box = Objects.requireNonNull(box);
	}

	public Box box() {
		return box;
	}

	/** Call before Vire writes {@code key} in the level; {@code current} is what the level shows now. */
	public void beforeVirtualWrite(long key, S current) {
		if (!real.containsKey(key)) {
			real.put(key, current);
		}
	}

	/** Call after Vire wrote {@code now} at {@code key}. */
	public void afterVirtualWrite(long key, S now) {
		S r = real.get(key);
		if (Objects.equals(r, now)) {
			// back to the real state: nothing to restore here any more
			real.remove(key);
			virtual.remove(key);
		} else {
			virtual.put(key, now);
		}
	}

	/**
	 * A real block update arrived from the server. Returns {@code true} when it was taken over and must
	 * not be applied to the level (position inside the box).
	 */
	public boolean onServerUpdate(long key, S state) {
		if (!box.contains(key)) {
			return false;
		}
		real.put(key, state);
		serverUpdated.add(key);
		interceptedUpdates++;
		return true;
	}

	/** Positions the server changed while Vire Mode was on (their real state is in {@link #restoreList()}). */
	public java.util.Set<Long> serverUpdatedKeys() {
		return Collections.unmodifiableSet(serverUpdated);
	}

	/** Same as {@link #onServerUpdate} for block entity data; the latest packet per position wins. */
	public boolean onServerBlockEntityData(long key, B data) {
		if (!box.contains(key)) {
			return false;
		}
		pendingBlockEntityData.remove(key);
		pendingBlockEntityData.put(key, data);
		interceptedUpdates++;
		return true;
	}

	/**
	 * The server resent chunk {@code (chunkX, chunkZ)}; the level now shows real data there.
	 *
	 * @param levelState reads the (now real) state from the level
	 * @return virtual states to write again, without capturing (the caller writes them raw)
	 */
	public Map<Long, S> onChunkReloaded(int chunkX, int chunkZ, LongFunction<S> levelState) {
		Map<Long, S> reapply = new LinkedHashMap<>();
		for (Iterator<Map.Entry<Long, S>> it = real.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Long, S> e = it.next();
			long key = e.getKey();
			if (!inChunk(key, chunkX, chunkZ)) {
				continue;
			}
			S v = virtual.get(key);
			S nowReal = levelState.apply(key);
			if (v == null || Objects.equals(v, nowReal)) {
				it.remove();
				virtual.remove(key);
			} else {
				e.setValue(nowReal);
				reapply.put(key, v);
			}
		}
		pendingBlockEntityData.keySet().removeIf(key -> inChunk(key, chunkX, chunkZ));
		return reapply;
	}

	private static boolean inChunk(long key, int chunkX, int chunkZ) {
		return PosKey.x(key) >> 4 == chunkX && PosKey.z(key) >> 4 == chunkZ;
	}

	/** Real states to write back on exit. */
	public Map<Long, S> restoreList() {
		return Collections.unmodifiableMap(real);
	}

	/** Block entity data to replay after {@link #restoreList()} is written, in arrival order. */
	public List<B> pendingBlockEntityData() {
		return new ArrayList<>(pendingBlockEntityData.values());
	}

	/** Positions that currently show a virtual state. */
	public Map<Long, S> virtualStates() {
		return Collections.unmodifiableMap(virtual);
	}

	public boolean isVirtual(long key) {
		return virtual.containsKey(key);
	}

	public int interceptedUpdates() {
		return interceptedUpdates;
	}
}
