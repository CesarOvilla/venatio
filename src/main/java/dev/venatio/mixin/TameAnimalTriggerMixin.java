package dev.venatio.mixin;

import dev.venatio.juego.RegistroAcciones;
import net.minecraft.advancements.triggers.TameAnimalTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla no lleva estadística de animales domesticados: la casilla de logro "Domestica un animal" la cuenta aquí. */
@Mixin(TameAnimalTrigger.class)
abstract class TameAnimalTriggerMixin {
	@Inject(method = "trigger", at = @At("HEAD"))
	private void venatio$domestico(ServerPlayer player, Animal animal, CallbackInfo ci) {
		RegistroAcciones.domestico(player);
	}
}
