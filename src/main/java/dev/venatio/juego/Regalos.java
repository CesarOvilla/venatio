package dev.venatio.juego;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.EquipmentSlot;
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
 */
public final class Regalos {
	private static final String MARCA = "venatio_regalo";
	public static final int COHETES = 64;

	private Regalos() {}

	public static boolean esRegalo(ItemStack st) {
		if (st.isEmpty()) return false;
		CustomData datos = st.get(DataComponents.CUSTOM_DATA);
		return datos != null && datos.copyTag().getBooleanOr(MARCA, false);
	}

	/** Pone lo que falte según las opciones: élitro y cohetes si están activos, botas con Depth Strider. */
	static void reponer(ServerPlayer p, Configuracion cfg) {
		if (cfg.elitro) {
			if (contar(p, Items.ELYTRA) == 0) {
				ItemStack elitro = marcar(p, new ItemStack(Items.ELYTRA), "venatio.regalo.elitro", true);
				if (p.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) p.setItemSlot(EquipmentSlot.CHEST, elitro);
				else p.getInventory().add(elitro);
			}
			if (contar(p, Items.FIREWORK_ROCKET) == 0) {
				ItemStack cohetes = marcar(p, new ItemStack(Items.FIREWORK_ROCKET, COHETES), "venatio.regalo.cohetes", false);
				// Como en MCBingo: en la última casilla de la barra rápida si está libre
				if (p.getInventory().getItem(8).isEmpty()) p.getInventory().setItem(8, cohetes);
				else p.getInventory().add(cohetes);
			}
		}
		if (cfg.depthStrider && contar(p, Items.LEATHER_BOOTS) == 0) {
			ItemStack botas = marcar(p, new ItemStack(Items.LEATHER_BOOTS), "venatio.regalo.botas", true);
			botas.enchant(encantamiento(p, Enchantments.DEPTH_STRIDER), 3);
			if (p.getItemBySlot(EquipmentSlot.FEET).isEmpty()) p.setItemSlot(EquipmentSlot.FEET, botas);
			else p.getInventory().add(botas);
		}
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
