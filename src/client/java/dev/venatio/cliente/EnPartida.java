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
}
