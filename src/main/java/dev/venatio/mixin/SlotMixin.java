package dev.venatio.mixin;

import dev.venatio.juego.Regalos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * La casilla 9 con los cohetes de regalo está fija: no se pueden tomar (clic, shift-clic, Q en el inventario, doble clic)
 * ni poner nada encima. Mira solo el item, así que vale igual en el cliente y en el servidor.
 */
@Mixin(Slot.class)
abstract class SlotMixin {
	@Shadow @Final public Container container;

	@Shadow
	public abstract int getContainerSlot();

	@Shadow
	public abstract ItemStack getItem();

	@Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
	private void venatio$cohetesFijos(Player player, CallbackInfoReturnable<Boolean> cir) {
		if (casillaDeCohetes()) cir.setReturnValue(false);
	}

	@Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
	private void venatio$nadaSobreLosCohetes(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (casillaDeCohetes()) cir.setReturnValue(false);
	}

	private boolean casillaDeCohetes() {
		return container instanceof Inventory && getContainerSlot() == Regalos.RANURA_COHETES && Regalos.esCohete(getItem());
	}
}
