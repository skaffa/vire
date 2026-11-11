package dev.vire.client.overlay;

import dev.vire.client.interact.VireWorld;
import dev.vire.client.mode.VireMode;
import dev.vire.client.mode.VireSession;
import dev.vire.core.region.Box;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Outline of the Vire region (white for a moment after a reset) and of the targeted block, submitted as
 * vanilla gizmos every client tick (like Gauge).
 */
public final class RegionOverlay {
	private static final int COLOR = 0xFF00C8FF;
	private static final int FLASH = 0xFFFFFFFF;
	private static final int TARGET = 0xE0FFFFFF;
	private static final int TARGET_USE = 0xFF60FF80;
	private static final int TARGET_OUTSIDE = 0xFFFF5555;

	private RegionOverlay() {
	}

	public static void tick() {
		VireSession s = VireMode.session();
		if (s == null) {
			return;
		}
		Box b = s.box;
		boolean flash = s.resetFlash > 0;
		if (flash) {
			s.resetFlash--;
		}
		Gizmos.cuboid(new AABB(b.minX(), b.minY(), b.minZ(), b.maxX() + 1, b.maxY() + 1, b.maxZ() + 1),
				GizmoStyle.stroke(flash ? FLASH : COLOR, flash ? 4.0f : 2.0f));

		Minecraft mc = Minecraft.getInstance();
		if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos p = hit.getBlockPos();
			BlockState st = s.level.getBlockState(p);
			VoxelShape shape = st.getShape(s.level, p);
			AABB box = shape.isEmpty() ? new AABB(p) : shape.bounds().move(p);
			int color = !s.box.contains(p.getX(), p.getY(), p.getZ()) ? TARGET_OUTSIDE
					: VireWorld.useOf(st) != null && !mc.hasControlDown() ? TARGET_USE : TARGET;
			Gizmos.cuboid(box.inflate(0.003), GizmoStyle.stroke(color, 2.5f));
		}
	}
}
