package dev.vire.client.design;

import dev.vire.client.VireClient;
import dev.vire.core.design.Design;
import dev.vire.core.design.DesignStore;
import dev.vire.core.design.StateRef;
import dev.vire.core.litematic.LitematicWriter;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Exports a design as {@code schematics/vire/<name>.litematic} (PLAN.md §10.1), cut to the bounding box
 * of the design. Design positions get the design state (removals = air). The other positions of the
 * box get the block the world shows now, when the client has it and the design was made here, so
 * Litematica sees them as already correct and its printer leaves them alone; otherwise air.
 */
public final class LitematicExport {
	/** The written file and the world position of its (0,0,0) corner, for placing it. */
	public record Result(Path file, BlockPos origin, int blocks) {
	}

	private LitematicExport() {
	}

	public static Path dir(Minecraft mc) {
		return mc.gameDirectory.toPath().resolve("schematics").resolve("vire");
	}

	/**
	 * Exports, then loads it into Litematica at its real position when Litematica is installed
	 * (setting {@code autoLoadLitematica}), and tells the player. Returns the result or null.
	 */
	public static Result exportAndTell(Minecraft mc, Design d) {
		Result r;
		try {
			r = export(mc, d);
		} catch (IOException | RuntimeException e) {
			VireClient.LOG.warn("[Vire] Export of {} failed", d.name(), e);
			tell(mc, "Export failed: " + e.getMessage(), net.minecraft.ChatFormatting.RED);
			return null;
		}
		String where = "schematics/vire/" + r.file().getFileName();
		String loaded = null;
		if (VireClient.config.autoLoadLitematica && net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("litematica")) {
			try {
				loaded = LitematicaHook.load(r.file(), r.origin(), d.name());
			} catch (Throwable t) { // version drift in Litematica's API: fall back to the file
				VireClient.LOG.warn("[Vire] Could not load {} into Litematica", r.file(), t);
			}
		}
		tell(mc, loaded != null ? "Exported to " + where + " and placed in Litematica at " + r.origin().toShortString() + "." + loaded
				: "Exported to " + where + " (corner " + r.origin().toShortString() + "). Load it in Litematica.", net.minecraft.ChatFormatting.AQUA);
		return r;
	}

	private static void tell(Minecraft mc, String msg, net.minecraft.ChatFormatting color) {
		if (mc.player != null) {
			mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[Vire] ").withStyle(net.minecraft.ChatFormatting.DARK_AQUA)
					.append(net.minecraft.network.chat.Component.literal(msg).withStyle(color)));
		}
	}

	public static Result export(Minecraft mc, Design d) throws IOException {
		if (d.entries().isEmpty()) {
			throw new IOException("Design " + d.name() + " is empty");
		}
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		Map<Long, StateRef> want = new HashMap<>();
		for (Design.Entry e : d.entries()) {
			minX = Math.min(minX, e.x());
			minY = Math.min(minY, e.y());
			minZ = Math.min(minZ, e.z());
			maxX = Math.max(maxX, e.x());
			maxY = Math.max(maxY, e.y());
			maxZ = Math.max(maxZ, e.z());
			want.put(e.key(), e.wantRef());
		}
		BlockPos origin = new BlockPos(minX, minY, minZ);
		ClientLevel level = mc.level;
		boolean here = level != null && d.world().equals(Designs.worldId(mc));
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		String author = mc.getUser() != null ? mc.getUser().getName() : "Vire";
		LitematicWriter.Schematic s = new LitematicWriter.Schematic(d.name(), author, "Made with Vire (" + d.entries().size() + " design blocks)",
				maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, SharedConstants.getCurrentVersion().dataVersion().version(),
				System.currentTimeMillis(), (x, y, z) -> {
					int wx = origin.getX() + x;
					int wy = origin.getY() + y;
					int wz = origin.getZ() + z;
					StateRef w = want.get(dev.vire.core.region.PosKey.pack(wx, wy, wz));
					if (w != null) {
						return w;
					}
					if (here && level.hasChunk(wx >> 4, wz >> 4)) {
						return Designs.ref(level.getBlockState(p.set(wx, wy, wz)));
					}
					return StateRef.AIR;
				});
		Path dir = dir(mc);
		Files.createDirectories(dir);
		Path file = dir.resolve(DesignStore.cleanName(d.name()) + ".litematic");
		Path tmp = dir.resolve(file.getFileName() + ".tmp");
		try (OutputStream out = Files.newOutputStream(tmp)) {
			LitematicWriter.write(out, s);
		}
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		VireClient.LOG.info("[Vire] Exported {} ({}x{}x{}, origin {}) to {}", d.name(), s.sizeX(), s.sizeY(), s.sizeZ(), origin.toShortString(), file);
		return new Result(file, origin, d.entries().size());
	}
}
