package dev.vire.core.litematic;

import static org.junit.jupiter.api.Assertions.*;

import dev.vire.core.design.StateRef;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

class LitematicTest {
	@Test
	void nbtGoldenBytes() throws IOException {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("a", 1);
		root.put("s", "hi");
		root.put("l", new long[] {-1L});
		root.put("e", List.of());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Nbt.write(out, root);
		String expected = "0a0000"                       // compound, empty name
				+ "03" + "0001" + "61" + "00000001"     // int a = 1
				+ "08" + "0001" + "73" + "0002" + "6869" // string s = "hi"
				+ "0c" + "0001" + "6c" + "00000001" + "ffffffffffffffff" // long array l
				+ "09" + "0001" + "65" + "0a" + "00000000" // empty list of compounds
				+ "00";
		assertEquals(expected, HexFormat.of().formatHex(out.toByteArray()));
	}

	@Test
	void bitsPerEntry() {
		assertEquals(2, BitPacker.bitsFor(1));
		assertEquals(2, BitPacker.bitsFor(2));
		assertEquals(2, BitPacker.bitsFor(3));
		assertEquals(2, BitPacker.bitsFor(4));
		assertEquals(3, BitPacker.bitsFor(5));
		assertEquals(5, BitPacker.bitsFor(17));
		assertEquals(9, BitPacker.bitsFor(300));
	}

	@Test
	void entriesSpanTwoLongs() {
		BitPacker p = new BitPacker(5, 26); // entry 12 sits at bits 60..64: across the first two longs
		assertEquals(3, p.data().length); // ceil(26 * 5 / 64)
		p.set(12, 0b10111);
		assertEquals(0b0111L << 60, p.data()[0]);
		assertEquals(0b1L, p.data()[1]);
		assertEquals(0b10111, p.get(12));
		p.set(11, 31);
		p.set(13, 1);
		assertEquals(0b10111, p.get(12), "neighbors do not disturb it");
		assertEquals(31, p.get(11));
		assertEquals(1, p.get(13));
	}

	@Test
	void packRoundTripForManyPaletteSizes() {
		for (int palette : new int[] {1, 2, 3, 17, 300}) {
			int bits = BitPacker.bitsFor(palette);
			int n = 1000;
			BitPacker p = new BitPacker(bits, n);
			for (int i = 0; i < n; i++) {
				p.set(i, (i * 7919) % palette);
			}
			for (int i = 0; i < n; i++) {
				assertEquals((i * 7919) % palette, p.get(i), "palette " + palette + " index " + i);
			}
			assertEquals((n * bits + 63) / 64, p.data().length);
		}
	}

	@Test
	void writerTreeAndFile() throws IOException {
		// 3 × 2 × 2: a repeater at (1,0,0) facing west, stone at (2,1,1), the rest air
		StateRef rep = StateRef.parse("minecraft:repeater[delay=2,facing=west,locked=false,powered=false]");
		StateRef stone = StateRef.parse("minecraft:stone");
		LitematicWriter.Schematic s = new LitematicWriter.Schematic("clock", "VireTester", "made with Vire", 3, 2, 2, 4790, 1000L,
				(x, y, z) -> x == 1 && y == 0 && z == 0 ? rep : x == 2 && y == 1 && z == 1 ? stone : null);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LitematicWriter.write(out, s);
		Map<String, Object> root = read(out.toByteArray());

		assertEquals(4790, root.get("MinecraftDataVersion"));
		assertEquals(LitematicWriter.VERSION, root.get("Version"));
		Map<?, ?> meta = (Map<?, ?>) root.get("Metadata");
		assertEquals(2, meta.get("TotalBlocks"));
		assertEquals(12, meta.get("TotalVolume"));
		assertEquals(Map.of("x", 3, "y", 2, "z", 2), meta.get("EnclosingSize"));
		Map<?, ?> region = (Map<?, ?>) ((Map<?, ?>) root.get("Regions")).get("clock");
		List<?> palette = (List<?>) region.get("BlockStatePalette");
		assertEquals(Map.of("Name", "minecraft:air"), palette.get(0));
		assertEquals(Map.of("Name", "minecraft:repeater", "Properties", Map.of("delay", "2", "facing", "west", "locked", "false", "powered", "false")),
				palette.get(1));
		assertEquals(Map.of("Name", "minecraft:stone"), palette.get(2));
		long[] states = (long[]) region.get("BlockStates");
		assertEquals(1, states.length); // 12 entries × 2 bits
		BitPacker p = new BitPacker(2, 12);
		System.arraycopy(states, 0, p.data(), 0, 1);
		assertEquals(1, p.get(1)); // (y0,z0,x1)
		assertEquals(2, p.get((1 * 2 + 1) * 3 + 2)); // (y1,z1,x2)
		assertEquals(0, p.get(0));
		assertTrue(((List<?>) region.get("TileEntities")).isEmpty());
	}

	// ---- a small NBT reader, only for checking ----

	private static Map<String, Object> read(byte[] gz) throws IOException {
		DataInputStream d = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(gz)));
		assertEquals(Nbt.COMPOUND, d.readByte());
		assertEquals("", d.readUTF());
		@SuppressWarnings("unchecked")
		Map<String, Object> m = (Map<String, Object>) payload(d, Nbt.COMPOUND);
		return m;
	}

	private static Object payload(DataInputStream d, byte type) throws IOException {
		switch (type) {
			case Nbt.BYTE:
				return d.readByte();
			case Nbt.INT:
				return d.readInt();
			case Nbt.LONG:
				return d.readLong();
			case Nbt.STRING:
				return d.readUTF();
			case Nbt.LONG_ARRAY: {
				long[] a = new long[d.readInt()];
				for (int i = 0; i < a.length; i++) {
					a[i] = d.readLong();
				}
				return a;
			}
			case Nbt.LIST: {
				byte t = d.readByte();
				int n = d.readInt();
				List<Object> l = new ArrayList<>();
				for (int i = 0; i < n; i++) {
					l.add(payload(d, t));
				}
				return l;
			}
			case Nbt.COMPOUND: {
				Map<String, Object> m = new LinkedHashMap<>();
				byte t;
				while ((t = d.readByte()) != Nbt.END) {
					String name = d.readUTF();
					m.put(name, payload(d, t));
				}
				return m;
			}
			default:
				throw new IOException("type " + type);
		}
	}
}
