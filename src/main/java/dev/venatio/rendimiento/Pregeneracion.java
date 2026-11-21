package dev.venatio.rendimiento;

/**
 * Cuánto terreno se genera alrededor de cada punto de aparición en la fase PREPARANDO, antes de llevar a los jugadores.
 * El valor por defecto sale de las mediciones de docs/RENDIMIENTO.md. Se puede cambiar sin recompilar con
 * -Dvenatio.pregen=&lt;radio en chunks&gt; (lo usa PruebaRendimiento para comparar radios).
 */
public final class Pregeneracion {
	/** Radio en chunks por defecto. */
	public static final int RADIO = 2;

	private Pregeneracion() {}

	public static int radio() {
		return Math.max(0, Math.min(32, Integer.getInteger("venatio.pregen", RADIO)));
	}
}
