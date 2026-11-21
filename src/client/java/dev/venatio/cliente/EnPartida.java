package dev.venatio.cliente;

import dev.venatio.red.Paquetes;

/** Acceso público al estado para código fuera del paquete (mixins del cliente). */
public final class EnPartida {
	private EnPartida() {}

	/** true si el cliente está en una partida (cualquier fase que no sea el lobby). */
	public static boolean ahora() {
		Paquetes.Estado e = EstadoCliente.estado;
		return e != null && e.fase() != Paquetes.Estado.LOBBY;
	}

	/** Fase del último estado (Paquetes.Estado.LOBBY…FINAL), o -1 sin estado. */
	public static int fase() {
		Paquetes.Estado e = EstadoCliente.estado;
		return e == null ? -1 : e.fase();
	}

	/** Mi equipo (ordinal) o -1. */
	public static int miEquipo() {
		Paquetes.Estado e = EstadoCliente.estado;
		return e == null ? -1 : e.miEquipo();
	}

	/** "m:ss" como en el resto de la interfaz. */
	public static String reloj(int segundos) {
		return EstadoCliente.reloj(segundos);
	}
}
