package dev.vire.client.dev;

import static dev.vire.client.dev.SelfTest.check;
import static dev.vire.client.dev.SelfTest.click;
import static dev.vire.client.dev.SelfTest.is;
import static dev.vire.client.dev.SelfTest.set;
import static dev.vire.client.dev.SelfTest.step;
import static dev.vire.client.dev.SelfTest.waitFor;

import dev.vire.client.VireClient;
import dev.vire.client.design.Designs;
import dev.vire.client.design.ExitScreen;
import dev.vire.client.design.GhostLayer;
import dev.vire.client.interact.VireWorld;
import dev.vire.client.mode.VireMode;
import dev.vire.core.design.Design;
import dev.vire.core.design.DesignStore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * M4 acceptance (PLAN.md §16): design in Vire Mode, keep it as a ghost, then build it for real in
 * survival on the test server through the real key paths. Checks the BOM against a hand count, the
 * build assist (a repeater placed the wrong way shows "facing: west expected"), that every ghost block
 * clears and that the design completes.
 *
 * <p>Layout on the player's own row, looking east: wire, wire, repeater (input west), lamp; stone two
 * blocks north; a real dirt block two blocks west that the design removes.
 */
final class GhostTest {
	private static final String NAME = "m4_selftest";
	private static BlockPos body;
	private static BlockPos wire1;
	private static BlockPos wire2;
	private static BlockPos repeater;
	private static BlockPos lamp;
	private static BlockPos stone;
	private static BlockPos dirt;
	private static Design design;

	private GhostTest() {
	}

	static void steps() {
		step(0, "go to the build spot", mc -> {
			mc.player.connection.sendCommand("tp @s 340 -60 300 -90 0");
			mc.player.connection.sendCommand("clear @s");
		});
		step(60, "clear the area, put a dirt block to remove", mc -> {
			body = mc.player.blockPosition();
			wire1 = body.east(1);
			wire2 = body.east(2);
			repeater = body.east(3);
			lamp = body.east(4);
			stone = body.north(2);
			dirt = body.west(2);
			cmd(mc, "fill " + (body.getX() - 4) + " " + body.getY() + " " + (body.getZ() - 4) + " " + (body.getX() + 6) + " " + (body.getY() + 3) + " "
					+ (body.getZ() + 4) + " minecraft:air");
			cmd(mc, "fill " + (body.getX() - 4) + " " + (body.getY() - 1) + " " + (body.getZ() - 4) + " " + (body.getX() + 6) + " " + (body.getY() - 1)
					+ " " + (body.getZ() + 4) + " minecraft:grass_block");
			cmd(mc, "setblock " + dirt.getX() + " " + dirt.getY() + " " + dirt.getZ() + " minecraft:dirt");
			GhostLayer.show(null);
			try {
				Designs.STORE.delete(NAME);
				Designs.STORE.delete(DesignStore.AUTOSAVE);
			} catch (java.io.IOException e) {
				throw new IllegalStateException(e);
			}
		});
		waitFor("dirt is there", () -> is(dirt, Blocks.DIRT), 100);
		step(1, "enter", mc -> click(VireClient.toggleKey()));
		waitFor("loaded", () -> VireMode.active() && VireMode.session().loaded, 2400);
		step(1, "design: wire, wire, repeater, lamp, stone; remove the dirt", mc -> {
			VireWorld.placeState(wire1, Blocks.REDSTONE_WIRE.defaultBlockState());
			VireWorld.placeState(wire2, Blocks.REDSTONE_WIRE.defaultBlockState());
			VireWorld.placeState(repeater, Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.WEST)
					.setValue(RepeaterBlock.DELAY, 2));
			VireWorld.placeState(lamp, Blocks.REDSTONE_LAMP.defaultBlockState());
			VireWorld.placeState(stone, Blocks.STONE.defaultBlockState());
			VireWorld.breakAt(dirt);
		});
		waitFor("design built in the sandbox", () -> is(lamp, Blocks.REDSTONE_LAMP) && is(stone, Blocks.STONE)
				&& Minecraft.getInstance().level.getBlockState(dirt).isAir(), 100);
		step(5, "leave: the exit dialog opens", mc -> click(VireClient.toggleKey()));
		waitFor("exit dialog shown", () -> Minecraft.getInstance().gui.screen() instanceof ExitScreen, 100);
		step(1, "Escape keeps it as _autosave", mc -> mc.gui.setScreen(null));
		waitFor("autosave written", () -> java.nio.file.Files.exists(Designs.STORE.file(DesignStore.AUTOSAVE)), 40);
		step(1, "keep as ghost", mc -> {
			design = VireMode.lastDesign;
			check(design != null && design.entries().size() == 6, "design has 6 positions (" + (design == null ? 0 : design.entries().size()) + ")");
			Design saved = ExitScreen.apply(design, NAME, ExitScreen.Choice.GHOST);
			check(saved != null && GhostLayer.design() != null && GhostLayer.design().name().equals(NAME), "saved and shown as ghost " + NAME);
			check(design.world().equals(Designs.worldId(mc)), "design belongs to this world (" + design.world() + ")");
			check(VireClient.config.activeGhost != null && VireClient.config.activeGhost.equals(NAME), "active ghost remembered in the config");
		});
		step(12, "real world is unchanged, ghost has 6 to do", mc -> {
			check(is(dirt, Blocks.DIRT) && mc.level.getBlockState(lamp).isAir(), "real world restored after exit");
			check(GhostLayer.remaining() == 6, "6 ghost positions left (" + GhostLayer.remaining() + ")");
			Map<String, int[]> bom = GhostLayer.bom();
			VireClient.LOG.info("[selftest] BOM: {}", text(bom));
			check(bom.size() == 4 && need(bom, "redstone") == 2 && need(bom, "repeater") == 1 && need(bom, "redstone_lamp") == 1
					&& need(bom, "stone") == 1, "BOM by hand: 2 redstone, 1 repeater, 1 lamp, 1 stone (" + text(bom) + ")");
			check(bom.values().stream().allMatch(v -> v[1] == 0), "inventory empty in the BOM");
			cmd(mc, "give @s minecraft:redstone 2");
			cmd(mc, "give @s minecraft:repeater 1");
			cmd(mc, "give @s minecraft:redstone_lamp 1");
			cmd(mc, "give @s minecraft:stone 1");
		});
		waitFor("BOM sees the inventory", () -> GhostLayer.bom().values().stream().allMatch(v -> v[1] >= v[0]), 100);

		// someone puts a repeater the wrong way round (facing east)
		step(1, "wrong repeater by command", mc -> cmd(mc, "setblock " + repeater.getX() + " " + repeater.getY() + " " + repeater.getZ()
				+ " minecraft:repeater[facing=east,delay=2]"));
		waitFor("repeater there (wrong way)", () -> is(repeater, Blocks.REPEATER), 100);
		step(5, "build assist says what is wrong", mc -> {
			GhostLayer.Ghost g = ghost(repeater);
			check(g != null && g.status() == GhostLayer.Status.WRONG, "wrong repeater shows yellow (" + (g == null ? null : g.status()) + ")");
			check(g != null && "facing: west expected".equals(g.difference()), "tooltip: facing: west expected (" + (g == null ? null : g.difference()) + ")");
			check(GhostLayer.remaining() == 6, "wrong block does not count as built");
			lookAt(mc, new Vec3(repeater.getX(), repeater.getY(), repeater.getZ() + 0.5));
		});
		step(20, "screenshot", mc -> Screenshot.grab(mc.gameDirectory, "vire-ghost.png", mc.gameRenderer.mainRenderTarget(), 1,
				msg -> VireClient.LOG.info("[selftest] screenshot: {}", msg.getString())));
		breakBlock(() -> repeater);
		waitFor("wrong repeater broken", () -> Minecraft.getInstance().level.getBlockState(repeater).isAir(), 100);
		place(() -> repeater, Items.REPEATER, Direction.EAST);
		// a new repeater has delay 1; the design wants 2
		waitFor("hand-placed repeater: delay 2 expected", () -> ghost(repeater) != null && ghost(repeater).status() == GhostLayer.Status.WRONG
				&& "delay: 2 expected".equals(ghost(repeater).difference()), 100);
		step(1, "look at the repeater", mc -> lookAt(mc, new Vec3(repeater.getX() + 0.5, repeater.getY() + 0.06, repeater.getZ() + 0.5)));
		waitFor("aimed", () -> Minecraft.getInstance().hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(repeater), 40);
		step(1, "right click: delay 1 -> 2", mc -> click(mc.options.keyUse));
		waitFor("repeater now matches the design", () -> ghost(repeater) != null && ghost(repeater).status() == GhostLayer.Status.DONE, 100);
		place(() -> wire1, Items.REDSTONE, Direction.EAST);
		place(() -> wire2, Items.REDSTONE, Direction.EAST);
		place(() -> stone, Items.STONE, Direction.NORTH);
		waitFor("wire and stone built", () -> GhostLayer.remaining() == 2, 100);
		breakBlock(() -> dirt);
		waitFor("dirt removed", () -> Minecraft.getInstance().level.getBlockState(dirt).isAir(), 200);
		step(3, "one left", mc -> check(GhostLayer.remaining() == 1, "only the lamp left (" + GhostLayer.remaining() + ")"));
		place(() -> lamp, Items.REDSTONE_LAMP, Direction.EAST);
		waitFor("design complete: ghost cleared", () -> GhostLayer.design() == null, 100);
		step(1, "after complete", mc -> {
			check(VireClient.config.activeGhost == null, "active ghost cleared in the config");
			check(Designs.load(NAME) != null, "design file kept in config/vire/designs/");
		});
		step(1, "clean up", mc -> {
			cmd(mc, "fill " + (body.getX() - 4) + " " + body.getY() + " " + (body.getZ() - 4) + " " + (body.getX() + 6) + " " + (body.getY() + 3) + " "
					+ (body.getZ() + 4) + " minecraft:air");
			cmd(mc, "clear @s");
		});
		step(20, "report", SelfTest::report);
	}

	private static GhostLayer.Ghost ghost(BlockPos p) {
		for (GhostLayer.Ghost g : GhostLayer.ghosts()) {
			if (g.pos.equals(p)) {
				return g;
			}
		}
		return null;
	}

	private static int need(Map<String, int[]> bom, String item) {
		int[] v = bom.get("minecraft:" + item);
		return v == null ? 0 : v[0];
	}

	private static String text(Map<String, int[]> bom) {
		StringBuilder b = new StringBuilder();
		bom.forEach((k, v) -> b.append(k.replace("minecraft:", "")).append(' ').append(v[0]).append('/').append(v[1]).append(' '));
		return b.toString().trim();
	}

	private static void cmd(Minecraft mc, String c) {
		mc.player.connection.sendCommand(c);
	}

	/**
	 * Real survival placement: hold {@code item}, face {@code facing} (sets the block's facing) while the
	 * crosshair is on the top of the ground block under {@code target}, press use.
	 */
	private static void place(java.util.function.Supplier<BlockPos> target, Item item, Direction facing) {
		step(1, "hold item", mc -> {
			var inv = mc.player.getInventory();
			for (int i = 0; i < 9; i++) {
				if (inv.getItem(i).is(item)) {
					click(mc.options.keyHotbarSlots[i]);
					return;
				}
			}
			check(false, "no " + item + " in the hotbar");
		});
		step(1, "look", mc -> look(mc, target.get().below(), facing));
		waitFor("aimed", () -> Minecraft.getInstance().hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
				&& hit.getBlockPos().equals(target.get().below()) && hit.getDirection() == Direction.UP, 40);
		step(1, "use", mc -> click(mc.options.keyUse));
		step(6, "placed", mc -> VireClient.LOG.info("[selftest] placed {} at {}: {} (hit {}, yaw {}, holding {})", item, target.get(),
				mc.level.getBlockState(target.get()), mc.hitResult instanceof BlockHitResult h ? h.getBlockPos() + " " + h.getDirection() : mc.hitResult,
				mc.player.getYRot(), mc.player.getMainHandItem()));
	}

	/** Holds attack on {@code target} until it is gone (dirt takes about a second by hand). */
	private static void breakBlock(java.util.function.Supplier<BlockPos> target) {
		step(1, "look at block", mc -> {
			BlockPos p = target.get();
			// the middle of the block's shape (a repeater is only 2/16 high)
			var shape = mc.level.getBlockState(p).getShape(mc.level, p);
			lookAt(mc, shape.isEmpty() ? Vec3.atCenterOf(p) : shape.bounds().getCenter().add(p.getX(), p.getY(), p.getZ()));
		});
		waitFor("aimed", () -> Minecraft.getInstance().hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(target.get()), 40);
		step(1, "hold attack", mc -> set(mc.options.keyAttack, true));
		waitFor("broken", () -> Minecraft.getInstance().level.getBlockState(target.get()).isAir(), 200);
		step(1, "release attack", mc -> set(mc.options.keyAttack, false));
	}

	/** Looks at the top face of {@code ground}; looking east/north there sets the facing of what is placed. */
	private static void look(Minecraft mc, BlockPos ground, Direction facing) {
		lookAt(mc, new Vec3(ground.getX() + 0.5, ground.getY() + 0.98, ground.getZ() + 0.5));
	}

	private static void lookAt(Minecraft mc, Vec3 target) {
		Vec3 eye = mc.player.getEyePosition();
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		mc.player.setYRot(yaw);
		mc.player.setXRot(pitch);
		mc.player.yRotO = yaw;
		mc.player.xRotO = pitch;
	}
}
