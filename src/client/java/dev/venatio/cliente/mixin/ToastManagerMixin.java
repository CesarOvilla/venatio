package dev.venatio.cliente.mixin;

import dev.venatio.cliente.EnPartida;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.components.toasts.RecipeToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Durante la partida no salen avisos de "receta desbloqueada" ni de logros: tapaban el minimapa y no aportan al bingo. */
@Mixin(ToastManager.class)
public abstract class ToastManagerMixin {
	@Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
	private void venatio$ocultarEnPartida(Toast toast, CallbackInfo ci) {
		if (EnPartida.ahora() && (toast instanceof RecipeToast || toast instanceof AdvancementToast)) {
			ci.cancel();
		}
	}
}
