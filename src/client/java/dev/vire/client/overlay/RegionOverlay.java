package dev.vire.client.overlay;

import dev.vire.client.mode.VireMode;
import dev.vire.client.mode.VireSession;
import dev.vire.core.region.Box;

import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;

/** Outline of the Vire region, submitted as vanilla gizmos every client tick (like Gauge). */
public final class RegionOverlay {
	private static final int COLOR = 0xFF00C8FF;

	private RegionOverlay() {
	}

	public static void tick() {
		VireSession s = VireMode.session();
		if (s == null) {
			return;
		}
		Box b = s.box;
		Gizmos.cuboid(new AABB(b.minX(), b.minY(), b.minZ(), b.maxX() + 1, b.maxY() + 1, b.maxZ() + 1), GizmoStyle.stroke(COLOR, 2.0f));
	}
}
