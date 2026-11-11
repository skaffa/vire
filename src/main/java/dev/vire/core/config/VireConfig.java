package dev.vire.core.config;

/**
 * User settings, stored as {@code config/vire/config.json}. See PLAN.md §13.
 * Plain fields so Gson can (de)serialize them; call {@link #clamp()} after loading.
 */
public final class VireConfig {
	public int regionSize = 32;
	public int reach = 64;
	public double cameraSpeed = 1.0;
	/** Start the sandbox server when joining a world and keep it running while connected (boot takes ~10 s). */
	public boolean keepWarm = true;
	public int randomTickSpeed = 3;
	public boolean exitOnDamage = true;
	public int labelRange = 24;
	/** Direction arrows on observers, repeaters, comparators, hoppers, droppers, pistons (toggle key J). */
	public boolean showArrows = true;
	/** Name of the design shown as ghost, or null. */
	public String activeGhost = null;
	/** Ghost blocks are drawn within this many blocks of the player. */
	public int ghostRange = 48;
	public int maxGhostsDrawn = 2000;
	/** Materials panel next to the ghost (toggle key B). */
	public boolean showBom = true;
	/** At most this many signal labels, nearest first. */
	public int maxLabels = 300;
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
		maxLabels = clamp(maxLabels, 16, 4096);
		ghostRange = clamp(ghostRange, 8, 256);
		maxGhostsDrawn = clamp(maxGhostsDrawn, 16, 20000);
		return this;
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}
}
