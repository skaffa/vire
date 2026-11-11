package dev.vire.core.probe;

/**
 * Delay tracker and frequency meter (PLAN.md §8). Fed with the signal level (0–15) of a marked input
 * and a marked output after every sandbox tick, with the sandbox tick counter. Because both ticks come
 * from the same counter, the numbers are exact.
 *
 * <ul>
 *   <li><b>Delay</b>: the first output change after an input change, minus the tick of that input
 *       change. Only the latest input change counts (a new input change before the output reacted
 *       replaces the older one).</li>
 *   <li><b>Period</b>: ticks between the last two rising edges (0 → &gt;0) of the input, or of the
 *       output when no input is marked. <b>On time</b>: from the latest rising edge to the falling edge
 *       after it.</li>
 * </ul>
 */
public final class DelayMeter {
	/** Game ticks per second at the normal tick rate: frequency is given in game time. */
	public static final double GAME_TPS = 20.0;

	private final Edge in = new Edge();
	private final Edge out = new Edge();
	private boolean hasIn;
	private boolean hasOut;

	/** Input change still waiting for the output to react, or -1. */
	private long pendingIn = -1;
	private boolean pendingRising;
	private long delay = -1;
	private boolean delayRising;

	/** Forgets everything (new load, marks moved). */
	public void reset() {
		in.reset();
		out.reset();
		hasIn = false;
		hasOut = false;
		pendingIn = -1;
		delay = -1;
	}

	/**
	 * Records the signals after sandbox tick {@code tick}. A marker that is not set passes -1. The first
	 * call after {@link #reset()} (or after a marker is set) only takes the baseline.
	 */
	public void observe(long tick, int inSignal, int outSignal) {
		if (inSignal >= 0) {
			if (hasIn && in.update(tick, inSignal)) {
				pendingIn = tick;
				pendingRising = inSignal > 0 && in.previous == 0;
			} else if (!hasIn) {
				in.baseline(inSignal);
			}
			hasIn = true;
		} else {
			hasIn = false;
			in.reset();
			pendingIn = -1;
		}
		if (outSignal >= 0) {
			if (hasOut && out.update(tick, outSignal)) {
				if (pendingIn >= 0) {
					delay = tick - pendingIn;
					delayRising = pendingRising;
					pendingIn = -1;
				}
			} else if (!hasOut) {
				out.baseline(outSignal);
			}
			hasOut = true;
		} else {
			hasOut = false;
			out.reset();
		}
	}

	/** Latest measured delay in game ticks, or -1. */
	public long delay() {
		return delay;
	}

	/** Whether the latest delay was measured on a rising input edge (off → on). */
	public boolean delayRising() {
		return delayRising;
	}

	/** Period in game ticks between the last two rising edges (input first, else output), or -1. */
	public long period() {
		long p = in.period();
		return p >= 0 ? p : out.period();
	}

	/** Ticks the signal stayed on in the latest full pulse (input first, else output), or -1. */
	public long onTime() {
		long p = in.onTime();
		return p >= 0 ? p : out.onTime();
	}

	/** Frequency in Hz of game time (20 gt = 1 s), or 0 without a period. */
	public double frequency() {
		long p = period();
		return p > 0 ? GAME_TPS / p : 0;
	}

	/** {@code "6 gt (3 rt, 0.30 s)"}. */
	public static String ticks(long gt) {
		String rt = gt % 2 == 0 ? Long.toString(gt / 2) : String.format(java.util.Locale.ROOT, "%.1f", gt / 2.0);
		return gt + " gt (" + rt + " rt, " + String.format(java.util.Locale.ROOT, "%.2f", gt / GAME_TPS) + " s)";
	}

	/** Edge history of one signal. */
	private static final class Edge {
		int level = -1;
		int previous = -1;
		long lastRise = -1;
		long prevRise = -1;
		long lastFall = -1;

		void reset() {
			level = -1;
			previous = -1;
			lastRise = -1;
			prevRise = -1;
			lastFall = -1;
		}

		void baseline(int signal) {
			level = signal;
		}

		/** Returns true when the signal changed. */
		boolean update(long tick, int signal) {
			if (signal == level) {
				return false;
			}
			previous = level;
			level = signal;
			if (previous == 0 && signal > 0) {
				prevRise = lastRise;
				lastRise = tick;
			} else if (previous > 0 && signal == 0) {
				lastFall = tick;
			}
			return true;
		}

		long period() {
			return prevRise >= 0 ? lastRise - prevRise : -1;
		}

		long onTime() {
			if (lastRise < 0 || lastFall < 0) {
				return -1;
			}
			// the latest complete pulse: rise then fall
			if (lastFall > lastRise) {
				return lastFall - lastRise;
			}
			return prevRise >= 0 && lastFall > prevRise ? lastFall - prevRise : -1;
		}
	}
}
