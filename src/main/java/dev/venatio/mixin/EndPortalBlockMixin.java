package dev.venatio.mixin;

import dev.venatio.mundo.MundosPartida;
import dev.venatio.mundo.Portales;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** En los mundos de una partida, el portal del End lleva al End de esa partida y de vuelta. */
@Mixin(EndPortalBlock.class)
abstract class EndPortalBlockMixin {
	@Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
	private void venatio$destinoEnPartida(ServerLevel currentLevel, Entity entity, BlockPos portalEntryPos,
										  CallbackInfoReturnable<TeleportTransition> cir) {
		if (!MundosPartida.esDePartida(currentLevel)) return;
		MundosPartida m = MundosPartida.activa();
		cir.setReturnValue(currentLevel == m.end() ? Portales.salidaDelEnd(m, entity) : Portales.entradaAlEnd(m, entity));
	}
}
