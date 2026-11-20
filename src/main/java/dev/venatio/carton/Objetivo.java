package dev.venatio.carton;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Lo que pide una casilla: un item concreto o cualquier item de un tag ("cualquier letrero"). */
public record Objetivo(String texto, Item item, TagKey<Item> tag) {

	public static Objetivo de(String texto) {
		if (texto.startsWith("#")) {
			return new Objetivo(texto, null, TagKey.create(Registries.ITEM, Identifier.parse(texto.substring(1))));
		}
		Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(texto));
		if (item == null || item == Items.AIR) throw new IllegalArgumentException("Item desconocido en el pool: " + texto);
		return new Objetivo(texto, item, null);
	}

	public boolean cumple(ItemStack stack) {
		if (stack.isEmpty()) return false;
		return item != null ? stack.is(item) : stack.is(tag);
	}

	/** Item que se dibuja en el cartón; para un tag, su primer elemento. */
	public ItemStack icono() {
		if (item != null) return new ItemStack(item);
		for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) return new ItemStack(h);
		return new ItemStack(Items.BARRIER);
	}
}
