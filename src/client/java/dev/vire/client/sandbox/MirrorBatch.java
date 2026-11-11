package dev.vire.client.sandbox;

import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * What the sandbox sends to the client after a tick (PLAN.md §6.1): block events (piston starts) in
 * order, then the final state of every changed position inside the region, plus the clock status.
 *
 * @param generation the load this batch belongs to; the client drops batches of older loads
 */
public record MirrorBatch(int generation, List<BlockEventData> events, long[] keys, BlockState[] states,
		boolean frozen, float tickRate, long tick) {
}
