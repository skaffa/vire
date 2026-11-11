package dev.vire.client.interact;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Block picker for the virtual hotbar (PLAN.md §4.2). A plain {@link Screen}, not the creative
 * inventory: it never sends a packet. Click a block to put it in the selected slot; hover a block and
 * press 1–9 to put it in that slot.
 */
public final class PaletteScreen extends Screen {
	private static final int CELL = 18;
	private static final int TOP = 40;
	private static final List<ItemStack> ALL = new ArrayList<>();

	private EditBox search;
	private final List<ItemStack> shown = new ArrayList<>();
	private int cols;
	private int rowsVisible;
	private int scroll;
	private int left;

	public PaletteScreen() {
		super(Component.literal("Vire blocks"));
	}

	private static List<ItemStack> all() {
		if (ALL.isEmpty()) {
			for (Item item : BuiltInRegistries.ITEM) {
				if (item instanceof BlockItem) {
					ALL.add(new ItemStack(item));
				}
			}
		}
		return ALL;
	}

	@Override
	protected void init() {
		cols = Math.max(1, Math.min(18, (width - 40) / CELL));
		left = (width - cols * CELL) / 2;
		rowsVisible = Math.max(1, (height - TOP - 50) / CELL);
		search = new EditBox(font, left, 16, cols * CELL, 18, Component.empty());
		search.setHint(Component.literal("Search blocks…").withStyle(ChatFormatting.DARK_GRAY));
		search.setResponder(s -> refilter());
		addRenderableWidget(search);
		// not focused at first: digits go to the hotbar, E closes; typing a letter starts the search
		refilter();
	}

	private void refilter() {
		String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
		shown.clear();
		for (ItemStack s : all()) {
			if (q.isEmpty() || s.getHoverName().getString().toLowerCase(Locale.ROOT).contains(q)
					|| BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().contains(q)) {
				shown.add(s);
			}
		}
		scroll = 0;
	}

	private int maxScroll() {
		int rows = (shown.size() + cols - 1) / cols;
		return Math.max(0, rows - rowsVisible);
	}

	/** Index into {@link #shown} under the mouse, or -1. */
	private int indexAt(double mx, double my) {
		if (mx < left || my < TOP) {
			return -1;
		}
		int c = (int) ((mx - left) / CELL);
		int r = (int) ((my - TOP) / CELL);
		if (c >= cols || r >= rowsVisible) {
			return -1;
		}
		int i = (r + scroll) * cols + c;
		return i < shown.size() ? i : -1;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractRenderState(g, mx, my, delta);
		g.centeredText(font, Component.literal("Click = selected slot · hover + 1–9 = that slot · type to search, Enter to stop"), width / 2, 4, 0xFFFFFFFF);
		int hovered = indexAt(mx, my);
		for (int r = 0; r < rowsVisible; r++) {
			for (int c = 0; c < cols; c++) {
				int i = (r + scroll) * cols + c;
				if (i >= shown.size()) {
					break;
				}
				int x = left + c * CELL;
				int y = TOP + r * CELL;
				g.fill(x, y, x + CELL - 1, y + CELL - 1, i == hovered ? 0x80FFFFFF : 0x40000000);
				g.item(shown.get(i), x + 1, y + 1);
			}
		}
		// the virtual hotbar along the bottom
		int hx = width / 2 - 9 * CELL / 2;
		int hy = height - 30;
		for (int i = 0; i < 9; i++) {
			int x = hx + i * CELL;
			g.fill(x, hy, x + CELL - 1, hy + CELL - 1, i == VirtualHotbar.selected() ? 0xC000C8FF : 0x80000000);
			g.item(VirtualHotbar.get(i), x + 1, hy + 1);
		}
		if (hovered >= 0) {
			g.centeredText(font, shown.get(hovered).getHoverName(), width / 2, height - 44, 0xFFFFFF80);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		int i = indexAt(e.x(), e.y());
		if (i >= 0 && e.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			VirtualHotbar.set(VirtualHotbar.selected(), shown.get(i));
			return true;
		}
		// clicking a hotbar slot selects it
		int hx = width / 2 - 9 * CELL / 2;
		int hy = height - 30;
		if (e.y() >= hy && e.y() < hy + CELL && e.x() >= hx && e.x() < hx + 9 * CELL) {
			VirtualHotbar.select((int) ((e.x() - hx) / CELL));
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(scrollY)));
		return true;
	}

	/** A digit just went to a hotbar slot; its character event must not reach the search box. */
	private boolean swallowChar;

	@Override
	public boolean keyPressed(KeyEvent e) {
		int k = e.key();
		swallowChar = false;
		if (k >= InputConstants.KEY_1 && k <= InputConstants.KEY_9 && minecraft != null) {
			double mx = minecraft.mouseHandler.getScaledXPos(minecraft.getWindow());
			double my = minecraft.mouseHandler.getScaledYPos(minecraft.getWindow());
			int i = indexAt(mx, my);
			if (i >= 0) {
				VirtualHotbar.set(k - InputConstants.KEY_1, shown.get(i));
				swallowChar = true;
				return true;
			}
			if (!search.isFocused()) {
				VirtualHotbar.select(k - InputConstants.KEY_1);
				swallowChar = true;
				return true;
			}
		}
		if (minecraft != null && minecraft.options.keyInventory.matches(e) && !search.isFocused()) {
			onClose();
			return true;
		}
		if (k == InputConstants.KEY_RETURN && search.isFocused()) {
			setFocused(null); // Enter ends the search; digits and E work again
			return true;
		}
		if (!search.isFocused() && k >= InputConstants.KEY_A && k <= InputConstants.KEY_Z) {
			setFocused(search); // the character event that follows lands in the box
		}
		return super.keyPressed(e);
	}

	@Override
	public boolean charTyped(net.minecraft.client.input.CharacterEvent e) {
		if (swallowChar) {
			swallowChar = false;
			return true;
		}
		return super.charTyped(e);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
