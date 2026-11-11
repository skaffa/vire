package dev.vire.client.overlay;

import dev.vire.client.interact.VirtualHotbar;
import dev.vire.client.mode.PacketGuard;
import dev.vire.client.mode.VireMode;
import dev.vire.client.mode.VireSession;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

/** Vire Mode HUD (PLAN.md §12): frame, status line and the virtual hotbar in place of the real one. */
public final class VireHud {
	private static final int FRAME = 0x6000C8FF;
	private static final int SLOT = 20;

	private VireHud() {
	}

	/** Drawn after the crosshair: frame and status line. */
	public static void frame(GuiGraphicsExtractor g, DeltaTracker delta) {
		VireSession s = VireMode.session();
		Minecraft mc = Minecraft.getInstance();
		if (s == null || mc.gui.hud.isHidden()) {
			return;
		}
		int w = g.guiWidth();
		int h = g.guiHeight();
		g.fill(0, 0, w, 2, FRAME);
		g.fill(0, h - 2, w, h, FRAME);
		g.fill(0, 2, 2, h - 2, FRAME);
		g.fill(w - 2, 2, w, h - 2, FRAME);

		String clock = !s.loaded ? "loading…" : s.frozen ? "PAUSED" : "RUNNING " + (int) s.tickRate + " TPS";
		String status = "VIRE MODE  " + clock + "  tick " + s.sandboxTick + "  " + s.box.sizeX() + "×" + s.box.sizeY() + "×" + s.box.sizeZ()
				+ "  edits " + s.history.undoSize()
				+ "  held updates " + s.shadow.interceptedUpdates()
				+ "  blocked packets " + PacketGuard.dropped();
		int tw = mc.font.width(status);
		g.fill(w / 2 - tw / 2 - 4, 4, w / 2 + tw / 2 + 4, 16, 0xA0000000);
		g.text(mc.font, status, w / 2 - tw / 2, 6, PacketGuard.dropped() > 0 ? 0xFFFF5555 : 0xFF66E0FF);
		String help = "V leave · K run · . , step · [ ] speed · R reset · E blocks · Ctrl+Z/Y";
		g.text(mc.font, help, w / 2 - mc.font.width(help) / 2, 19, 0xB0FFFFFF);
	}

	/** Replaces the vanilla hotbar while Vire Mode is on. */
	public static void hotbar(GuiGraphicsExtractor g) {
		int w = g.guiWidth();
		int h = g.guiHeight();
		int x0 = w / 2 - 9 * SLOT / 2;
		int y0 = h - SLOT - 2;
		g.fill(x0 - 1, y0 - 1, x0 + 9 * SLOT + 1, y0 + SLOT + 1, 0x90000000);
		for (int i = 0; i < 9; i++) {
			int x = x0 + i * SLOT;
			if (i == VirtualHotbar.selected()) {
				g.outline(x, y0, SLOT, SLOT, 0xFF00C8FF);
			}
			ItemStack stack = VirtualHotbar.get(i);
			g.item(stack, x + 2, y0 + 2);
		}
		ItemStack sel = VirtualHotbar.selectedStack();
		if (!sel.isEmpty()) {
			Minecraft mc = Minecraft.getInstance();
			String name = sel.getHoverName().getString();
			g.text(mc.font, name, w / 2 - mc.font.width(name) / 2, y0 - 12, 0xFFFFFFFF);
		}
	}
}
