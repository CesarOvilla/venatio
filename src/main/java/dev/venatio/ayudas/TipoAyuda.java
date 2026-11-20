package dev.venatio.ayudas;

import dev.venatio.red.PaquetesAyudas;

/**
 * Las ayudas que se compran. El orden (ordinal) viaja por la red; las tres guías coinciden con
 * {@link PaquetesAyudas.PedirGuia#BIOMA}, {@code ESTRUCTURA} y {@code MOB}.
 */
public enum TipoAyuda {
	GUIA_BIOMA("guia_bioma"),
	GUIA_ESTRUCTURA("guia_estructura"),
	GUIA_MOB("guia_mob"),
	MINIMAPA("minimapa"),
	MARCA("marca");

	/** Clave en precios.json y sufijo de las claves de idioma. */
	public final String id;

	TipoAyuda(String id) {
		this.id = id;
	}

	public boolean esGuia() {
		return this == GUIA_BIOMA || this == GUIA_ESTRUCTURA || this == GUIA_MOB;
	}

	/** Clave de idioma con el nombre de la ayuda ("Guía a bioma"). */
	public String clave() {
		return "venatio.ayudas." + id;
	}

	public static TipoAyuda porOrdinal(int i) {
		return i >= 0 && i < values().length ? values()[i] : null;
	}

	/** Tipo de guía de un {@link PaquetesAyudas.PedirGuia}; null si no es válido. */
	public static TipoAyuda deGuia(int tipo) {
		return switch (tipo) {
			case PaquetesAyudas.PedirGuia.BIOMA -> GUIA_BIOMA;
			case PaquetesAyudas.PedirGuia.ESTRUCTURA -> GUIA_ESTRUCTURA;
			case PaquetesAyudas.PedirGuia.MOB -> GUIA_MOB;
			default -> null;
		};
	}
}
