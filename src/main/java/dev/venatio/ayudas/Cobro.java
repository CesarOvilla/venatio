package dev.venatio.ayudas;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Cobra y devuelve ayudas con items del inventario del jugador. */
final class Cobro {
	private Cobro() {}

	static int contar(ServerPlayer p, Item item) {
		return ContainerHelper.clearOrCountMatchingItems(p.getInventory(), s -> s.is(item), Integer.MAX_VALUE, true);
	}

	/** Quita el precio del inventario. Si no alcanza, le dice al jugador cuánto le falta y devuelve false. */
	static boolean cobrar(ServerPlayer p, Precios.Precio precio, TipoAyuda tipo) {
		if (precio.gratis()) return true;
		int tiene = contar(p, precio.item());
		if (tiene < precio.cantidad()) {
			Ayudas.error(p, Component.translatable("venatio.ayudas.error.faltan", Component.translatable(tipo.clave()),
				precio.cantidad() - tiene, nombre(precio.item()), tiene));
			return false;
		}
		ContainerHelper.clearOrCountMatchingItems(p.getInventory(), s -> s.is(precio.item()), precio.cantidad(), false);
		p.containerMenu.broadcastChanges();
		p.inventoryMenu.slotsChanged(p.getInventory());
		return true;
	}

	/** Devuelve el pago (si el inventario está lleno, lo que no cabe cae al suelo). */
	static void devolver(ServerPlayer p, Precios.Precio precio) {
		if (precio.gratis()) return;
		int resto = precio.cantidad();
		int porPila = new ItemStack(precio.item()).getMaxStackSize();
		while (resto > 0) {
			int n = Math.min(resto, porPila);
			p.getInventory().placeItemBackInInventory(new ItemStack(precio.item(), n), Prediction.SERVER_ONLY);
			resto -= n;
		}
	}

	static Component nombre(Item item) {
		return new ItemStack(item).getHoverName();
	}
}
