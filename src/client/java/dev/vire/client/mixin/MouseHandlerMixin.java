package dev.vire.client.mixin;

import dev.vire.client.interact.VireInput;
import dev.vire.client.mode.VireMode;
import dev.vire.client.mode.VireSession;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Mouse look turns the free camera, and the scroll wheel moves the virtual hotbar. */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@Redirect(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void vire$turn(LocalPlayer player, double x, double y) {
		VireSession s = VireMode.session();
		if (s != null) {
			s.camera.turn(x, y);
		} else {
			player.turn(x, y);
		}
	}

	@Redirect(method = "onScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;setSelectedSlot(I)V"))
	private void vire$scroll(Inventory inventory, int slot) {
		if (VireMode.active()) {
			VireInput.scroll(inventory.getSelectedSlot(), slot);
		} else {
			inventory.setSelectedSlot(slot);
		}
	}
}
