package dev.vire.core.design;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Bill of materials rules (PLAN.md §9): how many items one design block needs. The item itself comes
 * from the client ({@code Block.asItem()}), except where this class names it.
 */
public final class Bom {
	private Bom() {
	}

	/** Items needed for one block: 0 for the second half of doors/beds/tall plants and for air. */
	public static int units(StateRef s) {
		String p = s.path();
		if (s.isAir() || p.equals("piston_head") || p.equals("moving_piston")) {
			return 0;
		}
		if ("upper".equals(s.get("half")) || "head".equals(s.get("part"))) {
			return 0; // doors, tall plants (half=upper), beds (part=head): the item places both
		}
		if ("double".equals(s.get("type")) && p.endsWith("_slab")) {
			return 2;
		}
		for (String count : new String[] {"candles", "pickles", "eggs", "layers", "flower_amount", "segment_amount"}) {
			String v = s.get(count);
			if (v != null) {
				try {
					return Math.max(1, Integer.parseInt(v));
				} catch (NumberFormatException e) {
					return 1;
				}
			}
		}
		return 1;
	}

	/** Item id for blocks whose item is not {@code Block.asItem()}, or null. */
	public static String specialItem(StateRef s) {
		return switch (s.path()) {
			case "water" -> "minecraft:water_bucket";
			case "lava" -> "minecraft:lava_bucket";
			case "powder_snow" -> "minecraft:powder_snow_bucket";
			default -> null;
		};
	}

	/**
	 * Counts the items of the blocks still to build. {@code item} maps a state to its item id (null =
	 * no item, e.g. fire); {@code built} tells which entries are done.
	 */
	public static Map<String, Integer> count(Iterable<StateRef> todo, Function<StateRef, String> item) {
		Map<String, Integer> out = new LinkedHashMap<>();
		for (StateRef s : todo) {
			int n = units(s);
			if (n <= 0) {
				continue;
			}
			String id = specialItem(s);
			if (id == null) {
				id = item.apply(s);
			}
			if (id != null) {
				out.merge(id, n, Integer::sum);
			}
		}
		return out;
	}
}
