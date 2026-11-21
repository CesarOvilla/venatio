package dev.venatio.carton;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Lo que pide una casilla: un item concreto o cualquier item de un tag ("cualquier letrero"), opcionalmente en cantidad
 * ("16 troncos de roble"), o un logro (entrar a un bioma, matar un mob…).
 *
 * Como texto: "minecraft:cake", "#minecraft:signs", "16*minecraft:oak_log" o "reto:matar_creeper".
 *
 * @param cantidad unidades que tiene que juntar el equipo entre todos; 1 = basta con tener una
 * @param reto     el logro, o null si la casilla es de item
 */
public record Objetivo(String texto, Item item, TagKey<Item> tag, int cantidad, Reto reto) {
	public static final String PREFIJO_RETO = "reto:";

	public static Objetivo de(String texto) {
		if (texto.startsWith(PREFIJO_RETO)) {
			Reto reto = Retos.porId(texto.substring(PREFIJO_RETO.length()));
			if (reto == null) throw new IllegalArgumentException("Logro desconocido: " + texto);
			return new Objetivo(texto, null, null, 1, reto);
		}
		int estrella = texto.indexOf('*');
		if (estrella > 0) return de(texto.substring(estrella + 1)).conCantidad(Integer.parseInt(texto.substring(0, estrella)));
		if (texto.startsWith("#")) {
			return new Objetivo(texto, null, TagKey.create(Registries.ITEM, Identifier.parse(texto.substring(1))), 1, null);
		}
		Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(texto));
		if (item == null || item == Items.AIR) throw new IllegalArgumentException("Item desconocido en el pool: " + texto);
		return new Objetivo(texto, item, null, 1, null);
	}

	public static Objetivo de(Reto reto) {
		return new Objetivo(PREFIJO_RETO + reto.id(), null, null, 1, reto);
	}

	/** El mismo item pidiendo n unidades (n &lt;= 1 lo deja en una). No aplica a logros. */
	public Objetivo conCantidad(int n) {
		if (reto != null) return this;
		int c = Math.max(1, n);
		return new Objetivo(c > 1 ? c + "*" + base() : base(), item, tag, c, null);
	}

	/** El texto sin la cantidad: la entrada del pool ("minecraft:oak_log", "#minecraft:signs") o "reto:…". */
	public String base() {
		int estrella = texto.indexOf('*');
		return estrella > 0 ? texto.substring(estrella + 1) : texto;
	}

	public boolean esReto() {
		return reto != null;
	}

	/** Si el stack es del item pedido (sin mirar la cantidad). Un logro no se cumple con items. */
	public boolean cumple(ItemStack stack) {
		if (stack.isEmpty() || reto != null) return false;
		return item != null ? stack.is(item) : stack.is(tag);
	}

	/**
	 * Item que se dibuja en el cartón; para un tag, su primer elemento. Lleva el nombre de la casilla
	 * ("Mata un creeper", "16 × Tronco de roble"), así se lee igual en el HUD, el chat y la pantalla final.
	 */
	public ItemStack icono() {
		if (reto != null) return reto.pila();
		ItemStack st = iconoBase();
		if (cantidad > 1) {
			st.set(DataComponents.ITEM_NAME, Component.translatable("venatio.carton.cantidad", cantidad, st.getHoverName()));
		}
		return st;
	}

	private ItemStack iconoBase() {
		if (item != null) return new ItemStack(item);
		for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) return new ItemStack(h);
		return new ItemStack(Items.BARRIER);
	}

	/** Item de la ficha: el pedido, o el relacionado del logro (puede ser null). */
	public Item itemFicha() {
		if (reto != null) return reto.itemRelacionado();
		return iconoBase().getItem();
	}

	/**
	 * Clave estable de lo que pedía un icono de casilla, para guardarla (estadísticas): el id del item, o "venatio:reto/&lt;id&gt;".
	 * Solo distingue logros en el servidor (la marca no viaja al cliente).
	 */
	public static String clave(ItemStack icono) {
		Reto r = Reto.de(icono);
		if (r != null) return "venatio:reto/" + r.id();
		return BuiltInRegistries.ITEM.getKey(icono.getItem()).toString();
	}

	/** Nombre para una clave de {@link #clave}; null si no es de logro. */
	public static Component nombreDeClave(String clave) {
		if (!clave.startsWith("venatio:reto/")) return null;
		Reto r = Retos.porId(clave.substring("venatio:reto/".length()));
		return r == null ? null : r.nombre();
	}
}
