package dev.vire.client.design;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;

import net.minecraft.core.BlockPos;

import java.nio.file.Path;

/**
 * Loads an exported schematic into Litematica and places it at its real position (PLAN.md §10.2),
 * checked against Litematica 0.29.1. Only called when Litematica is loaded: nothing else references
 * this class, so Vire runs without Litematica. A placement of the same name is replaced.
 */
public final class LitematicaHook {
	private LitematicaHook() {
	}

	/** Returns an extra note for the chat message, or null when nothing was loaded. */
	public static String load(Path file, BlockPos origin, String name) {
		LitematicaSchematic schematic = LitematicaSchematic.createFromFile(file.getParent(), file.getFileName().toString());
		if (schematic == null) {
			return null;
		}
		SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
		for (SchematicPlacement old : java.util.List.copyOf(manager.getAllSchematicsPlacements())) {
			if (name.equals(old.getName())) {
				manager.removeSchematicPlacement(old);
			}
		}
		SchematicHolder.getInstance().addSchematic(schematic, true);
		SchematicPlacement placement = SchematicPlacement.createFor(schematic, origin, name, true, true);
		manager.addSchematicPlacement(placement, false);
		manager.setSelectedSchematicPlacement(placement);
		return " Printer ready.";
	}

	/** Test helper: the placement's origin when one with this name exists, else null. */
	public static BlockPos placedAt(String name) {
		for (SchematicPlacement p : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
			if (name.equals(p.getName())) {
				return p.getOrigin();
			}
		}
		return null;
	}
}
