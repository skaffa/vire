package dev.vire.core.litematic;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * Minimal NBT writer (big endian, Java modified UTF-8 names, as Minecraft writes it). A tree of plain
 * Java values: {@code Map<String, ?>} = compound (insertion order kept), {@code List<?>} = list,
 * {@code Byte}, {@code Integer}, {@code Long}, {@code String}, {@code long[]}, {@code int[]}.
 * Empty lists are written as lists of compounds, which is what readers asking for compounds expect.
 */
public final class Nbt {
	public static final byte END = 0;
	public static final byte BYTE = 1;
	public static final byte INT = 3;
	public static final byte LONG = 4;
	public static final byte STRING = 8;
	public static final byte LIST = 9;
	public static final byte COMPOUND = 10;
	public static final byte INT_ARRAY = 11;
	public static final byte LONG_ARRAY = 12;

	private Nbt() {
	}

	/** Writes {@code root} as the unnamed root compound (no compression). */
	public static void write(OutputStream out, Map<String, ?> root) throws IOException {
		DataOutputStream d = new DataOutputStream(out);
		d.writeByte(COMPOUND);
		d.writeUTF("");
		payload(d, root);
		d.flush();
	}

	static byte type(Object v) {
		if (v instanceof Map<?, ?>) {
			return COMPOUND;
		}
		if (v instanceof List<?>) {
			return LIST;
		}
		if (v instanceof Byte) {
			return BYTE;
		}
		if (v instanceof Integer) {
			return INT;
		}
		if (v instanceof Long) {
			return LONG;
		}
		if (v instanceof String) {
			return STRING;
		}
		if (v instanceof long[]) {
			return LONG_ARRAY;
		}
		if (v instanceof int[]) {
			return INT_ARRAY;
		}
		throw new IllegalArgumentException("Not an NBT value: " + (v == null ? "null" : v.getClass()));
	}

	private static void payload(DataOutputStream d, Object v) throws IOException {
		switch (type(v)) {
			case COMPOUND -> {
				for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
					d.writeByte(type(e.getValue()));
					d.writeUTF((String) e.getKey());
					payload(d, e.getValue());
				}
				d.writeByte(END);
			}
			case LIST -> {
				List<?> l = (List<?>) v;
				byte t = l.isEmpty() ? COMPOUND : type(l.get(0));
				d.writeByte(t);
				d.writeInt(l.size());
				for (Object o : l) {
					if (type(o) != t) {
						throw new IllegalArgumentException("Mixed list");
					}
					payload(d, o);
				}
			}
			case BYTE -> d.writeByte((Byte) v);
			case INT -> d.writeInt((Integer) v);
			case LONG -> d.writeLong((Long) v);
			case STRING -> d.writeUTF((String) v);
			case LONG_ARRAY -> {
				long[] a = (long[]) v;
				d.writeInt(a.length);
				for (long x : a) {
					d.writeLong(x);
				}
			}
			case INT_ARRAY -> {
				int[] a = (int[]) v;
				d.writeInt(a.length);
				for (int x : a) {
					d.writeInt(x);
				}
			}
			default -> throw new IllegalStateException();
		}
	}
}
