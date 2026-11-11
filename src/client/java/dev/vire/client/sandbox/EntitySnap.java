package dev.vire.client.sandbox;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * One sandbox entity that redstone builds care about (PLAN.md §5.6): falling blocks, primed TNT and
 * items. Taken on the sandbox thread, so it holds copies only.
 */
public record EntitySnap(int id, EntityType<?> type, Vec3 pos, Vec3 motion, float yRot, float xRot, int blockId, ItemStack item, int fuse) {
	/** Whether the sandbox mirrors this entity to the client. */
	public static boolean tracked(Entity e) {
		return e instanceof FallingBlockEntity || e instanceof PrimedTnt || e instanceof ItemEntity;
	}

	public static EntitySnap of(Entity e) {
		int blockId = 0;
		ItemStack item = ItemStack.EMPTY;
		int fuse = 0;
		if (e instanceof FallingBlockEntity f) {
			blockId = Block.getId(f.getBlockState());
		} else if (e instanceof PrimedTnt t) {
			blockId = Block.getId(t.getBlockState());
			fuse = t.getFuse();
		} else if (e instanceof ItemEntity i) {
			item = i.getItem().copy();
		}
		return new EntitySnap(e.getId(), e.getType(), e.position(), e.getDeltaMovement(), e.getYRot(), e.getXRot(), blockId, item, fuse);
	}
}
