package dev.vire.client.dev;

import static dev.vire.client.dev.SelfTest.click;
import static dev.vire.client.dev.SelfTest.step;
import static dev.vire.client.dev.SelfTest.waitFor;

import dev.vire.client.VireClient;
import dev.vire.client.design.Designs;
import dev.vire.client.design.ExitScreen;
import dev.vire.client.design.GhostLayer;
import dev.vire.client.interact.VireWorld;
import dev.vire.client.mode.VireMode;
import dev.vire.client.overlay.Probe;
import dev.vire.client.sandbox.Sandbox;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ComparatorMode;

/**
 * Gallery screenshots for the README and Modrinth (run with {@code --width 1600 --height 900}):
 * Vire Mode with labels, arrows and the delay meter; the exit dialog; the ghost with the materials.
 * Files: {@code run/screenshots/gallery-*.png}.
 */
final class ShotsTest {
	private static final String NAME = "gallery_demo";
	private static BlockPos body;

	private ShotsTest() {
	}

	static void steps() {
		step(0, "go to a clear spot, daytime, no chat", mc -> {
			mc.player.connection.sendCommand("tp @s 300 -60 360 180 0");
			mc.player.connection.sendCommand("time set 6000");
			mc.player.connection.sendCommand("weather clear");
			// no mobs in the pictures (zombies burn in daylight)
			mc.player.connection.sendCommand("gamerule spawn_monsters false");
			mc.player.connection.sendCommand("kill @e[type=!player]");
			mc.options.chatVisibility().set(ChatVisiblity.HIDDEN);
		});
		step(60, "clear", mc -> {
			body = mc.player.blockPosition();
			cmd(mc, "fill " + (body.getX() - 8) + " " + body.getY() + " " + (body.getZ() - 10) + " " + (body.getX() + 8) + " " + (body.getY() + 4)
					+ " " + (body.getZ() + 3) + " minecraft:air");
			cmd(mc, "fill " + (body.getX() - 8) + " " + (body.getY() - 1) + " " + (body.getZ() - 10) + " " + (body.getX() + 8) + " " + (body.getY() - 1)
					+ " " + (body.getZ() + 3) + " minecraft:grass_block");
			GhostLayer.show(null);
		});
		step(20, "enter", mc -> click(VireClient.toggleKey()));
		waitFor("loaded", () -> VireMode.active() && VireMode.session().loaded, 2400);
		step(1, "build a small circuit", mc -> {
			BlockPos a = body.north(4);
			// (redstone block placed later) -> dust -> repeater chain 1-3-2 -> dust -> lamp
			put(a.west(5), Blocks.REDSTONE_WIRE.defaultBlockState());
			put(a.west(4), Blocks.REDSTONE_WIRE.defaultBlockState());
			int[] delays = {1, 3, 2};
			for (int i = 0; i < 3; i++) {
				put(a.west(3 - i), Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.WEST).setValue(RepeaterBlock.DELAY, delays[i]));
			}
			put(a, Blocks.REDSTONE_WIRE.defaultBlockState());
			put(a.east(1), Blocks.REDSTONE_WIRE.defaultBlockState());
			put(a.east(2), Blocks.REDSTONE_WIRE.defaultBlockState());
			put(a.east(3), Blocks.REDSTONE_LAMP.defaultBlockState());
			// comparator subtract: rear from a redstone block, side from a short dust line
			BlockPos c = body.north(7);
			put(c.west(2), Blocks.REDSTONE_BLOCK.defaultBlockState());
			put(c.west(1), Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.WEST)
					.setValue(ComparatorBlock.MODE, ComparatorMode.SUBTRACT));
			put(c, Blocks.REDSTONE_WIRE.defaultBlockState());
			put(c.west(1).north(), Blocks.REDSTONE_WIRE.defaultBlockState());
			put(c.west(1).north().east(), Blocks.REDSTONE_WIRE.defaultBlockState());
			put(c.west(1).north().east(2), Blocks.REDSTONE_WIRE.defaultBlockState());
			put(c.west(1).north().east(3), Blocks.REDSTONE_BLOCK.defaultBlockState());
			// observer clock with a lamp
			BlockPos o = body.north(7).east(4);
			put(o, Blocks.REDSTONE_LAMP.defaultBlockState());
			put(o.east(), Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.EAST));
			put(o.east(2), Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.WEST));
		});
		step(10, "mark in/out, run", mc -> {
			Probe.set(VireMode.session(), body.north(4).west(6), true);
			Probe.set(VireMode.session(), body.north(4).east(3), false);
			Sandbox.togglePause();
		});
		step(10, "switch the input on", mc -> put(body.north(4).west(6), Blocks.REDSTONE_BLOCK.defaultBlockState()));
		step(60, "pause, camera", mc -> {
			Sandbox.togglePause();
			VireMode.session().camera.snapTo(body.getX() + 0.5, body.getY() + 4.5, body.getZ() - 0.5, 180, 42);
		});
		step(20, "shot 1", mc -> shot(mc, "gallery-1-vire-mode.png"));
		step(10, "leave: dialog", mc -> {
			VireMode.openExitScreen = true;
			click(VireClient.toggleKey());
		});
		waitFor("dialog", () -> Minecraft.getInstance().gui.screen() instanceof ExitScreen, 100);
		step(20, "shot 2", mc -> shot(mc, "gallery-2-exit-dialog.png"));
		step(5, "keep as ghost", mc -> {
			ExitScreen.apply(VireMode.lastDesign, NAME, ExitScreen.Choice.GHOST);
			mc.gui.setScreen(null); // the design is already kept; closing writes _autosave too, which is fine
			mc.player.connection.sendCommand("give @s minecraft:repeater 2");
			mc.player.connection.sendCommand("give @s minecraft:redstone 5");
			mc.player.connection.sendCommand("tp @s ~-1 ~ ~3 174 20");
		});
		step(40, "shot 3", mc -> shot(mc, "gallery-3-ghost.png"));
		step(5, "settings", mc -> mc.gui.setScreen(new dev.vire.client.ConfigScreen(null)));
		step(20, "shot 4", mc -> shot(mc, "gallery-4-settings.png"));
		step(5, "close settings", mc -> mc.gui.setScreen(null));
		step(5, "clean up", mc -> {
			GhostLayer.show(null);
			try {
				Designs.STORE.delete(NAME);
			} catch (java.io.IOException e) {
				VireClient.LOG.warn("[shots] delete", e);
			}
			mc.player.connection.sendCommand("clear @s");
			mc.options.chatVisibility().set(ChatVisiblity.FULL);
		});
		step(20, "report", SelfTest::report);
	}

	private static void put(BlockPos p, BlockState s) {
		VireWorld.placeState(p, s);
	}

	private static void shot(Minecraft mc, String file) {
		Screenshot.grab(mc.gameDirectory, file, mc.gameRenderer.mainRenderTarget(), 1,
				msg -> VireClient.LOG.info("[selftest] screenshot: {}", msg.getString()));
	}

	private static void cmd(Minecraft mc, String c) {
		mc.player.connection.sendCommand(c);
	}
}
