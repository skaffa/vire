package dev.vire.client.interact;

import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Placement context whose "looking" directions come from the free camera instead of the frozen
 * player body, so repeaters, observers and pistons face the way the user looks.
 */
final class VirtualPlaceContext extends BlockPlaceContext {
	private final Entity camera;
	private final boolean secondary;

	VirtualPlaceContext(Level level, Player player, ItemStack stack, BlockHitResult hit, Entity camera, boolean secondary) {
		super(level, player, InteractionHand.MAIN_HAND, stack, hit);
		this.camera = camera;
		this.secondary = secondary;
	}

	@Override
	public Direction getNearestLookingDirection() {
		return Direction.orderedByNearest(camera)[0];
	}

	@Override
	public Direction getNearestLookingVerticalDirection() {
		return Direction.getFacingAxis(camera, Direction.Axis.Y);
	}

	@Override
	public Direction[] getNearestLookingDirections() {
		Direction[] directions = Direction.orderedByNearest(camera);
		if (!replacingClickedOnBlock()) {
			Direction clicked = getClickedFace();
			int i = 0;
			while (i < directions.length && directions[i] != clicked.getOpposite()) {
				i++;
			}
			if (i > 0 && i < directions.length) {
				System.arraycopy(directions, 0, directions, 1, i);
				directions[0] = clicked.getOpposite();
			}
		}
		return directions;
	}

	@Override
	public Direction getHorizontalDirection() {
		return camera.getDirection();
	}

	@Override
	public float getRotation() {
		return camera.getYRot();
	}

	@Override
	public boolean isSecondaryUseActive() {
		return secondary;
	}
}
