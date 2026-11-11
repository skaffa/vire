package dev.vire.client.dev;

import static dev.vire.client.dev.SelfTest.check;
import static dev.vire.client.dev.SelfTest.click;
import static dev.vire.client.dev.SelfTest.is;
import static dev.vire.client.dev.SelfTest.step;
import static dev.vire.client.dev.SelfTest.waitFor;

import dev.vire.client.VireClient;
import dev.vire.client.design.Designs;
import dev.vire.client.design.ExitScreen;
import dev.vire.client.design.LitematicExport;
import dev.vire.client.design.LitematicaHook;
import dev.vire.client.interact.VireWorld;
import dev.vire.client.mode.VireMode;
import dev.vire.core.design.Design;
import dev.vire.core.design.Normalizer;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.config.options.ConfigBoolean;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.Half;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * M5 acceptance (PLAN.md §16), run with {@code -Plitematica -Pselftest=litematic}: design six rotated
 * parts in Vire Mode, export them, check that Litematica 0.29.1 reads every state back exactly and
 * placed the schematic at the design's real corner, then let Litematica Printer 3.2.x try to build it
 * in survival on the test server. The printer result is logged, not checked: 3.2.3 on 26.3 places the
 * piston and stairs, the observer the wrong way (its rotation reaches the server after the click), and
 * never tries the repeater, comparator or hopper.
 */
final class LitematicSelfTest {
	private static final String NAME = "m5_selftest";
	private static BlockPos body;
	private static Design design;
	private static LitematicExport.Result result;
	private static Map<BlockPos, BlockState> parts;

	private LitematicSelfTest() {
	}

	static void steps() {
		step(0, "go to the build spot", mc -> {
			mc.player.connection.sendCommand("tp @s 380 -60 300 0 30");
			mc.player.connection.sendCommand("clear @s");
		});
		step(60, "clear the area", mc -> {
			body = mc.player.blockPosition();
			cmd(mc, "fill " + (body.getX() - 5) + " " + body.getY() + " " + (body.getZ() - 5) + " " + (body.getX() + 5) + " " + (body.getY() + 3) + " "
					+ (body.getZ() + 5) + " minecraft:air");
			cmd(mc, "fill " + (body.getX() - 5) + " " + (body.getY() - 1) + " " + (body.getZ() - 5) + " " + (body.getX() + 5) + " " + (body.getY() - 1)
					+ " " + (body.getZ() + 5) + " minecraft:grass_block");
			BlockPos row = body.south(2);
			parts = new java.util.LinkedHashMap<>();
			parts.put(row.west(3), Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.NORTH));
			parts.put(row.west(2), Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.SOUTH).setValue(RepeaterBlock.DELAY, 3));
			parts.put(row.west(1), Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.WEST)
					.setValue(ComparatorBlock.MODE, ComparatorMode.SUBTRACT));
			parts.put(row.east(1), Blocks.STICKY_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.UP));
			parts.put(row.east(2), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST).setValue(StairBlock.HALF, Half.BOTTOM));
			parts.put(row.east(3), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.NORTH));
			for (SchematicPlacement p : List.copyOf(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())) {
				DataManager.getSchematicPlacementManager().removeSchematicPlacement(p);
			}
		});
		step(20, "enter", mc -> click(VireClient.toggleKey()));
		waitFor("loaded", () -> VireMode.active() && VireMode.session().loaded, 2400);
		step(1, "design six rotated parts", mc -> parts.forEach(VireWorld::placeState));
		waitFor("parts in the sandbox", () -> parts.keySet().stream().allMatch(p -> !Minecraft.getInstance().level.getBlockState(p).isAir()), 100);
		step(5, "leave", mc -> {
			VireMode.openExitScreen = false;
			click(VireClient.toggleKey());
		});
		waitFor("left", () -> !VireMode.active(), 100);
		step(5, "export", mc -> {
			design = VireMode.lastDesign;
			check(design != null && design.entries().size() == 6, "design has 6 parts (" + (design == null ? 0 : design.entries().size()) + ")");
			Design saved = ExitScreen.apply(design, NAME, ExitScreen.Choice.EXPORT);
			check(saved != null, "saved and exported");
			result = new LitematicExport.Result(LitematicExport.dir(mc).resolve(NAME + ".litematic"), corner(), 6);
		});
		step(2, "Litematica reads it back", mc -> {
			Path f = result.file();
			check(Files.isRegularFile(f), "file " + f);
			LitematicaSchematic s = LitematicaSchematic.createFromFile(f.getParent(), f.getFileName().toString());
			check(s != null, "Litematica 0.29.1 loads the file");
			if (s == null) {
				return;
			}
			LitematicaBlockStateContainer c = s.getSubRegionContainer(NAME);
			check(c != null, "region " + NAME);
			BlockPos o = result.origin();
			int ok = 0;
			for (Map.Entry<BlockPos, BlockState> e : parts.entrySet()) {
				BlockPos p = e.getKey();
				BlockState got = c.get(p.getX() - o.getX(), p.getY() - o.getY(), p.getZ() - o.getZ());
				boolean same = Normalizer.matches(Designs.ref(e.getValue()), Designs.ref(got));
				if (!same) {
					VireClient.LOG.error("[selftest] {} expected {} Litematica read {}", p, e.getValue(), got);
				}
				ok += same ? 1 : 0;
			}
			check(ok == 6, "Litematica reads all 6 rotations back exactly (" + ok + ")");
			check(o.equals(LitematicaHook.placedAt(NAME)), "placement at the design's corner " + o.toShortString() + " (" + LitematicaHook.placedAt(NAME) + ")");
		});
		step(1, "give items, start the printer", mc -> {
			for (String item : List.of("observer", "repeater", "comparator", "sticky_piston", "oak_stairs", "hopper")) {
				cmd(mc, "give @s minecraft:" + item + " 1");
			}
			printerInt("PRINTING_INTERVAL", 12); // the printer's own advice for wrong facings and ghost blocks
			printer("PRINT_IN_AIR", false);
			printer("PRINT_MODE", true);
			printer("ROTATE", true);
			printer("INTERACT_BLOCKS", true);
		});
		// Printer 3.2.3 on 26.3 builds only part of this (PLAN.md §16 M5): logged, not checked.
		step(600, "printer result", mc -> {
			printer("PRINT_MODE", false);
			for (Map.Entry<BlockPos, BlockState> e : parts.entrySet()) {
				VireClient.LOG.info("[selftest] printed {}: {} (want {})", e.getKey().toShortString(), mc.level.getBlockState(e.getKey()), e.getValue());
			}
			VireClient.LOG.info("[selftest] printer built {} of 6 parts correctly", builtCount());
			mc.player.setXRot(40);
		});
		step(10, "screenshot", mc -> Screenshot.grab(mc.gameDirectory, "vire-printed.png", mc.gameRenderer.mainRenderTarget(), 1,
				msg -> VireClient.LOG.info("[selftest] screenshot: {}", msg.getString())));
		step(5, "clean up", mc -> {
			for (SchematicPlacement p : List.copyOf(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())) {
				DataManager.getSchematicPlacementManager().removeSchematicPlacement(p);
			}
			cmd(mc, "fill " + (body.getX() - 5) + " " + body.getY() + " " + (body.getZ() - 5) + " " + (body.getX() + 5) + " " + (body.getY() + 3) + " "
					+ (body.getZ() + 5) + " minecraft:air");
			cmd(mc, "clear @s");
		});
		step(20, "report", SelfTest::report);
	}

	private static int builtCount() {
		Minecraft mc = Minecraft.getInstance();
		int n = 0;
		for (Map.Entry<BlockPos, BlockState> e : parts.entrySet()) {
			if (Normalizer.matches(Designs.ref(e.getValue()), Designs.ref(mc.level.getBlockState(e.getKey())))) {
				n++;
			}
		}
		return n;
	}

	private static BlockPos corner() {
		int x = Integer.MAX_VALUE, y = Integer.MAX_VALUE, z = Integer.MAX_VALUE;
		for (BlockPos p : parts.keySet()) {
			x = Math.min(x, p.getX());
			y = Math.min(y, p.getY());
			z = Math.min(z, p.getZ());
		}
		return new BlockPos(x, y, z);
	}

	/** Litematica Printer's options, by reflection (the printer jar is only on the dev runtime classpath). */
	private static void printer(String option, boolean value) {
		try {
			Class<?> c = Class.forName("me.aleksilassila.litematica.printer.config.Configs");
			((ConfigBoolean) c.getField(option).get(null)).setBooleanValue(value);
		} catch (ReflectiveOperationException e) {
			check(false, "printer option " + option + ": " + e);
		}
	}

	private static void printerInt(String option, int value) {
		try {
			Class<?> c = Class.forName("me.aleksilassila.litematica.printer.config.Configs");
			var o = (fi.dy.masa.malilib.config.options.ConfigInteger) c.getField(option).get(null);
			VireClient.LOG.info("[selftest] printer {} {} -> {}", option, o.getIntegerValue(), value);
			o.setIntegerValue(value);
			var r = (fi.dy.masa.malilib.config.options.ConfigDouble) c.getField("PRINTING_RANGE").get(null);
			VireClient.LOG.info("[selftest] printer range {}", r.getDoubleValue());
		} catch (ReflectiveOperationException e) {
			check(false, "printer option " + option + ": " + e);
		}
	}

	private static void cmd(Minecraft mc, String c) {
		mc.player.connection.sendCommand(c);
	}
}
