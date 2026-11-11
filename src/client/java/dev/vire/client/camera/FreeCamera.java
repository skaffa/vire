package dev.vire.client.camera;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Detached camera for Vire Mode. Never added to the level, so it is not rendered, not ticked by the
 * level and never synced. It flies through blocks; movement is read straight from the movement keys,
 * because the player's own input is blanked while Vire Mode is on (PLAN.md §3.2).
 */
public final class FreeCamera extends Entity {
	private static final double BASE_SPEED = 0.6;
	private static final double SPRINT_FACTOR = 2.5;

	public FreeCamera(Level level) {
		super(EntityTypes.MARKER, level);
		this.noPhysics = true;
	}

	/** Called once per client tick: keeps last tick's pose for interpolation, then moves. */
	public void tickMovement(Minecraft mc, double speedFactor) {
		setOldPosAndRot();
		Options o = mc.options;
		float forward = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0);
		float strafe = (o.keyLeft.isDown() ? 1 : 0) - (o.keyRight.isDown() ? 1 : 0);
		float vertical = (o.keyJump.isDown() ? 1 : 0) - (o.keyShift.isDown() ? 1 : 0);
		if (forward == 0 && strafe == 0 && vertical == 0) {
			return;
		}
		double speed = BASE_SPEED * speedFactor * (o.keySprint.isDown() ? SPRINT_FACTOR : 1.0);
		double len = Math.sqrt(forward * forward + strafe * strafe);
		double f = len > 0 ? forward / len : 0;
		double s = len > 0 ? strafe / len : 0;
		float yaw = getYRot() * Mth.DEG_TO_RAD;
		double sin = Mth.sin(yaw);
		double cos = Mth.cos(yaw);
		double dx = (s * cos - f * sin) * speed;
		double dz = (f * cos + s * sin) * speed;
		double dy = vertical * speed;
		Vec3 p = position().add(dx, dy, dz);
		double y = Mth.clamp(p.y, level().getMinY() - 16, level().getMaxY() + 64);
		setPos(p.x, y, p.z);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		return false;
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
	}

	@Override
	public boolean isPickable() {
		return false;
	}
}
