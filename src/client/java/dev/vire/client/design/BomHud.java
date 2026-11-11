package dev.vire.client.design;

import dev.vire.client.VireClient;
import dev.vire.client.mode.VireMode;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

/**
 * Bill of materials panel (PLAN.md §9), toggled with {@code B}: one line per item still needed for the
 * ghost, with what the inventory holds. Green = enough, red = missing. Inventory only, no chests.
 */
public final class BomHud {
	private static final int ROW = 18;
	private static final int SHOWN = 12;

	private BomHud() {
	}

	public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!VireClient.config.showBom || GhostLayer.design() == null || VireMode.active() || mc.gui.hud.isHidden()
				|| mc.player == null || !GhostLayer.design().world().equals(Designs.worldId(mc))) {
			return;
		}
		Map<String, int[]> bom = GhostLayer.bom();
		int x = 4;
		int y = 30;
		String title = GhostLayer.design().name() + ": " + GhostLayer.remaining() + " left";
		int w = Math.max(130, mc.font.width(title) + 8);
		int rows = Math.min(SHOWN, bom.size());
		g.fill(x - 2, y - 2, x + w, y + 12 + rows * ROW + (bom.size() > SHOWN ? 10 : 0), 0x90000000);
		g.text(mc.font, title, x + 2, y, 0xFF66E0FF);
		y += 12;
		int i = 0;
		for (Map.Entry<String, int[]> e : bom.entrySet()) {
			if (i++ >= SHOWN) {
				g.text(mc.font, "+" + (bom.size() - SHOWN) + " more (/vire bom)", x + 2, y, 0xFFB0B0B0);
				break;
			}
			Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(e.getKey()));
			int need = e.getValue()[0];
			int got = e.getValue()[1];
			g.item(new ItemStack(item), x, y);
			g.text(mc.font, got + " / " + need, x + 20, y + 5, got >= need ? 0xFF60FF80 : 0xFFFF6060);
			y += ROW;
		}
	}
}
