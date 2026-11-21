package dev.venatio.juego;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.List;

/**
 * Items que da la partida (élitro, cohetes, botas). Llevan una marca propia para que <b>no cuenten para el cartón</b>:
 * con élitros activos, el cohete del cartón solo cuenta si lo crafteas (como en MCBingo), porque los cohetes que crafteas
 * no tienen la marca y no se apilan con los regalados.
 *
 * Como en MCBingo, son irrompibles y con maldición de desaparición (si no se conserva el inventario, desaparecen al morir
 * y nadie más los recoge), y se reponen: al reaparecer y cuando se acaban.
 *
 * Los cohetes viven en la casilla 9 de la barra rápida: no se pueden sacar de ahí ni poner otra cosa encima (SlotMixin),
 * lo que se cuele por otro camino (teclas numéricas, mano izquierda) vuelve en el siguiente tick, y al reaparecer se
 * rellenan a 64 aunque se conserve el inventario. El élitro de regalo sirve para planear desde cualquier parte del
 * inventario (LivingEntityMixin), así que se puede llevar pechera.
 */
public final class Regalos {
	private static final String MARCA = "venatio_regalo";
	public static final int COHETES = 64;
	/** Casilla 9 de la barra rápida. */
	public static final int RANURA_COHETES = 8;

	private Regalos() {}

	public static boolean esRegalo(ItemStack st) {
		if (st.isEmpty()) return false;
		CustomData datos = st.get(DataComponents.CUSTOM_DATA);
		return datos != null && datos.copyTag().getBooleanOr(MARCA, false);
	}

	public static boolean esCohete(ItemStack st) {
		return st.is(Items.FIREWORK_ROCKET) && esRegalo(st);
	}

	/** Lleva el élitro de regalo en cualquier parte del inventario, la armadura o la mano izquierda. Vale en los dos lados. */
	public static boolean tieneElitro(Player p) {
		Inventory inv = p.getInventory();
		for (int s = 0; s < inv.getContainerSize(); s++) {
			ItemStack st = inv.getItem(s);
			if (st.is(Items.ELYTRA) && esRegalo(st)) return true;
		}
		return false;
	}

	/**
	 * Pone lo que falte según las opciones: élitro y cohetes si están activos, botas con Depth Strider.
	 * reaparecio: los cohetes vuelven a 64 aunque se haya conservado el inventario.
	 */
	static void reponer(ServerPlayer p, Configuracion cfg, boolean reaparecio) {
		if (cfg.elitro) {
			if (contar(p, Items.ELYTRA) == 0) {
				ItemStack elitro = marcar(p, new ItemStack(Items.ELYTRA), "venatio.regalo.elitro", true);
				if (p.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) p.setItemSlot(EquipmentSlot.CHEST, elitro);
				else p.getInventory().add(elitro);
			}
			sujetarCohetes(p, reaparecio);
		}
		if (cfg.depthStrider && contar(p, Items.LEATHER_BOOTS) == 0) {
			ItemStack botas = marcar(p, new ItemStack(Items.LEATHER_BOOTS), "venatio.regalo.botas", true);
			botas.enchant(encantamiento(p, Enchantments.DEPTH_STRIDER), 3);
			if (p.getItemBySlot(EquipmentSlot.FEET).isEmpty()) p.setItemSlot(EquipmentSlot.FEET, botas);
			else p.getInventory().add(botas);
		}
	}

	/**
	 * Deja los cohetes de regalo en su casilla: junta los que estén en otra parte, saca lo que ocupe la casilla y la llena si
	 * se acabaron (o siempre, con llenar). Solo escribe en el inventario si algo cambió; se llama cada tick.
	 */
	static void sujetarCohetes(ServerPlayer p, boolean llenar) {
		Inventory inv = p.getInventory();
		int sueltos = 0;
		for (int s = 0; s < inv.getContainerSize(); s++) {
			if (s == RANURA_COHETES) continue;
			ItemStack st = inv.getItem(s);
			if (esCohete(st)) {
				sueltos += st.getCount();
				inv.setItem(s, ItemStack.EMPTY);
			}
		}
		ItemStack actual = inv.getItem(RANURA_COHETES);
		boolean son = esCohete(actual);
		int total = Math.min(COHETES, (son ? actual.getCount() : 0) + sueltos);
		// Si los trae en el cursor (no debería poder: SlotMixin), no se reponen hasta que los suelte.
		if (llenar || total == 0 && !esCohete(p.containerMenu.getCarried())) total = COHETES;
		if (total == 0 || son && actual.getCount() == total) return;
		ItemStack desplazado = son || actual.isEmpty() ? ItemStack.EMPTY : actual;
		inv.setItem(RANURA_COHETES, marcar(p, new ItemStack(Items.FIREWORK_ROCKET, total), "venatio.regalo.cohetes", false));
		if (!desplazado.isEmpty()) inv.placeItemBackInInventory(desplazado, Prediction.SERVER_ONLY);
	}

	/** Cuántos regalos de ese item tiene (inventario, armadura y el cursor). */
	static int contar(ServerPlayer p, Item item) {
		int n = 0;
		for (int s = 0; s < p.getInventory().getContainerSize(); s++) {
			ItemStack st = p.getInventory().getItem(s);
			if (st.is(item) && esRegalo(st)) n += st.getCount();
		}
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			ItemStack st = p.getItemBySlot(slot);
			if (st.is(item) && esRegalo(st)) n += st.getCount();
		}
		ItemStack cursor = p.containerMenu.getCarried();
		if (cursor.is(item) && esRegalo(cursor)) n += cursor.getCount();
		return n;
	}

	private static ItemStack marcar(ServerPlayer p, ItemStack st, String nombre, boolean irrompible) {
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(MARCA, true);
		st.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		st.set(DataComponents.ITEM_NAME, Component.translatable(nombre).withStyle(ChatFormatting.GOLD));
		st.set(DataComponents.LORE, new ItemLore(List.of(
			Component.translatable("venatio.regalo.lore").withStyle(ChatFormatting.GRAY))));
		if (irrompible) st.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
		st.enchant(encantamiento(p, Enchantments.VANISHING_CURSE), 1);
		return st;
	}

	private static net.minecraft.core.Holder<Enchantment> encantamiento(ServerPlayer p, ResourceKey<Enchantment> clave) {
		return p.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(clave);
	}
}
