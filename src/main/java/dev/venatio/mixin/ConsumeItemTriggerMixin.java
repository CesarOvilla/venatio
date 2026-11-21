package dev.venatio.mixin;

import dev.venatio.juego.RegistroAcciones;
import net.minecraft.advancements.triggers.ConsumeItemTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Casillas de logro "Come …": cuenta solo lo que se termina de comer o beber. La estadística "usado" de vanilla no sirve
 * porque también sube al plantar bayas.
 */
@Mixin(ConsumeItemTrigger.class)
abstract class ConsumeItemTriggerMixin {
	@Inject(method = "trigger", at = @At("HEAD"))
	private void venatio$comio(ServerPlayer player, ItemStack itemStack, CallbackInfo ci) {
		RegistroAcciones.comio(player, itemStack.getItem());
	}
}
