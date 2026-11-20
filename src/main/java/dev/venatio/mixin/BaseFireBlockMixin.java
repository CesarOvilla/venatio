package dev.venatio.mixin;

import dev.venatio.mundo.MundosPartida;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla solo enciende portales del Nether en las dimensiones minecraft:overworld y minecraft:the_nether. */
@Mixin(BaseFireBlock.class)
abstract class BaseFireBlockMixin {
	@Inject(method = "inPortalDimension", at = @At("HEAD"), cancellable = true)
	private static void venatio$portalEnPartida(Level level, CallbackInfoReturnable<Boolean> cir) {
		if (MundosPartida.permitePortalNether(level)) cir.setReturnValue(true);
	}
}
