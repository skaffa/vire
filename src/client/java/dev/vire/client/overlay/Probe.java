package dev.vire.client.overlay;

import dev.vire.client.mode.VireMode;
import dev.vire.client.mode.VireSession;
import dev.vire.core.probe.DelayMeter;
import dev.vire.core.region.PosKey;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Delay tracker and frequency meter (PLAN.md §8): the player marks one block as input (N) and one as
 * output (M); after every mirror batch the signal of both goes into the session's {@link DelayMeter}
 * with the sandbox tick of that batch. The sandbox sends a batch for every tick in which something
 * changed, so the ticks are exact.
 */
public final class Probe {
	private Probe() {
	}

	/** N / M: mark the targeted block as input / output; again on the same block removes the mark. */
	public static void mark(Minecraft mc, boolean input) {
		VireSession s = VireMode.session();
		if (s == null) {
			return;
		}
		if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			VireMode.hint(mc, "Look at a block to mark it as " + (input ? "input" : "output"));
			return;
		}
		BlockPos p = hit.getBlockPos();
		if (!s.box.contains(p.getX(), p.getY(), p.getZ())) {
			VireMode.hint(mc, "Outside the Vire region");
			return;
		}
		set(s, p, input);
		Long now = input ? s.markIn : s.markOut;
		VireMode.hint(mc, (input ? "Input" : "Output") + (now == null ? " mark removed" : " marked"));
	}

	/** Sets (or, when already there, removes) a mark. Public for the self-test. */
	public static void set(VireSession s, BlockPos p, boolean input) {
		long k = PosKey.pack(p.getX(), p.getY(), p.getZ());
		if (input) {
			s.markIn = Long.valueOf(k).equals(s.markIn) ? null : k;
		} else {
			s.markOut = Long.valueOf(k).equals(s.markOut) ? null : k;
		}
		// start over with the baseline the client shows now, so the very next batch can count as a change
		s.meter.reset();
		s.meter.observe(s.sandboxTick, signal(s, s.markIn), signal(s, s.markOut));
	}

	/** After every mirror batch of the current load. */
	public static void afterBatch(VireSession s, int generation, long tick) {
		if (generation != s.meterGeneration) {
			s.meterGeneration = generation; // reset/undo/redo restarted the circuit
			s.meter.reset();
		}
		if (s.markIn == null && s.markOut == null) {
			return;
		}
		s.meter.observe(tick, signal(s, s.markIn), signal(s, s.markOut));
	}

	private static int signal(VireSession s, Long key) {
		if (key == null) {
			return -1;
		}
		BlockPos p = new BlockPos(PosKey.x(key), PosKey.y(key), PosKey.z(key));
		return Signals.level(s.level, p, s.level.getBlockState(p), s.comparators);
	}

	/** HUD line for the meter, or null when nothing is marked. */
	public static String hudLine(VireSession s) {
		if (s.markIn == null && s.markOut == null) {
			return null;
		}
		DelayMeter m = s.meter;
		StringBuilder b = new StringBuilder();
		if (s.markIn != null && s.markOut != null) {
			b.append("IN→OUT ");
			b.append(m.delay() < 0 ? "Δ –" : "Δ " + DelayMeter.ticks(m.delay()) + (m.delayRising() ? " on" : " off"));
		} else {
			b.append(s.markIn != null ? "IN marked (M marks the output)" : "OUT marked (N marks the input)");
		}
		if (m.period() > 0) {
			b.append("   period ").append(m.period()).append(" gt · ")
					.append(String.format(java.util.Locale.ROOT, "%.2f Hz", m.frequency()));
			if (m.onTime() > 0) {
				b.append(" · on ").append(m.onTime()).append(" gt");
			}
		}
		return b.toString();
	}
}
