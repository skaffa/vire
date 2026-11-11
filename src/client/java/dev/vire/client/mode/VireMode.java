package dev.vire.client.mode;

import dev.vire.client.VireClient;
import dev.vire.client.camera.FreeCamera;
import dev.vire.client.interact.VireWorld;
import dev.vire.core.region.Box;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Vire Mode state machine (PLAN.md §3): OFF ⇄ ACTIVE. M1 has no sandbox server, so STARTING and
 * STOPPING are instant.
 */
public final class VireMode {
	private static VireSession session;

	private VireMode() {
	}

	public static boolean active() {
		return session != null;
	}

	/** The current session, or {@code null} when Vire Mode is off. */
	public static VireSession session() {
		return session;
	}

	public static void toggle(Minecraft mc) {
		if (active()) {
			exit(mc, "left", true);
		} else {
			enter(mc);
		}
	}

	public static boolean enter(Minecraft mc) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null || active()) {
			return false;
		}
		String refusal = refusal(p);
		if (refusal == null && p.hurtTime > 0) {
			refusal = "you were just hurt";
		}
		if (refusal != null) {
			say(mc, "Can't enter Vire Mode: " + refusal, ChatFormatting.RED);
			return false;
		}
		for (String w : warnings(p, level)) {
			say(mc, "Warning: " + w, ChatFormatting.YELLOW);
		}

		Box box = Box.centered(p.getBlockX(), p.getBlockY(), p.getBlockZ(), VireClient.config.regionSize,
				level.getMinY(), level.getMaxY());
		FreeCamera camera = new FreeCamera(level);
		Vec3 eye = p.getEyePosition();
		camera.snapTo(eye.x, eye.y, eye.z, p.getYRot(), p.getXRot());

		ClientInput saved = p.input;
		p.input = new ClientInput();
		p.setDeltaMovement(Vec3.ZERO);
		p.setSprinting(false);

		PacketGuard.reset();
		session = new VireSession(level, p, box, camera, saved);
		mc.setCameraEntity(camera);
		say(mc, "Vire Mode on: region " + box.sizeX() + "×" + box.sizeY() + "×" + box.sizeZ()
				+ ". Nothing you do here reaches the server. Press V to leave.", ChatFormatting.AQUA);
		return true;
	}

	/** Why entering is not allowed right now, or {@code null}. */
	private static String refusal(LocalPlayer p) {
		if (p.isPassenger()) {
			return "you are riding something";
		}
		if (p.isFallFlying()) {
			return "you are gliding";
		}
		if (!p.onGround() && !p.isInWater()) {
			return "stand on the ground (or in water) first";
		}
		if (p.isDeadOrDying()) {
			return "you are dead";
		}
		return null;
	}

	private static java.util.List<String> warnings(LocalPlayer p, ClientLevel level) {
		java.util.List<String> out = new java.util.ArrayList<>();
		if (p.getHealth() < p.getMaxHealth() * 0.5f) {
			out.add("low health; your body stays in the real world");
		}
		if (p.isOnFire() || p.isInLava()) {
			out.add("you are burning");
		}
		AABB around = p.getBoundingBox().inflate(16);
		if (!level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, around, m -> m instanceof Enemy && m.isAlive()).isEmpty()) {
			out.add("hostile mobs within 16 blocks can hurt your body");
		}
		return out;
	}

	/**
	 * Leaves Vire Mode.
	 *
	 * @param restore write the real blocks back (false when the level is already gone)
	 */
	public static void exit(Minecraft mc, String reason, boolean restore) {
		VireSession s = session;
		if (s == null) {
			return;
		}
		session = null; // off first: restore writes must not be intercepted
		VireWorld.resetRecording();
		if (s.player == mc.player) {
			s.player.input = s.savedInput;
			s.player.setDeltaMovement(Vec3.ZERO);
		}
		if (mc.player != null && mc.getCameraEntity() == s.camera) {
			mc.setCameraEntity(mc.player);
		}
		int restored = 0;
		if (restore && mc.level == s.level) {
			restored = VireWorld.restore(mc, s);
		}
		int edits = s.history.undoSize();
		String msg = "Vire Mode off (" + reason + "). " + edits + " edit" + (edits == 1 ? "" : "s") + " discarded, "
				+ restored + " block" + (restored == 1 ? "" : "s") + " restored"
				+ (PacketGuard.dropped() > 0 ? ", " + PacketGuard.dropped() + " packets blocked (see log)" : "") + ".";
		VireClient.LOG.info("[Vire] {} sent={} dropped={}", msg, PacketGuard.sent(), PacketGuard.droppedByType());
		say(mc, msg, PacketGuard.dropped() > 0 ? ChatFormatting.RED : ChatFormatting.AQUA);
	}

	/** End of every client tick. */
	public static void tick(Minecraft mc) {
		VireSession s = session;
		if (s == null) {
			return;
		}
		LocalPlayer p = mc.player;
		if (p == null || mc.level != s.level || p != s.player) {
			exit(mc, "world changed", false);
			return;
		}
		if (p.isDeadOrDying()) {
			exit(mc, "your body died", true);
			return;
		}
		// hurtTime comes from the damage event; health alone can miss a hit that regen undid in the same server tick
		if (VireClient.config.exitOnDamage && (p.getHealth() < s.lastHealth || p.hurtTime > 0)) {
			exit(mc, "your body took damage", true);
			return;
		}
		s.lastHealth = p.getHealth();
		if (p.position().distanceToSqr(s.bodyPos) > 0.01) {
			exit(mc, "the server moved your body", true);
			return;
		}
		p.setDeltaMovement(Vec3.ZERO);
		if (mc.getCameraEntity() != s.camera) {
			mc.setCameraEntity(s.camera);
		}
		if (mc.gui.screen() == null) {
			s.camera.tickMovement(mc, VireClient.config.cameraSpeed);
		} else {
			s.camera.setOldPosAndRot();
		}
	}

	static void say(Minecraft mc, String msg, ChatFormatting color) {
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.literal("[Vire] ").withStyle(ChatFormatting.DARK_AQUA)
					.append(Component.literal(msg).withStyle(color)));
		}
	}

	/** Short message above the hotbar. */
	public static void hint(Minecraft mc, String msg) {
		if (mc.player != null) {
			mc.player.sendOverlayMessage(Component.literal(msg).withStyle(ChatFormatting.AQUA));
		}
	}
}
