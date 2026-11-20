package dev.venatio.cliente;

import dev.venatio.red.Paquetes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;

import java.util.HashMap;
import java.util.Map;

/** Último estado recibido del servidor y animaciones en curso. Solo se usa en el hilo del cliente. */
public final class EstadoCliente {
	static Paquetes.Estado estado;
	private static long recibidoEn;
	/** Casilla → momento (ms) en que la consiguió mi equipo, para la animación. */
	static final Map<Integer, Long> animaciones = new HashMap<>();
	static final long DURACION_ANIMACION = 1500;

	private EstadoCliente() {}

	static void recibir(Paquetes.Estado e) {
		estado = e;
		recibidoEn = Util.getMillis();
	}

	static void limpiar() {
		estado = null;
		animaciones.clear();
	}

	/** Segundos de juego, avanzando entre paquetes para que el reloj no salte. */
	static int segundos() {
		if (estado == null) return 0;
		if (estado.fase() != Paquetes.Estado.JUGANDO) return estado.segundos();
		long extra = Math.min(1, (Util.getMillis() - recibidoEn) / 1000);
		return estado.segundos() + (int) extra;
	}

	static void evento(Paquetes.Evento ev, Minecraft mc) {
		int miEquipo = estado == null ? -1 : estado.miEquipo();
		switch (ev.tipo()) {
			case Paquetes.Evento.CASILLA -> {
				if (ev.equipo() == miEquipo) {
					animaciones.put(ev.casilla(), Util.getMillis());
					mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.4f));
				} else {
					mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 0.6f));
				}
			}
			case Paquetes.Evento.VICTORIA -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f));
			case Paquetes.Evento.INICIO -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.5f));
			default -> {}
		}
	}

	static String reloj(int segundos) {
		int s = Math.max(0, segundos);
		return String.format("%d:%02d", s / 60, s % 60);
	}
}
