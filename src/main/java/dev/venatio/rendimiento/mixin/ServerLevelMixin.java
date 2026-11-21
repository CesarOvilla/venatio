package dev.venatio.rendimiento.mixin;

import dev.venatio.mundo.MundosPartida;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProgressListener;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Los mundos de una partida no entran al autoguardado de cada 5 minutos. Ese guardado serializa en el hilo principal
 * todos los chunks modificados de golpe (en un mundo recién generado son casi todos) y da un tirón; en mundos que se
 * borran al terminar no protege nada. Los chunks se siguen guardando al descargarse, así que volver a una zona la
 * encuentra como se dejó. El guardado con flush (apagar el servidor) ya lo omite Fantasy para mundos temporales.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
	@Inject(method = "save", at = @At("HEAD"), cancellable = true)
	private void venatio$sinAutoguardadoEnPartida(@Nullable ProgressListener progressListener, boolean flush, boolean noSave, CallbackInfo ci) {
		if (!flush && MundosPartida.esDePartida((ServerLevel) (Object) this)) ci.cancel();
	}
}
