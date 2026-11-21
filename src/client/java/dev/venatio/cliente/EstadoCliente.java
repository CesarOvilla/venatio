package dev.venatio.cliente;

import dev.venatio.red.Paquetes;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

/** Último estado recibido del servidor. Las animaciones y los sonidos que dispara van en {@link Animaciones}. Solo se usa en el hilo del cliente. */
public final class EstadoCliente {
	static Paquetes.Estado estado;
	private static long recibidoEn;

	private EstadoCliente() {}

	static void recibir(Paquetes.Estado e) {
		Paquetes.Estado antes = estado;
		estado = e;
		recibidoEn = Util.getMillis();
		Animaciones.alCambiarEstado(antes, e);
		VariantesCliente.alCambiarEstado(antes, e);
	}

	static void limpiar() {
		estado = null;
		Animaciones.limpiar();
		VariantesCliente.limpiar();
	}

	/** Segundos de juego, avanzando entre paquetes para que el reloj no salte. */
	static int segundos() {
		if (estado == null) return 0;
		if (estado.fase() != Paquetes.Estado.JUGANDO) return estado.segundos();
		long extra = Math.min(1, (Util.getMillis() - recibidoEn) / 1000);
		return estado.segundos() + (int) extra;
	}

	static void evento(Paquetes.Evento ev, Minecraft mc) {
		Animaciones.evento(ev, estado);
	}

	static String reloj(int segundos) {
		int s = Math.max(0, segundos);
		return String.format("%d:%02d", s / 60, s % 60);
	}
}
