package dev.vire.core.region;

/** Inclusive block box. All bounds are block coordinates; {@code max} is part of the box. */
public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
	public Box {
		if (minX > maxX || minY > maxY || minZ > maxZ) {
			throw new IllegalArgumentException("empty box");
		}
	}

	/**
	 * Cube of {@code size} blocks centered on a block (an even size puts the extra block on the
	 * negative side), with y clipped to the world's build height {@code [worldMinY, worldMaxY]}.
	 */
	public static Box centered(int x, int y, int z, int size, int worldMinY, int worldMaxY) {
		int lo = size / 2;
		int hi = size - lo - 1;
		int minY = Math.max(worldMinY, y - lo);
		int maxY = Math.min(worldMaxY, y + hi);
		return new Box(x - lo, minY, z - lo, x + hi, maxY, z + hi);
	}

	public boolean contains(int x, int y, int z) {
		return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
	}

	public boolean contains(long key) {
		return contains(PosKey.x(key), PosKey.y(key), PosKey.z(key));
	}

	/** True when the 16x16 chunk column {@code (chunkX, chunkZ)} overlaps this box. */
	public boolean overlapsChunk(int chunkX, int chunkZ) {
		int cx0 = chunkX << 4;
		int cz0 = chunkZ << 4;
		return cx0 <= maxX && cx0 + 15 >= minX && cz0 <= maxZ && cz0 + 15 >= minZ;
	}

	public int sizeX() {
		return maxX - minX + 1;
	}

	public int sizeY() {
		return maxY - minY + 1;
	}

	public int sizeZ() {
		return maxZ - minZ + 1;
	}

	public long volume() {
		return (long) sizeX() * sizeY() * sizeZ();
	}
}
