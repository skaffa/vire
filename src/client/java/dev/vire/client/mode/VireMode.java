package dev.vire.client.mode;

import dev.vire.client.design.Designs;
import dev.vire.client.design.ExitScreen;
import dev.vire.core.design.Design;
import dev.vire.core.design.DesignStore;

import dev.vire.client.VireClient;
import dev.vire.client.camera.FreeCamera;
import dev.vire.client.interact.VireWorld;
import dev.vire.client.sandbox.EntityMirror;
import dev.vire.client.sandbox.Sandbox;
import dev.vire.core.region.Box;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Vire Mode state machine (PLAN.md §3): OFF → STARTING (sandbox server booting) → ACTIVE → OFF.
 * The sandbox normally starts when the player joins a world ({@code keepWarm}), so STARTING is
 * usually skipped.
 */
public final class VireMode {
	private static VireSession session;
	/** V was pressed while the sandbox was still booting; enter as soon as it is ready. */
	private static boolean pending;
	private static int pendingTicks;

	private VireMode() {
	}

	public static boolean active() {
		return session != null;
	}

	/** The current session, or {@code null} when Vire Mode is off. */
	public static VireSession session() {
		return session;
	}

	public static boolean pending() {
		return pending;
	}

	public static void toggle(Minecraft mc) {
		if (pending) {
			pending = false;
			hint(mc, "Vire Mode cancelled");
		} else if (active()) {
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
		if (!Sandbox.ready()) {
			Sandbox.ensureStarted();
			pending = true;
			pendingTicks = 0;
			hint(mc, "Starting the Vire sandbox… (press V to cancel)");
			return true;
		}
		return start(mc, p, level);
	}

	private static boolean start(Minecraft mc, LocalPlayer p, ClientLevel level) {
		// inside the region of the last session here: continue it (same box, its edits on top of the real world now)
		dev.vire.core.design.SessionStore.Saved resume = Sessions.resumable(mc, level, p.getBlockX(), p.getBlockY(), p.getBlockZ());
		Box box = resume != null ? resume.box() : Box.centered(p.getBlockX(), p.getBlockY(), p.getBlockZ(), VireClient.config.regionSize,
				level.getMinY(), level.getMaxY());
		FreeCamera camera = new FreeCamera(level);
		Vec3 eye = p.getEyePosition();
		camera.snapTo(eye.x, eye.y, eye.z, p.getYRot(), p.getXRot());

		ClientInput saved = p.input;
		p.input = new ClientInput();
		p.setDeltaMovement(Vec3.ZERO);
		p.setSprinting(false);

		PacketGuard.reset();
		VireSession s = new VireSession(level, p, box, camera, saved);
		s.world = Designs.worldId(mc, level);
		snapshot(s);
		int resumed = resume == null ? 0 : Sessions.apply(s, resume);
		session = s;
		VireWorld.reload(s);
		mc.setCameraEntity(camera);
		say(mc, "Vire Mode on: region " + box.sizeX() + "×" + box.sizeY() + "×" + box.sizeZ()
				+ ". Nothing you do here reaches the server. Paused: K to run, . to step. V to leave.", ChatFormatting.AQUA);
		if (resumed > 0) {
			say(mc, "Continued your last session here: " + resumed + " changed blocks. Ctrl+Z removes them all; /vire discard forgets it.",
					ChatFormatting.AQUA);
		} else {
			dev.vire.core.design.SessionStore.Saved other = Sessions.load(s.world);
			if (other != null && resume == null) {
				say(mc, "Fresh copy. Your last session is around " + Sessions.where(other.box()) + " (press V there to continue it).",
						ChatFormatting.GRAY);
			}
		}
		return true;
	}

	/** Copies the real region plus a 1-block border from the client level (PLAN.md §5.4). */
	private static void snapshot(VireSession s) {
		Box outer = s.box.expand(1);
		for (int x = outer.minX(); x <= outer.maxX(); x++) {
			for (int y = outer.minY(); y <= outer.maxY(); y++) {
				for (int z = outer.minZ(); z <= outer.maxZ(); z++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (s.level.isOutsideBuildHeight(pos)) {
						continue;
					}
					BlockState state = s.level.getBlockState(pos);
					long key = VireWorld.key(pos);
					if (!state.isAir()) {
						s.snapshot.put(key, state);
					}
					BlockEntity be = s.level.getBlockEntity(pos);
					if (be != null) {
						s.snapshotBlockEntities.put(key, be.saveWithFullMetadata(s.level.registryAccess()));
					}
				}
			}
		}
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
		Sandbox.unload();
		EntityMirror.clear();
		if (!VireClient.config.keepWarm) {
			Sandbox.stop();
		}
		if (s.player == mc.player) {
			s.player.input = s.savedInput;
			s.player.setDeltaMovement(Vec3.ZERO);
		}
		if (mc.player != null && mc.getCameraEntity() == s.camera) {
			mc.setCameraEntity(mc.player);
		}
		// the design, taken before restore (PLAN.md §7); null when the edits change nothing
		Design design = null;
		try {
			design = Designs.capture(mc, s, Designs.defaultName());
		} catch (RuntimeException e) {
			VireClient.LOG.warn("[Vire] Could not capture the design", e);
		}
		// the session file: continue it with V in this region; a disconnect or world change asks on the next join
		boolean seen = !reason.equals("disconnected") && !reason.equals("world changed");
		try {
			Sessions.save(mc, s, seen, reason);
		} catch (RuntimeException e) {
			VireClient.LOG.warn("[Vire] Could not save the session", e);
		}
		int restored = 0;
		if (restore && mc.level == s.level) {
			restored = VireWorld.restore(mc, s);
		}
		boolean byUser = reason.equals("left");
		String what;
		if (design == null) {
			what = "No design changes";
		} else if (byUser && mc.player != null) {
			what = "Design of " + design.entries().size() + " blocks";
		} else {
			Design saved = Designs.save(design, DesignStore.AUTOSAVE);
			what = saved == null ? "Design could not be saved (see log)"
					: "Design of " + design.entries().size() + " blocks saved as " + DesignStore.AUTOSAVE + " (/vire ghost " + DesignStore.AUTOSAVE + ")";
		}
		String msg = "Vire Mode off (" + reason + "). " + what + ", "
				+ restored + " block" + (restored == 1 ? "" : "s") + " restored"
				+ (PacketGuard.dropped() > 0 ? ", " + PacketGuard.dropped() + " packets blocked (see log)" : "") + ".";
		VireClient.LOG.info("[Vire] {} sent={} dropped={}", msg, PacketGuard.sent(), PacketGuard.droppedByType());
		say(mc, msg, PacketGuard.dropped() > 0 ? ChatFormatting.RED : ChatFormatting.AQUA);
		lastDesign = design;
		if (design != null && byUser && mc.player != null && openExitScreen) {
			mc.gui.setScreen(new ExitScreen(design));
		}
	}

	/** The design captured by the last exit (dev tools and tests). */
	public static Design lastDesign;
	/** The self-test turns the dialog off and applies a choice itself. */
	public static boolean openExitScreen = true;

	/** End of every client tick. */
	public static void tick(Minecraft mc) {
		if (pending) {
			tickPending(mc);
		}
		VireSession s = session;
		if (s == null) {
			return;
		}
		VireWorld.applyMirror(s);
		Sessions.tickActive(mc, s);
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

	private static void tickPending(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) {
			pending = false;
			return;
		}
		if (Sandbox.error() != null) {
			pending = false;
			say(mc, "The Vire sandbox could not start: " + Sandbox.error(), ChatFormatting.RED);
			return;
		}
		if (Sandbox.ready()) {
			pending = false;
			String refusal = refusal(p);
			if (refusal != null) {
				say(mc, "Can't enter Vire Mode: " + refusal, ChatFormatting.RED);
				return;
			}
			start(mc, p, mc.level);
			return;
		}
		if (++pendingTicks % 20 == 0) {
			hint(mc, "Starting the Vire sandbox… " + Sandbox.secondsSinceStart() + " s (press V to cancel)");
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
		VireSession s = session;
		if (s != null) {
			s.lastAction = msg;
			s.lastActionAt = System.currentTimeMillis();
		}
		if (mc.player != null) {
			mc.player.sendOverlayMessage(Component.literal(msg).withStyle(ChatFormatting.AQUA));
		}
	}
}
