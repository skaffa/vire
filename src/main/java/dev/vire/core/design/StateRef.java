package dev.vire.core.design;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * A block state as text, Minecraft-free: {@code minecraft:repeater[delay=2,facing=west]}. Properties are
 * kept sorted so two refs of the same state are equal and print the same.
 */
public record StateRef(String block, Map<String, String> props) {
	public static final StateRef AIR = new StateRef("minecraft:air", Map.of());

	public StateRef {
		props = Collections.unmodifiableMap(new TreeMap<>(props));
	}

	/** Parses {@code id[k=v,...]}; a missing namespace becomes {@code minecraft:}. */
	public static StateRef parse(String text) {
		String t = text.trim();
		int open = t.indexOf('[');
		String id = open < 0 ? t : t.substring(0, open);
		if (!id.contains(":")) {
			id = "minecraft:" + id;
		}
		Map<String, String> props = new TreeMap<>();
		if (open >= 0) {
			int close = t.lastIndexOf(']');
			if (close < open) {
				throw new IllegalArgumentException("Bad block state: " + text);
			}
			String body = t.substring(open + 1, close);
			if (!body.isBlank()) {
				for (String kv : body.split(",")) {
					int eq = kv.indexOf('=');
					if (eq < 0) {
						throw new IllegalArgumentException("Bad block state: " + text);
					}
					props.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
				}
			}
		}
		return new StateRef(id, props);
	}

	/** The id without namespace: {@code repeater}. */
	public String path() {
		int c = block.indexOf(':');
		return c < 0 ? block : block.substring(c + 1);
	}

	public boolean isAir() {
		return block.equals("minecraft:air") || block.equals("minecraft:cave_air") || block.equals("minecraft:void_air");
	}

	public String get(String prop) {
		return props.get(prop);
	}

	@Override
	public String toString() {
		if (props.isEmpty()) {
			return block;
		}
		StringBuilder b = new StringBuilder(block).append('[');
		boolean first = true;
		for (Map.Entry<String, String> e : props.entrySet()) {
			if (!first) {
				b.append(',');
			}
			b.append(e.getKey()).append('=').append(e.getValue());
			first = false;
		}
		return b.append(']').toString();
	}
}
