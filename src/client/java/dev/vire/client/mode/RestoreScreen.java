package dev.vire.client.mode;

import dev.vire.core.design.SessionStore;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Asked on join when the last Vire session in this world did not end normally (crash, kick, world
 * change): continue it now, keep it for the next V there, or throw it away.
 */
public final class RestoreScreen extends Screen {
	private final SessionStore.Saved saved;

	public RestoreScreen(SessionStore.Saved saved) {
		super(Component.literal("Restore Vire session"));
		this.saved = saved;
	}

	@Override
	protected void init() {
		int w = 240;
		int x = width / 2 - w / 2;
		int y = height / 2 + 4;
		int bw = (w - 12) / 3;
		addRenderableWidget(Button.builder(Component.literal("Restore"), b -> restore()).bounds(x, y, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Later"), b -> later()).bounds(x + bw + 6, y, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Discard"), b -> discard()).bounds(x + 2 * (bw + 6), y, bw, 20).build());
	}

	private void restore() {
		Sessions.markClean(saved);
		onClose();
		if (minecraft == null || minecraft.player == null) {
			return;
		}
		var p = minecraft.player;
		if (saved.box().contains(p.getBlockX(), p.getBlockY(), p.getBlockZ())) {
			VireMode.enter(minecraft); // continues the saved session (it is inside its region)
		} else {
			Sessions.tell(minecraft, "Your Vire session is around " + Sessions.where(saved.box())
					+ ". Press V inside that region to continue it.");
		}
	}

	private void later() {
		Sessions.markClean(saved);
		onClose();
		if (minecraft != null) {
			Sessions.tell(minecraft, "Kept. Press V around " + Sessions.where(saved.box()) + " to continue it; /vire discard drops it.");
		}
	}

	private void discard() {
		Sessions.delete(saved.world());
		onClose();
		if (minecraft != null) {
			Sessions.tell(minecraft, "Vire session discarded.");
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractRenderState(g, mx, my, delta);
		long min = Math.max(0, (System.currentTimeMillis() - saved.savedAt()) / 60_000);
		String ago = min < 1 ? "just now" : min < 120 ? min + " min ago" : (min / 60) + " h ago";
		g.centeredText(font, Component.literal("Your last Vire session here did not end normally."), width / 2, height / 2 - 40, 0xFF66E0FF);
		g.centeredText(font, Component.literal(saved.edits().size() + " changed blocks around " + Sessions.where(saved.box()) + ", saved " + ago + "."),
				width / 2, height / 2 - 26, 0xFFFFFFFF);
		g.centeredText(font, Component.literal("Restore it?"), width / 2, height / 2 - 12, 0xFFB0C0C8);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
