package dev.venatio.cliente.mixin;

import dev.venatio.cliente.SonidosAyudas;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Calla los sonidos vanilla de las ayudas que Venatio sustituye por los suyos (ver {@link SonidosAyudas}). */
@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {
	@Inject(method = "play", at = @At("HEAD"), cancellable = true)
	private void venatio$callarSustituidos(SoundInstance sonido, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
		if (SonidosAyudas.calla(sonido)) cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
	}
}
