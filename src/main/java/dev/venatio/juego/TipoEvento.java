package dev.venatio.juego;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Los eventos sorpresa. segundos: lo que dura (0 = pasa de una vez). El nombre y la descripción salen de
 * venatio.evento.&lt;id&gt; y venatio.evento.&lt;id&gt;.desc. El id viaja por la red y es el de /venatio evento &lt;id&gt;.
 */
public enum TipoEvento {
	/** 2 minutos de noche en el mundo de la partida. */
	NOCHE("noche", Items.CLOCK, 120),
	/** Velocidad II y Prisa II para todos, 1 minuto. */
	VELOCIDAD("velocidad", Items.SUGAR, 60),
	/** Cada jugador del mundo normal, a un punto seguro en superficie a 150–300 bloques. */
	TELETRANSPORTE("teletransporte", Items.ENDER_PEARL, 0),
	/** Dos jugadores de equipos distintos cambian de lugar. */
	INTERCAMBIO("intercambio", Items.ENDER_EYE, 0),
	/** 2 o 3 monstruos cerca de cada jugador del mundo normal (sin creepers en el primer minuto). */
	HORDA("horda", Items.ZOMBIE_HEAD, 0),
	/** Comida, hierro, carbón y troncos caen alrededor de cada jugador. */
	LLUVIA("lluvia", Items.BREAD, 0),
	/** Salto alto y caída lenta, 1 minuto. */
	LUNAR("lunar", Items.FEATHER, 60),
	/** Oscuridad breve. */
	NIEBLA("niebla", Items.INK_SAC, 25),
	/** Tormenta eléctrica 2 minutos; los rayos no lastiman a los jugadores de la partida. */
	TORMENTA("tormenta", Items.TRIDENT, 120),
	/** Un mercader nómada junto a cada equipo, que se va a los 3 minutos. */
	MERCADER("mercader", Items.EMERALD, 0),
	/** Todos brillan y se ven a través de las paredes, 1 minuto. */
	BRILLO("brillo", Items.GLOWSTONE_DUST, 60),
	/** Orbes de experiencia alrededor de cada jugador. */
	EXPERIENCIA("experiencia", Items.EXPERIENCE_BOTTLE, 0);

	public final String id;
	public final Item icono;
	public final int segundos;

	TipoEvento(String id, Item icono, int segundos) {
		this.id = id;
		this.icono = icono;
		this.segundos = segundos;
	}

	public MutableComponent nombre() {
		return Component.translatable("venatio.evento." + id);
	}

	public MutableComponent descripcion() {
		return Component.translatable("venatio.evento." + id + ".desc");
	}

	/** El icono con el nombre del evento, para la línea de tiempo de la pantalla final. */
	public ItemStack iconoConNombre() {
		ItemStack st = new ItemStack(icono);
		st.set(DataComponents.ITEM_NAME, nombre());
		return st;
	}

	public static TipoEvento porId(String id) {
		for (TipoEvento t : values()) if (t.id.equals(id)) return t;
		return null;
	}
}
