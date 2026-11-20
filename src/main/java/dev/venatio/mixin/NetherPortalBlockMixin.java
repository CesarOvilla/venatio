package dev.venatio.mixin;

import dev.venatio.mundo.MundosPartida;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** En los mundos de una partida, el portal lleva al Nether (o al mundo) de esa misma partida. */
@Mixin(NetherPortalBlock.class)
abstract class NetherPortalBlockMixin {
	@Shadow
	private TeleportTransition getExitPortal(ServerLevel newLevel, Entity entity, BlockPos portalEntryPos,
											 BlockPos approximateExitPos, boolean toNether, WorldBorder worldBorder) {
		throw new AssertionError();
	}

	@Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
	private void venatio$destinoEnPartida(ServerLevel currentLevel, Entity entity, BlockPos portalEntryPos,
										  CallbackInfoReturnable<TeleportTransition> cir) {
		if (!MundosPartida.esDePartida(currentLevel)) return;
		ServerLevel destino = MundosPartida.destinoNether(currentLevel);
		if (destino == null) {
			cir.setReturnValue(null);
			return;
		}
		boolean haciaNether = destino == MundosPartida.activa().nether();
		WorldBorder borde = destino.getWorldBorder();
		double escala = DimensionType.getTeleportationScale(currentLevel.dimensionType(), destino.dimensionType());
		BlockPos aprox = borde.clampToBounds(entity.getX() * escala, entity.getY(), entity.getZ() * escala);
		cir.setReturnValue(getExitPortal(destino, entity, portalEntryPos, aprox, haciaNether, borde));
	}
}
