package dev.vire.client.interact;

import com.mojang.blaze3d.platform.InputConstants;

import dev.vire.client.mode.VireMode;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/**
 * Layer 1 of input isolation (PLAN.md §4.1). Runs at the start of {@code Minecraft.handleKeybinds}
 * while Vire Mode is on and consumes every key click that would make vanilla act on the real world.
 * Vanilla then finds nothing to do for those keys; held keys are covered by the cancelled
 * {@code startUseItem}/{@code continueAttack} in {@code MinecraftMixin}.
 */
public final class VireInput {
	/** Ticks between repeated actions while a mouse button is held, like creative mode. */
	private static final int REPEAT = 4;
	private static int useDelay;
	private static int attackDelay;
	private static boolean undoWasDown;
	private static boolean redoWasDown;

	private VireInput() {
	}

	public static void handleKeys(Minecraft mc) {
		Options o = mc.options;
		for (int i = 0; i < 9; i++) {
			while (o.keyHotbarSlots[i].consumeClick()) {
				VirtualHotbar.select(i);
			}
		}
		while (o.keyInventory.consumeClick()) {
			mc.gui.setScreen(new PaletteScreen());
		}
		drain(o.keySwapOffhand);
		drain(o.keyDrop);
		drain(o.keyQuickActions);

		boolean clickedAttack = false;
		while (o.keyAttack.consumeClick()) {
			VireWorld.breakTarget(mc);
			clickedAttack = true;
		}
		boolean clickedUse = false;
		while (o.keyUse.consumeClick()) {
			VireWorld.place(mc, mc.hasControlDown());
			clickedUse = true;
		}
		while (o.keyPickItem.consumeClick()) {
			VireWorld.pick(mc);
		}

		boolean screenOpen = mc.gui.screen() != null;
		if (clickedUse) {
			useDelay = REPEAT;
		} else if (!screenOpen && o.keyUse.isDown()) {
			if (--useDelay <= 0) {
				VireWorld.place(mc, mc.hasControlDown());
				useDelay = REPEAT;
			}
		} else {
			useDelay = 0;
		}
		if (clickedAttack) {
			attackDelay = REPEAT + 1;
		} else if (!screenOpen && o.keyAttack.isDown()) {
			if (--attackDelay <= 0) {
				VireWorld.breakTarget(mc);
				attackDelay = REPEAT + 1;
			}
		} else {
			attackDelay = 0;
		}

		// Ctrl+Z undo, Ctrl+Y or Ctrl+Shift+Z redo (raw keys: no conflict with key mappings)
		boolean ctrl = mc.hasControlDown();
		boolean shift = mc.hasShiftDown();
		boolean z = !screenOpen && ctrl && InputConstants.isKeyDown(InputConstants.KEY_Z);
		boolean y = !screenOpen && ctrl && InputConstants.isKeyDown(InputConstants.KEY_Y);
		boolean undoDown = z && !shift;
		boolean redoDown = y || (z && shift);
		if (undoDown && !undoWasDown) {
			VireWorld.undo(mc);
		}
		if (redoDown && !redoWasDown) {
			VireWorld.redo(mc);
		}
		undoWasDown = undoDown;
		redoWasDown = redoDown;
	}

	private static void drain(KeyMapping key) {
		while (key.consumeClick()) {
			// swallowed in Vire Mode
		}
	}

	/** Scroll wheel over the hotbar ({@code MouseHandlerMixin}). */
	public static void scroll(int oldSlot, int newSlot) {
		int delta = Math.floorMod(newSlot - oldSlot + 4, 9) - 4;
		VirtualHotbar.scroll(delta);
	}

	public static boolean active() {
		return VireMode.active();
	}
}
