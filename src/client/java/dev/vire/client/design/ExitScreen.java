package dev.vire.client.design;

import dev.vire.core.design.Design;
import dev.vire.core.design.DesignStore;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shown after leaving Vire Mode with a design (PLAN.md §12): name it, then keep it as a ghost to build
 * from, only save it, or discard it. Closing with Escape saves it as {@code _autosave}, so nothing is
 * lost by accident.
 */
public final class ExitScreen extends Screen {
	private final Design design;
	private EditBox name;
	private boolean decided;

	public ExitScreen(Design design) {
		super(Component.literal("Vire design"));
		this.design = design;
	}

	@Override
	protected void init() {
		int w = 220;
		int x = width / 2 - w / 2;
		int y = height / 2 - 50;
		name = new EditBox(font, x, y, w, 20, Component.literal("Name"));
		name.setMaxLength(48);
		name.setValue(name.getValue().isEmpty() ? Designs.defaultName() : name.getValue());
		addRenderableWidget(name);
		setInitialFocus(name);
		int bw = (w - 6) / 2;
		addRenderableWidget(Button.builder(Component.literal("Keep as ghost"), b -> decide(Choice.GHOST)).bounds(x, y + 28, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Save only"), b -> decide(Choice.SAVE)).bounds(x + bw + 6, y + 28, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Export .litematic"), b -> decide(Choice.EXPORT)).bounds(x, y + 52, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Discard"), b -> decide(Choice.DISCARD)).bounds(x + bw + 6, y + 52, bw, 20).build());
	}

	public enum Choice { GHOST, SAVE, EXPORT, DISCARD, AUTOSAVE }

	/** Applies a choice; public for the self-test. */
	public static Design apply(Design design, String name, Choice choice) {
		return switch (choice) {
			case DISCARD -> null;
			case AUTOSAVE -> Designs.save(design, DesignStore.AUTOSAVE);
			case SAVE -> Designs.save(design, name);
			case EXPORT -> {
				Design saved = Designs.save(design, name);
				if (saved != null) {
					LitematicExport.exportAndTell(net.minecraft.client.Minecraft.getInstance(), saved);
				}
				yield saved;
			}
			case GHOST -> {
				Design saved = Designs.save(design, name);
				if (saved != null) {
					GhostLayer.show(saved);
				}
				yield saved;
			}
		};
	}

	private void decide(Choice c) {
		decided = true;
		Design saved = apply(design, name.getValue(), c);
		if (minecraft != null && minecraft.player != null) {
			String msg = switch (c) {
				case GHOST -> saved == null ? "Could not save the design (see log)"
						: "Design " + saved.name() + " saved; " + saved.entries().size() + " blocks to build. B shows the materials.";
				case SAVE -> saved == null ? "Could not save the design (see log)" : "Design " + saved.name() + " saved.";
				case EXPORT -> saved == null ? "Could not save the design (see log)" : "Design " + saved.name() + " saved.";
				default -> "Design discarded.";
			};
			minecraft.player.sendSystemMessage(Component.literal("[Vire] ").withStyle(ChatFormatting.DARK_AQUA)
					.append(Component.literal(msg).withStyle(ChatFormatting.AQUA)));
		}
		onClose();
	}

	@Override
	public void removed() {
		if (!decided) {
			decided = true;
			Design saved = apply(design, DesignStore.AUTOSAVE, Choice.AUTOSAVE);
			if (minecraft != null && minecraft.player != null && saved != null) {
				minecraft.player.sendSystemMessage(Component.literal("[Vire] Design kept as _autosave (/vire ghost _autosave shows it).")
						.withStyle(ChatFormatting.AQUA));
			}
		}
		super.removed();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractRenderState(g, mx, my, delta);
		g.centeredText(font, Component.literal("Vire design: " + design.entries().size() + " blocks differ from the world"), width / 2,
				height / 2 - 72, 0xFF66E0FF);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
