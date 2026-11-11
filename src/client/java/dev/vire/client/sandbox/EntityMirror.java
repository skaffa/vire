package dev.vire.client.sandbox;

import dev.vire.client.VireClient;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Client copies of the sandbox's falling blocks, TNT and items (PLAN.md §5.6). They live in the client
 * level under negative ids, which a real server never uses, and are moved to the sandbox position
 * every tick. Nothing about them is ever sent to the server.
 */
public final class EntityMirror {
	private static final int ID_BASE = -1_000_000;
	private static final Map<Integer, Entity> ENTITIES = new HashMap<>();
	private static ClientLevel level;

	private EntityMirror() {
	}

	public static int count() {
		return ENTITIES.size();
	}

	/** Brings the client copies in line with one sandbox tick. */
	public static void apply(ClientLevel lvl, List<EntitySnap> snaps) {
		if (level != lvl) {
			clear();
			level = lvl;
		}
		Set<Integer> seen = new HashSet<>();
		for (EntitySnap s : snaps) {
			seen.add(s.id());
			Entity e = ENTITIES.get(s.id());
			if (e == null || e.getType() != s.type()) {
				if (e != null) {
					remove(s.id());
				}
				e = create(lvl, s);
				if (e == null) {
					continue;
				}
				ENTITIES.put(s.id(), e);
			} else {
				e.moveOrInterpolateTo(s.pos(), s.yRot(), s.xRot());
			}
			e.setDeltaMovement(s.motion());
			if (e instanceof PrimedTnt t) {
				t.setFuse(s.fuse());
			} else if (e instanceof ItemEntity i && !ItemEntityAccess.same(i.getItem(), s.item())) {
				i.setItem(s.item());
			}
		}
		for (Integer id : Set.copyOf(ENTITIES.keySet())) {
			if (!seen.contains(id)) {
				remove(id);
			}
		}
	}

	private static Entity create(ClientLevel lvl, EntitySnap s) {
		Entity e = s.type().create(lvl, EntitySpawnReason.LOAD);
		if (e == null) {
			return null;
		}
		int id = ID_BASE - s.id();
		// the same packet a server would send, so falling blocks get their block state the vanilla way
		e.recreateFromPacket(new ClientboundAddEntityPacket(id, UUID.randomUUID(), s.pos().x, s.pos().y, s.pos().z,
				s.xRot(), s.yRot(), s.type(), s.blockId(), s.motion(), s.yRot()));
		if (e instanceof PrimedTnt t) {
			t.setBlockState(Block.stateById(s.blockId()));
		} else if (e instanceof ItemEntity i) {
			i.setItem(s.item());
		}
		try {
			lvl.addEntity(e);
		} catch (RuntimeException ex) {
			VireClient.LOG.warn("[Vire] Could not show sandbox entity {}", s.type(), ex);
			return null;
		}
		return e;
	}

	private static void remove(int sandboxId) {
		Entity e = ENTITIES.remove(sandboxId);
		if (e != null && level != null) {
			level.removeEntity(e.getId(), Entity.RemovalReason.DISCARDED);
		}
	}

	/** Removes every client copy (exit, reset, world change). */
	public static void clear() {
		for (Integer id : Set.copyOf(ENTITIES.keySet())) {
			remove(id);
		}
		ENTITIES.clear();
	}

	/** Small helper so item stacks are only replaced when they really changed. */
	private static final class ItemEntityAccess {
		static boolean same(net.minecraft.world.item.ItemStack a, net.minecraft.world.item.ItemStack b) {
			return net.minecraft.world.item.ItemStack.isSameItemSameComponents(a, b) && a.getCount() == b.getCount();
		}
	}
}
