package dev.venatio.ayudas;

import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Opcion;
import dev.venatio.juego.Partida;
import net.minecraft.server.level.ServerPlayer;

/**
 * Descuento al último lugar (opción "descuento"): si hay 2 equipos o más y el tuyo va último por al menos 2 casillas
 * o puntos, las ayudas te cuestan un 20 % menos (hacia abajo, mínimo 1 si no eran gratis). Vale en todos los modos:
 * en el Rastreo y en Switcheroo cuentan los items conseguidos.
 */
public final class Descuento {
	public static final int PORCENTAJE = 20;
	/** Ventaja mínima del equipo de arriba para que haya descuento. */
	public static final int DIFERENCIA = 2;

	private Descuento() {}

	/** true si este jugador paga con descuento ahora mismo. */
	public static boolean aplica(ServerPlayer p) {
		Partida partida = Juego.INSTANCIA.partida();
		if (partida == null || !partida.opcion(Opcion.DESCUENTO) || !partida.participa(p)) return false;
		return enUltimoLugar(partida, partida.equipoDe(p));
	}

	/**
	 * Ningún equipo tiene menos puntos y el siguiente de arriba le lleva 2 o más. Si varios empatan en el último lugar,
	 * todos tienen descuento; si todos van iguales, nadie.
	 */
	public static boolean enUltimoLugar(Partida partida, ColorEquipo equipo) {
		if (equipo == null || partida.equipos().size() < 2) return false;
		int mios = partida.carton().puntos(equipo);
		int siguiente = Integer.MAX_VALUE;
		for (ColorEquipo c : partida.equipos()) {
			int puntos = partida.carton().puntos(c);
			if (puntos < mios) return false;
			if (puntos > mios) siguiente = Math.min(siguiente, puntos);
		}
		return siguiente != Integer.MAX_VALUE && siguiente - mios >= DIFERENCIA;
	}

	/** El precio con el descuento aplicado. */
	public static Precios.Precio rebajar(Precios.Precio precio) {
		if (precio.gratis()) return precio;
		int nuevo = Math.max(1, precio.cantidad() * (100 - PORCENTAJE) / 100);
		return new Precios.Precio(precio.item(), nuevo, precio.enfriamiento());
	}
}
