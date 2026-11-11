package dev.vire.core.region;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

class RegionTest {
	@Test
	void posKeyRoundTripIncludingNegatives() {
		int[][] cases = {{0, 0, 0}, {1, -64, -1}, {-30000000, 319, 29999999}, {123, -1, -456}, {-1, 2047, 1}, {5, -2048, 5}};
		for (int[] c : cases) {
			long k = PosKey.pack(c[0], c[1], c[2]);
			assertEquals(c[0], PosKey.x(k));
			assertEquals(c[1], PosKey.y(k));
			assertEquals(c[2], PosKey.z(k));
		}
		assertNotEquals(PosKey.pack(1, 2, 3), PosKey.pack(3, 2, 1));
	}

	@Test
	void centeredBoxSizeAndClip() {
		Box b = Box.centered(10, 70, -5, 32, -64, 319);
		assertEquals(32, b.sizeX());
		assertEquals(32, b.sizeY());
		assertEquals(32, b.sizeZ());
		assertTrue(b.contains(10, 70, -5));
		assertTrue(b.contains(-6, 54, -21));
		assertFalse(b.contains(-7, 54, -21));
		Box low = Box.centered(0, -60, 0, 32, -64, 319);
		assertEquals(-64, low.minY());
		Box odd = Box.centered(0, 0, 0, 17, -64, 319);
		assertEquals(17, odd.sizeX());
		assertEquals(-8, odd.minX());
		assertEquals(8, odd.maxX());
	}

	@Test
	void expand() {
		Box b = new Box(0, 0, 0, 3, 3, 3).expand(1);
		assertEquals(new Box(-1, -1, -1, 4, 4, 4), b);
		assertEquals(216, b.volume());
	}

	@Test
	void overlapsChunk() {
		Box b = new Box(14, 0, 14, 17, 5, 17);
		assertTrue(b.overlapsChunk(0, 0));
		assertTrue(b.overlapsChunk(1, 1));
		assertFalse(b.overlapsChunk(2, 0));
		assertFalse(b.overlapsChunk(-1, 0));
		Box neg = new Box(-3, 0, -3, -1, 0, -1);
		assertTrue(neg.overlapsChunk(-1, -1));
		assertFalse(neg.overlapsChunk(0, 0));
	}

	/** Small fake level: map of key → state, "air" default. */
	private static final class Level {
		final Map<Long, String> states = new HashMap<>();

		String get(long k) {
			return states.getOrDefault(k, "air");
		}
	}

	private static final Box BOX = new Box(0, 0, 0, 15, 15, 15);

	private static void virtualWrite(ShadowRegion<String, String> r, Level level, long k, String s) {
		r.beforeVirtualWrite(k, level.get(k));
		level.states.put(k, s);
		r.afterVirtualWrite(k, s);
	}

	private static void restore(ShadowRegion<String, String> r, Level level) {
		r.restoreList().forEach(level.states::put);
	}

	@Test
	void restoreGivesBackRealStates() {
		Level level = new Level();
		long a = PosKey.pack(1, 1, 1);
		level.states.put(a, "stone");
		ShadowRegion<String, String> r = new ShadowRegion<>(BOX);
		virtualWrite(r, level, a, "dirt");
		virtualWrite(r, level, a, "glass");
		assertEquals("glass", level.get(a));
		restore(r, level);
		assertEquals("stone", level.get(a));
	}

	@Test
	void serverUpdateDuringVireModeWinsOnRestore() {
		Level level = new Level();
		long edited = PosKey.pack(2, 2, 2);
		long untouched = PosKey.pack(3, 3, 3);
		level.states.put(edited, "stone");
		level.states.put(untouched, "stone");
		ShadowRegion<String, String> r = new ShadowRegion<>(BOX);
		virtualWrite(r, level, edited, "repeater");

		// another player changes both blocks for real
		assertTrue(r.onServerUpdate(edited, "gold"));
		assertTrue(r.onServerUpdate(untouched, "air"));
		// the virtual view does not change
		assertEquals("repeater", level.get(edited));
		assertEquals("stone", level.get(untouched));

		restore(r, level);
		assertEquals("gold", level.get(edited));
		assertEquals("air", level.get(untouched));
		assertEquals(2, r.interceptedUpdates());
	}

	@Test
	void updatesOutsideBoxPassThrough() {
		ShadowRegion<String, String> r = new ShadowRegion<>(BOX);
		assertFalse(r.onServerUpdate(PosKey.pack(16, 0, 0), "stone"));
		assertFalse(r.onServerBlockEntityData(PosKey.pack(0, -1, 0), "be"));
		assertTrue(r.restoreList().isEmpty());
	}

	@Test
	void writingTheRealStateBackDropsTheEntry() {
		Level level = new Level();
		long a = PosKey.pack(1, 1, 1);
		level.states.put(a, "stone");
		ShadowRegion<String, String> r = new ShadowRegion<>(BOX);
		virtualWrite(r, level, a, "air");
		virtualWrite(r, level, a, "stone");
		assertTrue(r.restoreList().isEmpty());
		assertFalse(r.isVirtual(a));
	}

	@Test
	void virtualWritesOutsideBoxAreStillRestored() {
		// neighbor shape updates can spill one block outside the box
		Level level = new Level();
		long out = PosKey.pack(16, 1, 1);
		level.states.put(out, "fence");
		ShadowRegion<String, String> r = new ShadowRegion<>(BOX);
		virtualWrite(r, level, out, "fence_east");
		restore(r, level);
		assertEquals("fence", level.get(out));
	}

	@Test
	void blockEntityDataLatestWinsInArrivalOrder() {
		ShadowRegion<String, String> r = new ShadowRegion<>(BOX);
		long a = PosKey.pack(1, 1, 1);
		long b = PosKey.pack(2, 1, 1);
		r.onServerBlockEntityData(a, "a1");
		r.onServerBlockEntityData(b, "b1");
		r.onServerBlockEntityData(a, "a2");
		assertEquals(List.of("b1", "a2"), r.pendingBlockEntityData());
	}

	@Test
	void chunkReloadReappliesVirtualAndForgetsStaleEntries() {
		Level level = new Level();
		long edited = PosKey.pack(1, 1, 1);
		long serverOnly = PosKey.pack(2, 2, 2);
		long otherChunk = PosKey.pack(1, 1, 1 + 16);
		Box big = new Box(0, 0, 0, 31, 15, 31);
		ShadowRegion<String, String> r = new ShadowRegion<>(big);
		level.states.put(edited, "stone");
		virtualWrite(r, level, edited, "piston");
		virtualWrite(r, level, otherChunk, "dust");
		r.onServerUpdate(serverOnly, "gold");
		r.onServerBlockEntityData(serverOnly, "sign");

		// the server resends chunk (0,0): level now shows the real data there
		level.states.put(edited, "obsidian");
		level.states.put(serverOnly, "gold");
		Map<Long, String> reapply = r.onChunkReloaded(0, 0, level::get);

		assertEquals(Map.of(edited, "piston"), reapply);
		reapply.forEach(level.states::put);
		assertTrue(r.pendingBlockEntityData().isEmpty());

		restore(r, level);
		assertEquals("obsidian", level.get(edited));
		assertEquals("gold", level.get(serverOnly));
		assertEquals("air", level.get(otherChunk));
	}
}
