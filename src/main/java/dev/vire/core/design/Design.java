package dev.vire.core.design;

import dev.vire.core.region.PosKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A saved design (PLAN.md §7, §9): the blocks to build, in world coordinates of the world it was made
 * in. Only positions where the design differs from the real world when it was made are stored.
 *
 * @param world   where it belongs (server address or singleplayer save name, plus dimension)
 * @param created epoch millis
 */
public record Design(String name, String world, long created, List<Entry> entries) {
	/** One position: what to build ({@code want}, air = remove) and what was there ({@code was}). */
	public record Entry(int x, int y, int z, String want, String was) {
		public long key() {
			return PosKey.pack(x, y, z);
		}

		public StateRef wantRef() {
			return StateRef.parse(want);
		}

		public boolean removal() {
			return wantRef().isAir();
		}
	}

	public Design {
		entries = List.copyOf(entries);
	}

	/**
	 * Normalized diff of the design against the real world: positions whose design state does not
	 * match the real state (PLAN.md §7). {@code real} and {@code design} map position keys to states.
	 */
	public static List<Entry> diff(Map<Long, StateRef> real, Map<Long, StateRef> design) {
		List<Entry> out = new ArrayList<>();
		for (Map.Entry<Long, StateRef> e : design.entrySet()) {
			StateRef was = real.getOrDefault(e.getKey(), StateRef.AIR);
			StateRef want = e.getValue();
			if (!Normalizer.matches(want, was)) {
				long k = e.getKey();
				out.add(new Entry(PosKey.x(k), PosKey.y(k), PosKey.z(k), Normalizer.normalize(want).toString(), was.toString()));
			}
		}
		out.sort((a, b) -> a.y() != b.y() ? Integer.compare(a.y(), b.y())
				: a.z() != b.z() ? Integer.compare(a.z(), b.z()) : Integer.compare(a.x(), b.x()));
		return out;
	}
}
