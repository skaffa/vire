package dev.vire.client.overlay;

import dev.vire.core.region.PosKey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;

import java.util.Map;

/**
 * Signal levels of blocks in the Vire view. The client level holds the mirrored states, so state
 * properties are right; comparator outputs live in block entities the mirror does not update, so they
 * come from the sandbox ({@code comparators}, key → 0..15).
 */
public final class Signals {
	private Signals() {
	}

	/** Blocks that get a label or an arrow (also used to skip chunk sections quickly). */
	public static boolean interesting(BlockState s) {
		return s.isSignalSource() || s.getBlock() instanceof HopperBlock || s.getBlock() instanceof DispenserBlock
				|| s.getBlock() instanceof PistonBaseBlock;
	}

	/**
	 * The signal level a block shows (0..15), for the delay tracker marks: output of components, the
	 * power of dust, on/off of lamps, pistons, doors and the like, and for a plain solid block the best
	 * signal it receives.
	 */
	public static int level(Level level, BlockPos pos, BlockState s, Map<Long, Integer> comparators) {
		if (s.getBlock() instanceof ComparatorBlock) {
			return comparators.getOrDefault(key(pos), 0);
		}
		if (s.hasProperty(BlockStateProperties.POWER)) {
			return s.getValue(BlockStateProperties.POWER);
		}
		for (var p : new net.minecraft.world.level.block.state.properties.BooleanProperty[] {BlockStateProperties.POWERED,
				BlockStateProperties.LIT, BlockStateProperties.EXTENDED, BlockStateProperties.TRIGGERED}) {
			if (s.hasProperty(p)) {
				return s.getValue(p) ? 15 : 0;
			}
		}
		if (s.isSignalSource()) {
			int best = 0;
			for (Direction d : Direction.values()) {
				best = Math.max(best, s.getSignal(level, pos, d));
			}
			return best;
		}
		return received(level, pos, comparators)[0];
	}

	/**
	 * Label text for a component, or null for none: power of dust (only when on), output of repeaters,
	 * comparators, observers, torches, levers, buttons, plates, targets, detectors.
	 */
	public static String label(BlockState s, BlockPos pos, Map<Long, Integer> comparators) {
		if (!s.isSignalSource()) {
			return null;
		}
		if (s.getBlock() instanceof RedstoneWireBlock) {
			int p = s.getValue(RedstoneWireBlock.POWER);
			return p > 0 ? Integer.toString(p) : null;
		}
		if (s.getBlock() instanceof ComparatorBlock) {
			int out = comparators.getOrDefault(key(pos), 0);
			return out + (s.getValue(ComparatorBlock.MODE) == ComparatorMode.SUBTRACT ? " −" : "");
		}
		if (s.hasProperty(BlockStateProperties.LOCKED) && s.getValue(BlockStateProperties.LOCKED)) {
			return (s.getValue(BlockStateProperties.POWERED) ? "15" : "0") + " L";
		}
		if (s.hasProperty(BlockStateProperties.POWER)) {
			return Integer.toString(s.getValue(BlockStateProperties.POWER));
		}
		if (s.hasProperty(BlockStateProperties.POWERED)) {
			return s.getValue(BlockStateProperties.POWERED) ? "15" : "0";
		}
		if (s.hasProperty(BlockStateProperties.LIT)) {
			return s.getValue(BlockStateProperties.LIT) ? "15" : "0";
		}
		return "15"; // redstone block and other constant sources
	}

	/** The face a block outputs to or acts on (for the flow arrows), or null. */
	public static Direction output(BlockState s) {
		if (s.getBlock() instanceof DiodeBlock) {
			return s.getValue(DiodeBlock.FACING).getOpposite(); // facing = the input side
		}
		if (s.getBlock() instanceof ObserverBlock) {
			return s.getValue(ObserverBlock.FACING).getOpposite(); // watches its face, outputs from the back
		}
		if (s.getBlock() instanceof HopperBlock) {
			return s.getValue(HopperBlock.FACING);
		}
		if (s.getBlock() instanceof DispenserBlock) {
			return s.getValue(DispenserBlock.FACING);
		}
		if (s.getBlock() instanceof PistonBaseBlock) {
			return s.getValue(PistonBaseBlock.FACING);
		}
		return null;
	}

	/** True when the arrow should be drawn "on" (red). */
	public static boolean active(BlockState s, BlockPos pos, Map<Long, Integer> comparators) {
		if (s.getBlock() instanceof ComparatorBlock) {
			return comparators.getOrDefault(key(pos), 0) > 0;
		}
		for (var p : new net.minecraft.world.level.block.state.properties.BooleanProperty[] {BlockStateProperties.POWERED,
				BlockStateProperties.EXTENDED, BlockStateProperties.TRIGGERED}) {
			if (s.hasProperty(p)) {
				return s.getValue(p);
			}
		}
		return s.hasProperty(BlockStateProperties.ENABLED) && !s.getValue(BlockStateProperties.ENABLED); // hopper locked
	}

	/**
	 * Power a solid block receives from neighboring components: {weak, strong}. Strong power (from
	 * repeaters, comparators, torches below, attached levers) makes dust next to the block light up; dust
	 * only powers a block weakly. Comparator outputs come from the sandbox map.
	 */
	public static int[] received(Level level, BlockPos pos, Map<Long, Integer> comparators) {
		int weak = 0;
		int strong = 0;
		for (Direction d : Direction.values()) {
			BlockPos n = pos.relative(d);
			BlockState ns = level.getBlockState(n);
			if (!ns.isSignalSource()) {
				continue;
			}
			int sig;
			int direct;
			if (ns.getBlock() instanceof ComparatorBlock) {
				// same rule as DiodeBlock.getSignal: it powers the block it faces away from (facing == d)
				boolean toward = ns.getValue(ComparatorBlock.FACING) == d;
				sig = toward ? comparators.getOrDefault(key(n), 0) : 0;
				direct = sig;
			} else {
				sig = ns.getSignal(level, n, d);
				direct = ns.getBlock() instanceof RedstoneWireBlock ? 0 : ns.getDirectSignal(level, n, d);
			}
			weak = Math.max(weak, sig);
			strong = Math.max(strong, direct);
		}
		return new int[] {Math.max(weak, strong), strong};
	}

	/** Red shade for a signal level; grey for 0. */
	public static int color(int level) {
		if (level <= 0) {
			return 0xFFA0A0A0;
		}
		int r = 0x90 + (0xFF - 0x90) * level / 15;
		int g = 0x20 + 0x30 * level / 15;
		return 0xFF000000 | r << 16 | g << 8 | g;
	}

	public static long key(BlockPos p) {
		return PosKey.pack(p.getX(), p.getY(), p.getZ());
	}
}
