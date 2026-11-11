package dev.vire.core.design;

import static org.junit.jupiter.api.Assertions.*;

import dev.vire.core.region.PosKey;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class DesignTest {
	private static StateRef s(String t) {
		return StateRef.parse(t);
	}

	@Test
	void parseAndPrintSortsProperties() {
		StateRef r = s("repeater[facing=west,delay=2]");
		assertEquals("minecraft:repeater", r.block());
		assertEquals("repeater", r.path());
		assertEquals("minecraft:repeater[delay=2,facing=west]", r.toString());
		assertEquals(r, s("minecraft:repeater[delay=2,facing=west]"));
		assertEquals("minecraft:stone", s("stone").toString());
		assertTrue(s("cave_air").isAir());
		assertThrows(IllegalArgumentException.class, () -> s("repeater[delay]"));
	}

	@Test
	void simulationStateIsIgnored() {
		assertTrue(Normalizer.matches(s("repeater[delay=2,facing=west,locked=false,powered=false]"),
				s("repeater[delay=2,facing=west,locked=true,powered=true]")));
		assertFalse(Normalizer.matches(s("repeater[delay=2,facing=west,powered=false]"), s("repeater[delay=3,facing=west,powered=false]")));
		assertTrue(Normalizer.matches(s("redstone_lamp[lit=false]"), s("redstone_lamp[lit=true]")));
		assertTrue(Normalizer.matches(s("sticky_piston[extended=true,facing=up]"), s("sticky_piston[extended=false,facing=up]")));
		assertTrue(Normalizer.matches(s("comparator[facing=north,mode=subtract,powered=true]"), s("comparator[facing=north,mode=subtract,powered=false]")));
		assertFalse(Normalizer.matches(s("comparator[facing=north,mode=subtract,powered=true]"), s("comparator[facing=north,mode=compare,powered=true]")));
		assertTrue(Normalizer.matches(s("note_block[instrument=harp,note=0,powered=false]"), s("note_block[instrument=harp,note=0,powered=true]")));
		assertFalse(Normalizer.matches(s("note_block[instrument=harp,note=0]"), s("note_block[instrument=harp,note=5]")), "note is kept");
	}

	@Test
	void neighborConnectionsAreIgnored() {
		assertTrue(Normalizer.matches(s("redstone_wire[east=side,north=none,power=7,south=up,west=side]"),
				s("redstone_wire[east=none,north=side,power=0,south=none,west=none]")));
		assertTrue(Normalizer.matches(s("oak_fence[east=true,north=false,south=false,waterlogged=false,west=false]"),
				s("oak_fence[east=false,north=false,south=false,waterlogged=false,west=true]")));
		assertFalse(Normalizer.matches(s("oak_fence[waterlogged=true]"), s("oak_fence[waterlogged=false]")));
		assertTrue(Normalizer.matches(s("oak_stairs[facing=east,half=bottom,shape=straight]"), s("oak_stairs[facing=east,half=bottom,shape=outer_left]")));
		assertFalse(Normalizer.matches(s("oak_stairs[facing=east,half=bottom,shape=straight]"), s("oak_stairs[facing=west,half=bottom,shape=straight]")));
		// vines keep their faces: the player chose them
		assertFalse(Normalizer.matches(s("vine[east=true,north=false]"), s("vine[east=false,north=true]")));
	}

	@Test
	void differenceText() {
		assertNull(Normalizer.difference(s("stone"), s("stone")));
		assertEquals("facing: north expected", Normalizer.difference(s("repeater[delay=1,facing=north]"), s("repeater[delay=1,facing=south]")));
		assertEquals("repeater expected", Normalizer.difference(s("repeater[delay=1,facing=north]"), s("stone")));
		assertEquals("remove", Normalizer.difference(s("air"), s("stone")));
		assertTrue(Normalizer.matches(s("air"), s("cave_air")));
	}

	@Test
	void diffKeepsOnlyRealChanges() {
		long a = PosKey.pack(0, 0, 0);
		long b = PosKey.pack(1, 0, 0);
		long c = PosKey.pack(2, 0, 0);
		long d = PosKey.pack(3, 0, 0);
		Map<Long, StateRef> real = new HashMap<>();
		real.put(a, s("grass_block[snowy=false]"));
		real.put(b, s("redstone_lamp[lit=true]"));
		real.put(c, s("stone"));
		Map<Long, StateRef> design = new HashMap<>();
		design.put(a, s("repeater[delay=2,facing=west,locked=false,powered=true]"));
		design.put(b, s("redstone_lamp[lit=false]")); // same block, only simulation state differs
		design.put(c, s("air"));
		design.put(d, s("air")); // was air (not in real) and stays air
		List<Design.Entry> e = Design.diff(real, design);
		assertEquals(2, e.size());
		assertEquals("minecraft:repeater[delay=2,facing=west]", e.get(0).want(), "stored normalized");
		assertEquals("minecraft:grass_block[snowy=false]", e.get(0).was());
		assertTrue(e.get(1).removal());
		assertEquals(c, e.get(1).key());
	}

	@Test
	void bomUnits() {
		assertEquals(1, Bom.units(s("oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]")));
		assertEquals(0, Bom.units(s("oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]")));
		assertEquals(1, Bom.units(s("oak_stairs[facing=east,half=top,shape=straight]")), "stairs half=top is not a second half");
		assertEquals(0, Bom.units(s("red_bed[facing=north,occupied=false,part=head]")));
		assertEquals(1, Bom.units(s("red_bed[facing=north,occupied=false,part=foot]")));
		assertEquals(2, Bom.units(s("stone_slab[type=double,waterlogged=false]")));
		assertEquals(1, Bom.units(s("stone_slab[type=top,waterlogged=false]")));
		assertEquals(3, Bom.units(s("candle[candles=3,lit=false,waterlogged=false]")));
		assertEquals(4, Bom.units(s("sea_pickle[pickles=4,waterlogged=true]")));
		assertEquals(5, Bom.units(s("snow[layers=5]")));
		assertEquals(0, Bom.units(s("air")));
		assertEquals(0, Bom.units(s("piston_head[facing=up,short=false,type=sticky]")));
		assertEquals("minecraft:water_bucket", Bom.specialItem(s("water[level=0]")));
	}

	@Test
	void bomCount() {
		Map<String, Integer> m = Bom.count(List.of(s("repeater[delay=1,facing=west]"), s("repeater[delay=3,facing=west]"),
				s("redstone_wire[power=0]"), s("stone_slab[type=double]"), s("oak_door[half=upper]"), s("water[level=0]"), s("fire[age=0]")),
				r -> switch (r.path()) {
					case "redstone_wire" -> "minecraft:redstone";
					case "fire" -> null;
					default -> r.block();
				});
		assertEquals(Map.of("minecraft:repeater", 2, "minecraft:redstone", 1, "minecraft:stone_slab", 2, "minecraft:water_bucket", 1), m);
	}

	@Test
	void storeRoundTripAndNames(@TempDir Path dir) throws Exception {
		DesignStore store = new DesignStore(dir.resolve("designs"));
		assertTrue(store.list().isEmpty());
		assertEquals("my_clock_2", DesignStore.cleanName(" My Clock #2 "));
		assertEquals("_autosave", DesignStore.cleanName(DesignStore.AUTOSAVE));
		assertEquals("design", DesignStore.cleanName("  "));
		Design d = new Design("clock", "server:127.0.0.1/minecraft:overworld", 123L,
				List.of(new Design.Entry(1, -60, 2, "minecraft:observer[facing=east]", "minecraft:air")));
		store.save(d);
		assertEquals(d, store.load("clock"));
		assertEquals(List.of("clock"), store.list());
		assertEquals("clock-2", store.freeName("clock"));
		assertNull(store.load("nope"));
		Files.writeString(store.file("bad"), "{ not json");
		assertThrows(java.io.IOException.class, () -> store.load("bad"));
		assertTrue(store.delete("clock"));
	}
}
