package dev.vire.client.overlay;

import dev.vire.client.VireClient;
import dev.vire.client.interact.VireWorld;
import dev.vire.client.interact.VirtualHotbar;
import dev.vire.client.mode.PacketGuard;
import dev.vire.client.mode.VireMode;
import dev.vire.client.mode.VireSession;
import dev.vire.core.probe.DelayMeter;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Vire Mode HUD (PLAN.md §12): a frame, a values panel (top left), a keys panel (right, H hides it),
 * the Vire crosshair and the virtual hotbar in place of the real one.
 */
public final class VireHud {
	private static final int FRAME = 0x6000C8FF;
	private static final int SLOT = 20;
	private static final int PANEL_BG = 0xC0101820;
	private static final int ACCENT = 0xFF00C8FF;
	private static final int LABEL = 0xFF8CA0AC;
	private static final int VALUE = 0xFFFFFFFF;
	private static final int KEY = 0xFF66E0FF;
	private static final int PAD = 5;
	private static final int ROW = 11;

	/** Crosshair colours: what right click would do on the targeted block. */
	private static final int CROSS_PLACE = 0xFFFFFFFF;
	private static final int CROSS_USE = 0xFF60FF80;
	private static final int CROSS_OUTSIDE = 0xFFFF5555;
	private static final int CROSS_NONE = 0x90FFFFFF;

	private VireHud() {
	}

	/** One row of a panel: label and value, or a key and its action. */
	private record Row(String left, String right, int rightColor) {
	}

	/** Drawn after the crosshair: frame and both panels. */
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

		values(g, mc, s);
		keys(g, mc, w, h);
	}

	// ---- values panel ---------------------------------------------------------------------------------

	private static void values(GuiGraphicsExtractor g, Minecraft mc, VireSession s) {
		Font font = mc.font;
		String clock = !s.loaded ? "LOADING…" : s.frozen ? "⏸ PAUSED" : "▶ RUNNING";
		int clockColor = !s.loaded ? 0xFFB0B0B0 : s.frozen ? 0xFFFFD040 : 0xFF60FF80;

		List<Row> rows = new ArrayList<>();
		rows.add(new Row("Speed", fmtRate(s.tickRate) + " TPS", VALUE));
		rows.add(new Row("Tick", Long.toString(s.sandboxTick), VALUE));
		rows.add(new Row("Region", s.box.sizeX() + "×" + s.box.sizeY() + "×" + s.box.sizeZ(), VALUE));
		rows.add(new Row("Edits", s.history.undoSize() + (s.history.redoSize() > 0 ? "  (redo " + s.history.redoSize() + ")" : ""), VALUE));

		DelayMeter m = s.meter;
		if (s.markIn != null || s.markOut != null) {
			if (s.markIn != null && s.markOut != null) {
				rows.add(new Row("Delay", m.delay() < 0 ? "–" : DelayMeter.ticks(m.delay()) + (m.delayRising() ? " ↑" : " ↓"), SignalOverlay.IN_COLOR));
			} else {
				rows.add(new Row("Delay", s.markIn != null ? "mark output (" + k(VireClient.markOutKey()) + ")"
						: "mark input (" + k(VireClient.markInKey()) + ")", LABEL));
			}
			if (m.period() > 0) {
				rows.add(new Row("Period", m.period() + " gt · " + String.format(Locale.ROOT, "%.2f Hz", m.frequency()), SignalOverlay.OUT_COLOR));
				if (m.onTime() > 0) {
					rows.add(new Row("On time", m.onTime() + " gt", SignalOverlay.OUT_COLOR));
				}
			}
		}

		Row[] target = target(mc, s);
		if (target != null) {
			rows.add(target[0]);
			if (target[1] != null) {
				rows.add(target[1]);
			}
		}

		if (mc.player != null) {
			float hp = mc.player.getHealth();
			float max = mc.player.getMaxHealth();
			rows.add(new Row("Body", String.format(Locale.ROOT, "♥ %.0f/%.0f", hp, max), hp < max * 0.5f ? 0xFFFF5555 : VALUE));
		}
		if (PacketGuard.dropped() > 0) {
			rows.add(new Row("Blocked", PacketGuard.dropped() + " packets", 0xFFFF5555));
		}

		int labelW = 0;
		int valueW = font.width(clock);
		for (Row r : rows) {
			labelW = Math.max(labelW, font.width(r.left()));
			valueW = Math.max(valueW, font.width(r.right()));
		}
		int innerW = Math.max(110, labelW + 10 + valueW);
		int x = 6;
		int y = 6;
		int headerH = 14;
		int height = headerH + 3 + rows.size() * ROW + PAD;
		String last = s.lastAction != null && System.currentTimeMillis() - s.lastActionAt < 3000 ? s.lastAction : null;
		if (last != null) {
			height += ROW + 2;
			innerW = Math.max(innerW, font.width(last));
		}
		panel(g, x, y, innerW + 2 * PAD, height);
		g.text(font, "VIRE", x + PAD, y + 4, ACCENT);
		g.text(font, clock, x + PAD + innerW - font.width(clock), y + 4, clockColor);
		g.fill(x + PAD, y + headerH, x + PAD + innerW, y + headerH + 1, 0x4000C8FF);
		int ry = y + headerH + 4;
		for (Row r : rows) {
			g.text(font, r.left(), x + PAD, ry, LABEL);
			g.text(font, r.right(), x + PAD + innerW - font.width(r.right()), ry, r.rightColor());
			ry += ROW;
		}
		if (last != null) {
			g.fill(x + PAD, ry, x + PAD + innerW, ry + 1, 0x4000C8FF);
			g.text(font, last, x + PAD, ry + 3, 0xFFFFE080);
		}
	}

	/** Name and details of the targeted block, or null. */
	private static Row[] target(Minecraft mc, VireSession s) {
		if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			return null;
		}
		BlockPos p = hit.getBlockPos();
		BlockState st = s.level.getBlockState(p);
		String name = st.getBlock().getName().getString();
		Row first = new Row("Target", name, VALUE);
		if (!s.box.contains(p.getX(), p.getY(), p.getZ())) {
			return new Row[] {first, new Row("", "outside region", 0xFFFF5555)};
		}
		String detail;
		if (st.getBlock() instanceof RepeaterBlock) {
			detail = "delay " + st.getValue(RepeaterBlock.DELAY) + " · out " + Signals.level(s.level, p, st, s.comparators);
		} else if (st.getBlock() instanceof ComparatorBlock) {
			detail = st.getValue(ComparatorBlock.MODE).getSerializedName() + " · out " + s.comparators.getOrDefault(Signals.key(p), 0);
		} else {
			detail = "signal " + Signals.level(s.level, p, st, s.comparators);
		}
		VireWorld.Use use = VireWorld.useOf(st);
		if (use == VireWorld.Use.INPUT) {
			detail += " · " + VireClient.keyName(mc.options.keyUse) + " flip";
		} else if (use == VireWorld.Use.SETTING) {
			detail += " · " + VireClient.keyName(mc.options.keyUse) + " change";
		}
		return new Row[] {first, new Row("", detail, 0xFFC8D8E0)};
	}

	// ---- keys panel -----------------------------------------------------------------------------------

	private static void keys(GuiGraphicsExtractor g, Minecraft mc, int w, int h) {
		Font font = mc.font;
		if (!VireClient.config.showKeys) {
			String tab = k(VireClient.helpKey()) + " keys";
			int tw = font.width(tab);
			panel(g, w - tw - 2 * PAD - 6, 6, tw + 2 * PAD, 14);
			g.text(font, tab, w - tw - PAD - 6, 9, LABEL);
			return;
		}
		List<Row> rows = new ArrayList<>();
		rows.add(new Row(k(VireClient.pauseKey()), "run / pause", 0));
		rows.add(new Row(k(VireClient.stepKey()) + " " + k(VireClient.stepManyKey()), "step 1 / 10", 0));
		rows.add(new Row(k(VireClient.slowerKey()) + " " + k(VireClient.fasterKey()), "slower / faster", 0));
		rows.add(new Row(k(VireClient.resetKey()), "reset circuit", 0));
		rows.add(null);
		String use = k(mc.options.keyUse);
		rows.add(new Row(use, "place / flip", 0));
		rows.add(new Row("Ctrl+" + use, "place on lever…", 0));
		rows.add(new Row(k(mc.options.keyAttack), "break", 0));
		rows.add(new Row(k(mc.options.keyPickItem), "pick block", 0));
		rows.add(new Row(k(mc.options.keyInventory), "blocks", 0));
		rows.add(new Row("Ctrl+Z / Y", "undo / redo", 0));
		rows.add(null);
		rows.add(new Row(k(VireClient.markInKey()) + " " + k(VireClient.markOutKey()), "mark in / out", 0));
		rows.add(new Row(k(VireClient.arrowsKey()), "arrows", 0));
		rows.add(new Row(k(VireClient.helpKey()), "hide keys", 0));
		rows.add(new Row(k(VireClient.toggleKey()), "leave", 0));

		int keyW = 0;
		int actW = 0;
		int n = 0;
		int gaps = 0;
		for (Row r : rows) {
			if (r == null) {
				gaps++;
				continue;
			}
			keyW = Math.max(keyW, font.width(r.left()));
			actW = Math.max(actW, font.width(r.right()));
			n++;
		}
		int innerW = keyW + 8 + actW;
		int height = 14 + 3 + n * ROW + gaps * 4 + PAD;
		int x = w - innerW - 2 * PAD - 6;
		int y = Math.max(6, (h - height) / 2 - 20);
		panel(g, x, y, innerW + 2 * PAD, height);
		g.text(font, "KEYS", x + PAD, y + 4, ACCENT);
		g.fill(x + PAD, y + 14, x + PAD + innerW, y + 15, 0x4000C8FF);
		int ry = y + 18;
		for (Row r : rows) {
			if (r == null) {
				ry += 4;
				continue;
			}
			g.text(font, r.left(), x + PAD + keyW - font.width(r.left()), ry, KEY);
			g.text(font, r.right(), x + PAD + keyW + 8, ry, 0xFFE0E8EC);
			ry += ROW;
		}
	}

	private static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, PANEL_BG);
		g.fill(x, y, x + 1, y + h, ACCENT);
	}

	private static String fmtRate(float r) {
		return r == (int) r ? Integer.toString((int) r) : String.format(Locale.ROOT, "%.1f", r);
	}

	private static String k(KeyMapping m) {
		return VireClient.keyName(m);
	}

	// ---- crosshair --------------------------------------------------------------------------------------

	/** Replaces the vanilla crosshair in Vire Mode; its colour says what right click does. */
	public static void crosshair(GuiGraphicsExtractor g) {
		VireSession s = VireMode.session();
		Minecraft mc = Minecraft.getInstance();
		if (s == null || mc.gui.hud.isHidden()) {
			return;
		}
		int color = CROSS_NONE;
		if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos p = hit.getBlockPos();
			if (!s.box.contains(p.getX(), p.getY(), p.getZ())) {
				color = CROSS_OUTSIDE;
			} else if (VireWorld.useOf(s.level.getBlockState(p)) != null && !mc.hasControlDown()) {
				color = CROSS_USE;
			} else {
				color = CROSS_PLACE;
			}
		}
		int cx = g.guiWidth() / 2;
		int cy = g.guiHeight() / 2;
		int shadow = 0x80000000;
		// arms with a gap, then a centre dot; a dark outline keeps it visible on bright blocks
		int[][] arms = {{cx - 6, cy, cx - 2, cy + 1}, {cx + 2, cy, cx + 6, cy + 1}, {cx, cy - 6, cx + 1, cy - 2}, {cx, cy + 2, cx + 1, cy + 6}};
		for (int[] a : arms) {
			g.fill(a[0] - 1, a[1] - 1, a[2] + 1, a[3] + 1, shadow);
		}
		g.fill(cx - 1, cy - 1, cx + 2, cy + 2, shadow);
		for (int[] a : arms) {
			g.fill(a[0], a[1], a[2], a[3], color);
		}
		g.fill(cx, cy, cx + 1, cy + 1, color);
	}

	// ---- hotbar -----------------------------------------------------------------------------------------

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
			// where vanilla puts the held item name: above hearts and armour (the XP bar is hidden in Vire Mode)
			int y = h - 59;
			if (mc.gameMode != null && !mc.gameMode.canHurtPlayer()) {
				y += 14;
			}
			int nw = mc.font.width(name);
			g.fill(w / 2 - nw / 2 - 3, y - 2, w / 2 + nw / 2 + 3, y + 10, 0x80000000);
			g.text(mc.font, name, w / 2 - nw / 2, y, 0xFFFFFFFF);
		}
	}
}
