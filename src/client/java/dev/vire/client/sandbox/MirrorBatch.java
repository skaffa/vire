package dev.vire.client.sandbox;

import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * What the sandbox sends to the client after a tick (PLAN.md §6.1): block events (piston starts) in
 * order, then the final state of every changed position inside the region, the tracked entities
 * (falling blocks, TNT, items) and the clock status.
 *
 * @param generation the load this batch belongs to; the client drops batches of older loads
 * @param ready      all chunks of the region tick (scheduled ticks run on time); controls wait for this
 */
public record MirrorBatch(int generation, List<BlockEventData> events, long[] keys, BlockState[] states,
		List<EntitySnap> entities, boolean ready, boolean frozen, float tickRate, long tick) {
}
