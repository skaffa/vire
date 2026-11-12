package dev.vire.client;

import dev.vire.core.config.VireConfig;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;

/**
 * Vire settings (Mod Menu → Vire, or {@code /vire config}). Plain vanilla widgets, so Vire needs no
 * config library. Changes apply at once and are saved to {@code config/vire/config.json} on close.
 */
public final class ConfigScreen extends Screen {
	private static final int W = 150;
	private static final int H = 20;
	private static final int GAP = 4;

	private final Screen parent;

	public ConfigScreen(Screen parent) {
		super(Component.literal("Vire settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		VireConfig c = VireClient.config;
		List<AbstractWidget> w = new ArrayList<>();

		// Vire Mode
		w.add(intSlider("Region size", 16, 64, 1, () -> c.regionSize, v -> c.regionSize = v, v -> v + " blocks",
				"Size of the cube copied around you when you enter Vire Mode. Used the next time you enter."));
		w.add(slider("Camera speed", 0.25, 5.0, 0.25, () -> c.cameraSpeed, v -> c.cameraSpeed = v,
				v -> String.format(Locale.ROOT, "%.2f×", v), "Flying speed of the free camera (Ctrl flies faster)."));
		w.add(intSlider("Reach", 8, 128, 4, () -> c.reach, v -> c.reach = v, v -> v + " blocks",
				"How far away you can place, break and use blocks in Vire Mode."));
		w.add(toggle("Leave when hurt", () -> c.exitOnDamage, v -> c.exitOnDamage = v,
				"End Vire Mode as soon as your real body takes damage (your design is autosaved). Dying always ends it."));
		w.add(toggle("Keep sandbox warm", () -> c.keepWarm, v -> c.keepWarm = v,
				"Start the private sandbox server when you join a world (about 10 s, ~30 MB), so V is instant. "
						+ "Off: it starts when you press V and stops when you leave."));
		w.add(intSlider("Random tick speed", 0, 20, 1, () -> c.randomTickSpeed, v -> c.randomTickSpeed = v, Integer::toString,
				"Random tick speed in the sandbox (vanilla is 3). Used the next time the sandbox starts."));

		// overlays
		w.add(toggle("Flow arrows", () -> c.showArrows, v -> c.showArrows = v,
				"Arrows on repeaters, comparators, observers, hoppers, droppers and pistons (key J)."));
		w.add(toggle("Keys panel", () -> c.showKeys, v -> c.showKeys = v, "The list of keys on the right in Vire Mode (key H)."));
		w.add(intSlider("Label range", 4, 64, 2, () -> c.labelRange, v -> c.labelRange = v, v -> v + " blocks",
				"Signal labels are shown within this distance of the camera."));
		w.add(intSlider("Max labels", 20, 1000, 20, () -> c.maxLabels, v -> c.maxLabels = v, Integer::toString,
				"At most this many signal labels, nearest first. Lower it if many labels cost frames."));

		// ghost and export
		w.add(toggle("Materials panel", () -> c.showBom, v -> c.showBom = v, "The materials list next to a ghost (key B)."));
		w.add(intSlider("Ghost range", 8, 256, 8, () -> c.ghostRange, v -> c.ghostRange = v, v -> v + " blocks",
				"Ghost blocks are drawn within this distance of you."));
		w.add(intSlider("Max ghost blocks", 100, 10000, 100, () -> c.maxGhostsDrawn, v -> c.maxGhostsDrawn = v, Integer::toString,
				"At most this many ghost blocks are drawn, nearest first."));
		w.add(toggle("Place in Litematica", () -> c.autoLoadLitematica, v -> c.autoLoadLitematica = v,
				"After an export, load the .litematic in Litematica and place it at its real position (when Litematica is installed)."));

		int rows = (w.size() + 1) / 2;
		int top = Math.max(28, (height - rows * (H + GAP) - 30) / 2);
		int left = width / 2 - W - GAP / 2;
		for (int i = 0; i < w.size(); i++) {
			AbstractWidget widget = w.get(i);
			widget.setPosition(left + (i % 2) * (W + GAP), top + (i / 2) * (H + GAP));
			addRenderableWidget(widget);
		}
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
				.bounds(width / 2 - 100, top + rows * (H + GAP) + 8, 200, H).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractRenderState(g, mx, my, delta);
		g.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		VireClient.config.clamp();
		VireClient.save();
		if (minecraft != null) {
			minecraft.gui.setScreen(parent);
		}
	}

	// ---- widgets ------------------------------------------------------------------------------------

	private static CycleButton<Boolean> toggle(String label, Supplier<Boolean> get, Consumer<Boolean> set, String tip) {
		CycleButton<Boolean> b = CycleButton.onOffBuilder(get.get()).create(0, 0, W, H, Component.literal(label), (btn, v) -> set.accept(v));
		b.setTooltip(Tooltip.create(Component.literal(tip)));
		return b;
	}

	private static AbstractWidget intSlider(String label, int min, int max, int step, Supplier<Integer> get, Consumer<Integer> set,
			java.util.function.IntFunction<String> fmt, String tip) {
		return slider(label, min, max, step, () -> (double) get.get(), v -> set.accept((int) Math.round(v)), v -> fmt.apply((int) Math.round(v)), tip);
	}

	/** A slider over {@code [min, max]} in steps of {@code step}; the label reads "Name: value". */
	private static AbstractWidget slider(String label, double min, double max, double step, Supplier<Double> get, Consumer<Double> set,
			DoubleFunction<String> fmt, String tip) {
		double start = (Math.max(min, Math.min(max, get.get())) - min) / (max - min);
		AbstractSliderButton s = new AbstractSliderButton(0, 0, W, H, Component.empty(), start) {
			{
				updateMessage();
			}

			private double current() {
				double v = min + value * (max - min);
				return Math.max(min, Math.min(max, Math.round(v / step) * step));
			}

			@Override
			protected void updateMessage() {
				setMessage(Component.literal(label + ": " + fmt.apply(current())));
			}

			@Override
			protected void applyValue() {
				set.accept(current());
			}
		};
		s.setTooltip(Tooltip.create(Component.literal(tip)));
		return s;
	}
}
