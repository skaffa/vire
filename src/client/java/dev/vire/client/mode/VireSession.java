package dev.vire.client.mode;

import dev.vire.client.camera.FreeCamera;
import dev.vire.core.design.EditHistory;
import dev.vire.core.region.Box;
import dev.vire.core.region.ShadowRegion;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/** Everything that exists only while Vire Mode is on. */
public final class VireSession {
	public final ClientLevel level;
	public final LocalPlayer player;
	public final Box box;
	public final ShadowRegion<BlockState, ClientboundBlockEntityDataPacket> shadow;
	public final EditHistory<BlockState> history = new EditHistory<>(500);
	public final FreeCamera camera;
	/** Where the body stands; any server move of the body ends Vire Mode. */
	public final Vec3 bodyPos;
	/** The player's real input object, put back on exit; a blank one is used meanwhile. */
	public final ClientInput savedInput;
	/** Block entity data of real blocks Vire overwrote, so restore can bring back sign text etc. */
	public final Map<Long, CompoundTag> savedBlockEntities = new HashMap<>();
	public final long startedAt;
	public float lastHealth;

	/** Real states of the region plus a 1-block border, taken on enter: the base of every sandbox load. */
	public final Map<Long, BlockState> snapshot = new HashMap<>();
	/** Block entity data in the snapshot (signs, banners, what the client knows of containers). */
	public final Map<Long, CompoundTag> snapshotBlockEntities = new HashMap<>();
	/** Current sandbox load; mirror batches of other loads are dropped. */
	public int generation;
	/** True once the first batch of the current session arrived. */
	public boolean loaded;
	/** Sandbox clock, from the last batch. */
	public boolean frozen = true;
	public float tickRate = 20;
	public long sandboxTick;
	/** Comparator outputs in the sandbox (key → 0..15); the client's own comparator block entities do not know them. */
	public Map<Long, Integer> comparators = Map.of();
	/** Delay tracker marks (keys) or null, and the meter fed after every mirror batch (PLAN.md §8). */
	public Long markIn;
	public Long markOut;
	public final dev.vire.core.probe.DelayMeter meter = new dev.vire.core.probe.DelayMeter();
	/** Load the meter last saw; a new load restarts the circuit, so the meter starts over. */
	public int meterGeneration = -1;

	VireSession(ClientLevel level, LocalPlayer player, Box box, FreeCamera camera, ClientInput savedInput) {
		this.level = level;
		this.player = player;
		this.box = box;
		this.shadow = new ShadowRegion<>(box);
		this.camera = camera;
		this.bodyPos = player.position();
		this.savedInput = savedInput;
		this.lastHealth = player.getHealth();
		this.startedAt = level.getGameTime();
	}
}
