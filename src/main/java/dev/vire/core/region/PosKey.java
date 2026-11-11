package dev.vire.core.region;

/**
 * Packs a block position into one {@code long} (26 bits x, 26 bits z, 12 bits y), so the core can key
 * maps by position without Minecraft classes. Same layout as vanilla {@code BlockPos.asLong}, but the
 * client converts explicitly and never relies on that.
 */
public final class PosKey {
	private static final int XZ_BITS = 26;
	private static final int Y_BITS = 12;
	private static final long XZ_MASK = (1L << XZ_BITS) - 1;
	private static final long Y_MASK = (1L << Y_BITS) - 1;
	private static final int Z_SHIFT = Y_BITS;
	private static final int X_SHIFT = Y_BITS + XZ_BITS;

	private PosKey() {
	}

	public static long pack(int x, int y, int z) {
		return ((x & XZ_MASK) << X_SHIFT) | ((z & XZ_MASK) << Z_SHIFT) | (y & Y_MASK);
	}

	public static int x(long key) {
		return (int) (key << (64 - X_SHIFT - XZ_BITS) >> (64 - XZ_BITS));
	}

	public static int y(long key) {
		return (int) (key << (64 - Y_BITS) >> (64 - Y_BITS));
	}

	public static int z(long key) {
		return (int) (key << (64 - Z_SHIFT - XZ_BITS) >> (64 - XZ_BITS));
	}
}
