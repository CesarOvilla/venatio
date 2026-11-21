package dev.venatio.juego;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;

public enum Modo {
	/** Gana el primer equipo con una línea completa (fila, columna o diagonal). */
	LINEA,
	/** Gana el primer equipo que llena el cartón entero. */
	APAGON,
	/** Cada casilla solo la puede tener un equipo. Gana quien hace línea o tiene una mayoría que ya no se puede alcanzar. */
	LOCKOUT,
	/** Gana quien tenga más casillas al acabarse el tiempo. */
	CONTRARRELOJ,
	/** Rastreo (ItemFinder): un item a la vez; gana quien encuentre más antes de que se acabe el tiempo. */
	RASTREO,
	/** Rastreo (ItemFinder): un item a la vez; gana el primero en llegar a la meta. */
	RASTREO_CARRERA,
	/**
	 * Todos buscan el mismo item; el primer equipo que lo consigue gana un punto y, si nadie lo consigue en unos minutos,
	 * cambia solo. Gana quien tenga más puntos al acabar el tiempo o quien llegue primero a la meta.
	 */
	SWITCHEROO;

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	public MutableComponent nombre() {
		return Component.translatable("venatio.modo." + id());
	}

	/** Un item a la vez en vez de cartón (Switcheroo es un Rastreo compartido con tiempo por item). */
	public boolean esRastreo() {
		return this == RASTREO || this == RASTREO_CARRERA || this == SWITCHEROO;
	}

	public boolean exclusivo() {
		return this == LOCKOUT;
	}

	public static Modo porId(String id) {
		for (Modo m : values()) if (m.id().equals(id)) return m;
		return LINEA;
	}
}
