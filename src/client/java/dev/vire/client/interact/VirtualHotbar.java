package dev.vire.client.interact;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Vire Mode's own 9 slots (PLAN.md §4.2). The survival inventory is never touched, so no
 * carried-item or creative-slot packet is ever needed.
 */
public final class VirtualHotbar {
	private static final ItemStack[] SLOTS = new ItemStack[9];
	private static int selected;

	static {
		ItemStack[] defaults = {
				new ItemStack(Items.REDSTONE), new ItemStack(Items.REPEATER), new ItemStack(Items.COMPARATOR),
				new ItemStack(Items.REDSTONE_TORCH), new ItemStack(Items.STICKY_PISTON), new ItemStack(Items.OBSERVER),
				new ItemStack(Items.SMOOTH_STONE), new ItemStack(Items.LEVER), new ItemStack(Items.REDSTONE_LAMP)};
		System.arraycopy(defaults, 0, SLOTS, 0, 9);
	}

	private VirtualHotbar() {
	}

	public static int selected() {
		return selected;
	}

	public static void select(int slot) {
		if (slot >= 0 && slot < 9) {
			selected = slot;
		}
	}

	/** Moves the selection by {@code delta} slots, wrapping around. */
	public static void scroll(int delta) {
		selected = Math.floorMod(selected + delta, 9);
	}

	public static ItemStack get(int slot) {
		return SLOTS[slot];
	}

	public static ItemStack selectedStack() {
		return SLOTS[selected];
	}

	public static void set(int slot, ItemStack stack) {
		SLOTS[slot] = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
	}

	/** Pick block: select the slot holding {@code stack}'s item, or put it in the selected slot. */
	public static void pick(ItemStack stack) {
		if (stack.isEmpty()) {
			return;
		}
		for (int i = 0; i < 9; i++) {
			if (SLOTS[i].is(stack.getItem())) {
				selected = i;
				return;
			}
		}
		set(selected, stack);
	}
}
