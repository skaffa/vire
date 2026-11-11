package dev.vire.core.litematic;

import dev.vire.core.design.StateRef;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Writes a one-region {@code .litematic} (PLAN.md §10.1): gzipped NBT with {@code Version},
 * {@code SubVersion}, {@code MinecraftDataVersion}, {@code Metadata} and {@code Regions/<name>}
 * holding {@code Position}, {@code Size}, {@code BlockStatePalette}, {@code BlockStates} (packed by
 * {@link BitPacker}, index = (y × sizeZ + z) × sizeX + x) and empty entity / tick lists. Palette entry 0
 * is air.
 */
public final class LitematicWriter {
	/** Litematica's schematic format version written; checked against Litematica 0.29.1 in M5. */
	public static final int VERSION = 7;
	public static final int SUB_VERSION = 1;

	/** What to write. {@code state} is called for every position of the box, (0,0,0) = min corner. */
	public record Schematic(String name, String author, String description, int sizeX, int sizeY, int sizeZ,
			int dataVersion, long time, StateAt state) {
	}

	@FunctionalInterface
	public interface StateAt {
		StateRef at(int x, int y, int z);
	}

	private LitematicWriter() {
	}

	public static void write(OutputStream out, Schematic s) throws IOException {
		GZIPOutputStream gz = new GZIPOutputStream(out);
		Nbt.write(gz, tree(s));
		gz.finish();
	}

	/** The NBT tree (public for tests). */
	public static Map<String, Object> tree(Schematic s) {
		if (s.sizeX() < 1 || s.sizeY() < 1 || s.sizeZ() < 1) {
			throw new IllegalArgumentException("empty box");
		}
		long volume = (long) s.sizeX() * s.sizeY() * s.sizeZ();
		List<StateRef> palette = new ArrayList<>();
		Map<StateRef, Integer> index = new HashMap<>();
		palette.add(StateRef.AIR);
		index.put(StateRef.AIR, 0);
		int[] ids = new int[(int) volume];
		int blocks = 0;
		int i = 0;
		for (int y = 0; y < s.sizeY(); y++) {
			for (int z = 0; z < s.sizeZ(); z++) {
				for (int x = 0; x < s.sizeX(); x++) {
					StateRef st = s.state().at(x, y, z);
					if (st == null || st.isAir()) {
						st = StateRef.AIR;
					} else {
						blocks++;
					}
					Integer id = index.get(st);
					if (id == null) {
						id = palette.size();
						palette.add(st);
						index.put(st, id);
					}
					ids[i++] = id;
				}
			}
		}
		BitPacker packer = new BitPacker(BitPacker.bitsFor(palette.size()), volume);
		for (int k = 0; k < ids.length; k++) {
			packer.set(k, ids[k]);
		}

		List<Object> paletteTag = new ArrayList<>();
		for (StateRef st : palette) {
			Map<String, Object> e = new LinkedHashMap<>();
			e.put("Name", st.block());
			if (!st.props().isEmpty()) {
				e.put("Properties", new LinkedHashMap<String, Object>(st.props()));
			}
			paletteTag.add(e);
		}
		Map<String, Object> region = new LinkedHashMap<>();
		region.put("Position", vec(0, 0, 0));
		region.put("Size", vec(s.sizeX(), s.sizeY(), s.sizeZ()));
		region.put("BlockStatePalette", paletteTag);
		region.put("BlockStates", packer.data());
		region.put("TileEntities", List.of());
		region.put("Entities", List.of());
		region.put("PendingBlockTicks", List.of());
		region.put("PendingFluidTicks", List.of());
		Map<String, Object> regions = new LinkedHashMap<>();
		regions.put(s.name(), region);

		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("Name", s.name());
		meta.put("Author", s.author());
		meta.put("Description", s.description());
		meta.put("RegionCount", 1);
		meta.put("TotalVolume", (int) volume);
		meta.put("TotalBlocks", blocks);
		meta.put("TimeCreated", s.time());
		meta.put("TimeModified", s.time());
		meta.put("EnclosingSize", vec(s.sizeX(), s.sizeY(), s.sizeZ()));

		Map<String, Object> root = new LinkedHashMap<>();
		root.put("MinecraftDataVersion", s.dataVersion());
		root.put("Version", VERSION);
		root.put("SubVersion", SUB_VERSION);
		root.put("Metadata", meta);
		root.put("Regions", regions);
		return root;
	}

	private static Map<String, Object> vec(int x, int y, int z) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("x", x);
		m.put("y", y);
		m.put("z", z);
		return m;
	}
}
