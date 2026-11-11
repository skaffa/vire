package dev.vire.core.config;

/**
 * User settings, stored as {@code config/vire/config.json}. See PLAN.md §13.
 * Plain fields so Gson can (de)serialize them; call {@link #clamp()} after loading.
 */
public final class VireConfig {
	public int regionSize = 32;
	public int reach = 64;
	public double cameraSpeed = 1.0;
	public boolean keepWarm = false;
	public int randomTickSpeed = 3;
	public boolean exitOnDamage = true;
	public int labelRange = 24;
	public boolean autoLoadLitematica = true;
	public boolean exportSkipAir = false;
	public boolean packetLog = false;

	/** Pulls every value into its allowed range and returns {@code this}. */
	public VireConfig clamp() {
		regionSize = clamp(regionSize, 16, 64);
		reach = clamp(reach, 8, 128);
		cameraSpeed = Math.max(0.1, Math.min(10.0, Double.isNaN(cameraSpeed) ? 1.0 : cameraSpeed));
		randomTickSpeed = clamp(randomTickSpeed, 0, 4096);
		labelRange = clamp(labelRange, 4, 64);
		return this;
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}
}
