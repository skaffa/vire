package dev.vire.client.overlay;

import dev.vire.client.VireClient;
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

		// control bar (PLAN.md §8): clock first, then the region numbers
		String clock = !s.loaded ? "loading…" : s.frozen ? "⏸ PAUSED" : "▶ RUNNING";
		int clockColor = !s.loaded ? 0xFFB0B0B0 : s.frozen ? 0xFFFFD040 : 0xFF60FF80;
		String rest = "  " + fmtRate(s.tickRate) + " TPS  tick " + s.sandboxTick + "   |   " + s.box.sizeX() + "×" + s.box.sizeY() + "×"
				+ s.box.sizeZ() + "  edits " + s.history.undoSize() + "  held " + s.shadow.interceptedUpdates()
				+ "  blocked " + PacketGuard.dropped();
		int cw = mc.font.width("VIRE  " + clock);
		int tw = cw + mc.font.width(rest);
		int x = w / 2 - tw / 2;
		g.fill(x - 4, 4, x + tw + 4, 16, 0xA0000000);
		g.text(mc.font, "VIRE  ", x, 6, 0xFF66E0FF);
		g.text(mc.font, clock, x + mc.font.width("VIRE  "), 6, clockColor);
		g.text(mc.font, rest, x + cw, 6, PacketGuard.dropped() > 0 ? 0xFFFF5555 : 0xFFD0F4FF);
		int y = 19;
		String probe = Probe.hudLine(s);
		if (probe != null) {
			int pw = mc.font.width(probe);
			g.fill(w / 2 - pw / 2 - 4, y - 2, w / 2 + pw / 2 + 4, y + 10, 0xA0000000);
			g.text(mc.font, probe, w / 2 - pw / 2, y, s.markIn != null ? SignalOverlay.IN_COLOR : SignalOverlay.OUT_COLOR);
			y += 13;
		}
		for (String help : help()) {
			g.text(mc.font, help, w / 2 - mc.font.width(help) / 2, y, 0xB0FFFFFF);
			y += 10;
		}
	}

	private static String fmtRate(float r) {
		return r == (int) r ? Integer.toString((int) r) : String.format(java.util.Locale.ROOT, "%.1f", r);
	}

	/** Two help lines (clock, then tools) from the keys as bound now; one line does not fit at GUI scale 3. */
	private static String[] help() {
		return new String[] {
				k(VireClient.pauseKey()) + " run/pause · " + k(VireClient.stepKey()) + " step · " + k(VireClient.stepManyKey()) + " step 10 · "
						+ k(VireClient.slowerKey()) + " " + k(VireClient.fasterKey()) + " speed · " + k(VireClient.resetKey()) + " reset",
				k(VireClient.markInKey()) + " " + k(VireClient.markOutKey()) + " mark in/out · " + k(VireClient.arrowsKey()) + " arrows · E blocks · Ctrl+Z/Y undo · "
						+ k(VireClient.toggleKey()) + " leave"};
	}

	private static String k(net.minecraft.client.KeyMapping m) {
		return VireClient.keyName(m);
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
