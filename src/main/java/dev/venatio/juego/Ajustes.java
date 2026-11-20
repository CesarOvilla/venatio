package dev.venatio.juego;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Cambios de configuración del anfitrión. Los usan los comandos (/venatio modo, tiempo…) y el panel del atril,
 * así los dos validan igual y avisan igual en el chat. Cada método devuelve el error, o null si se aplicó.
 */
public final class Ajustes {
	public static final int MAX_MINUTOS = 300;
	public static final int MAX_DISTANCIA = 10000;

	private static final List<Runnable> oyentes = new ArrayList<>();

	private Ajustes() {}

	/** Se llama después de cada cambio aplicado (p. ej. para refrescar el panel del anfitrión). */
	public static void alCambiar(Runnable oyente) {
		oyentes.add(oyente);
	}

	public static Component modo(Modo m) {
		return cambiar(c -> c.modo = m, Component.translatable("venatio.config.modo", m.nombre()));
	}

	public static Component tiempo(int minutos) {
		if (minutos < 0 || minutos > MAX_MINUTOS) return fueraDeRango(0, MAX_MINUTOS);
		return cambiar(c -> c.minutos = minutos, minutos == 0
			? Component.translatable("venatio.config.sin_tiempo")
			: Component.translatable("venatio.config.tiempo", minutos));
	}

	/** semilla null = aleatoria. */
	public static Component semilla(Long semilla) {
		return cambiar(c -> c.semilla = semilla, semilla == null
			? Component.translatable("venatio.config.semilla_aleatoria")
			: Component.translatable("venatio.config.semilla", String.valueOf(semilla)));
	}

	public static Component pvp(boolean activo) {
		return cambiar(c -> c.pvp = activo, Component.translatable(activo ? "venatio.config.pvp_si" : "venatio.config.pvp_no"));
	}

	public static Component distancia(int bloques) {
		if (bloques < 0 || bloques > MAX_DISTANCIA) return fueraDeRango(0, MAX_DISTANCIA);
		return cambiar(c -> c.distancia = bloques, Component.translatable("venatio.config.distancia", bloques));
	}

	public static Component opcion(Opcion o, boolean valor) {
		return cambiar(c -> o.poner(c, valor), Component.translatable("venatio.config.opcion", o.nombre(),
			Component.translatable(valor ? "venatio.config.si" : "venatio.config.no")));
	}

	private interface Cambio {
		void aplicar(Configuracion c);
	}

	private static Component cambiar(Cambio cambio, Component confirmacion) {
		Juego j = Juego.INSTANCIA;
		if (j.partida() != null) return Component.translatable("venatio.error.partida_en_curso");
		cambio.aplicar(j.config);
		j.actualizarTableros();
		j.enviarEstadoATodos();
		j.difundir(confirmacion);
		for (Runnable o : oyentes) o.run();
		return null;
	}

	private static Component fueraDeRango(int min, int max) {
		return Component.translatable("venatio.stats.error.rango", min, max);
	}
}
