package dev.venatio.mixin;

import dev.venatio.juego.Regalos;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gameevent.GameEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * El élitro de regalo sirve para planear desde cualquier parte del inventario, para poder llevar pechera. Mira solo los
 * items (el élitro lleva la marca de regalo), así que cliente y servidor deciden lo mismo.
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
	@Shadow
	protected abstract boolean canGlide();

	@Inject(method = "canGlide", at = @At("RETURN"), cancellable = true)
	private void venatio$elitroEnElInventario(CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) return;
		LivingEntity yo = (LivingEntity) (Object) this;
		if (yo instanceof Player p && !p.onGround() && !p.isPassenger() && !p.hasEffect(MobEffects.LEVITATION) && Regalos.tieneElitro(p)) {
			cir.setReturnValue(true);
		}
	}

	/**
	 * Vanilla desgasta cada segundo un planeador puesto; si se planea con el élitro guardado no hay ninguno puesto y
	 * elegiría al azar de una lista vacía. En ese caso se hace lo mismo sin desgastar (el élitro de regalo es irrompible).
	 */
	@Inject(method = "updateFallFlying", at = @At("HEAD"), cancellable = true)
	private void venatio$planearConElitroGuardado(CallbackInfo ci) {
		LivingEntity yo = (LivingEntity) (Object) this;
		if (yo.level().isClientSide()) return;
		for (EquipmentSlot slot : EquipmentSlot.VALUES) {
			if (LivingEntity.canGlideUsing(yo.getItemBySlot(slot), slot)) return;
		}
		if (!canGlide()) return; // vanilla deja de planear
		yo.checkFallDistanceAccumulation();
		if ((yo.getFallFlyingTicks() + 1) % 10 == 0) yo.gameEvent(GameEvent.ELYTRA_GLIDE);
		ci.cancel();
	}
}
