package dev.vire.core.litematic;

/**
 * Litematica's block state packing (its {@code LitematicaBitArray}): every entry uses {@code bits} bits,
 * entries are packed back to back from the lowest bit, and an entry may span two longs (unlike vanilla
 * chunk sections since 1.16). Array length = ceil(entries × bits / 64).
 */
public final class BitPacker {
	private final int bits;
	private final long mask;
	private final long[] data;
	private final long size;

	public BitPacker(int bits, long size) {
		if (bits < 1 || bits > 32) {
			throw new IllegalArgumentException("bits " + bits);
		}
		this.bits = bits;
		this.size = size;
		this.mask = (1L << bits) - 1L;
		this.data = new long[(int) ((size * bits + 63L) / 64L)];
	}

	/** Bits per entry for a palette of {@code paletteSize}: at least 2, as Litematica does. */
	public static int bitsFor(int paletteSize) {
		int b = 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1));
		return Math.max(2, b);
	}

	public void set(long index, int value) {
		check(index);
		long start = index * bits;
		int startArr = (int) (start >> 6);
		int endArr = (int) (((index + 1L) * bits - 1L) >> 6);
		int startBit = (int) (start & 0x3F);
		long v = value & mask;
		data[startArr] = data[startArr] & ~(mask << startBit) | v << startBit;
		if (startArr != endArr) {
			int endOffset = 64 - startBit;
			int rest = bits - endOffset;
			data[endArr] = data[endArr] >>> rest << rest | v >>> endOffset;
		}
	}

	public int get(long index) {
		check(index);
		long start = index * bits;
		int startArr = (int) (start >> 6);
		int endArr = (int) (((index + 1L) * bits - 1L) >> 6);
		int startBit = (int) (start & 0x3F);
		if (startArr == endArr) {
			return (int) (data[startArr] >>> startBit & mask);
		}
		int endOffset = 64 - startBit;
		return (int) ((data[startArr] >>> startBit | data[endArr] << endOffset) & mask);
	}

	private void check(long index) {
		if (index < 0 || index >= size) {
			throw new IndexOutOfBoundsException(index);
		}
	}

	public long[] data() {
		return data;
	}
}
