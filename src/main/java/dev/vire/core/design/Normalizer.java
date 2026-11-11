package dev.vire.core.design;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Normalization table (PLAN.md §7): properties the simulation or the neighbors set are not part of the
 * design. A powered dust, a lit lamp or an extended piston is built as the plain block; fence and wire
 * connections follow from the neighbors once everything is placed.
 */
public final class Normalizer {
	/** Ignored on every block. */
	private static final Set<String> STATE = Set.of(
			"power", "powered", "lit", "triggered", "extended", "enabled", "locked", "open",
			"snowy", "distance", "attached", "disarmed", "in_wall", "sculk_sensor_phase", "occupied");
	/** Side connections, ignored on blocks that connect to their neighbors by themselves. */
	private static final Set<String> SIDES = Set.of("north", "east", "south", "west", "up");
	private static final List<String> CONNECTING_SUFFIXES = List.of("_fence", "_wall", "_pane", "_bars");
	private static final Set<String> CONNECTING = Set.of("redstone_wire", "tripwire", "iron_bars", "glass_pane", "chorus_plant");

	private Normalizer() {
	}

	/** The design-relevant part of a state. */
	public static StateRef normalize(StateRef s) {
		String path = s.path();
		boolean connecting = connecting(path);
		boolean neighborShape = path.endsWith("_stairs") || path.endsWith("rail"); // shape follows the neighbors
		boolean chest = path.endsWith("chest"); // single / left / right follows the neighbor chest
		Map<String, String> out = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : s.props().entrySet()) {
			String k = e.getKey();
			if (STATE.contains(k) || connecting && SIDES.contains(k) || neighborShape && k.equals("shape") || chest && k.equals("type")) {
				continue;
			}
			out.put(k, e.getValue());
		}
		return out.size() == s.props().size() ? s : new StateRef(s.block(), out);
	}

	/** True when {@code live} counts as built for {@code want}. Any air counts as air. */
	public static boolean matches(StateRef want, StateRef live) {
		if (want.isAir() || live.isAir()) {
			return want.isAir() && live.isAir();
		}
		return normalize(want).equals(normalize(live));
	}

	/**
	 * First difference that matters, as {@code "facing: north expected"}, or {@code "<block> expected"}
	 * for another block, or null when they match.
	 */
	public static String difference(StateRef want, StateRef live) {
		if (matches(want, live)) {
			return null;
		}
		if (want.isAir()) {
			return "remove";
		}
		if (!want.block().equals(live.block())) {
			return want.path() + " expected";
		}
		StateRef w = normalize(want);
		StateRef l = normalize(live);
		for (Map.Entry<String, String> e : w.props().entrySet()) {
			if (!e.getValue().equals(l.get(e.getKey()))) {
				return e.getKey() + ": " + e.getValue() + " expected";
			}
		}
		return "state differs";
	}

	private static boolean connecting(String path) {
		if (CONNECTING.contains(path)) {
			return true;
		}
		for (String s : CONNECTING_SUFFIXES) {
			if (path.endsWith(s)) {
				return true;
			}
		}
		return false;
	}
}
