package dev.venatio.juego;

import dev.venatio.carton.Dificultad;
import dev.venatio.carton.Exclusiones;
import dev.venatio.carton.Objetivo;
import dev.venatio.info.Fichas;
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
	/** Minutos que se pueden elegir para cada item de Switcheroo. */
	public static final int[] MINUTOS_SWITCHEROO = {1, 2, 3, 5};
	/** Minutos aproximados entre eventos sorpresa que se pueden elegir. */
	public static final int[] MINUTOS_EVENTOS = {5, 8, 10};

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

	public static Component meta(int items) {
		return cambiar(c -> c.meta = items, Component.translatable("venatio.config.meta", items));
	}

	public static Component pvp(boolean activo) {
		return cambiar(c -> c.pvp = activo, Component.translatable(activo ? "venatio.config.pvp_si" : "venatio.config.pvp_no"));
	}

	public static Component distancia(int bloques) {
		if (bloques < 0 || bloques > MAX_DISTANCIA) return fueraDeRango(0, MAX_DISTANCIA);
		return cambiar(c -> c.distancia = bloques, Component.translatable("venatio.config.distancia", bloques));
	}

	public static Component dificultad(Dificultad d) {
		return cambiar(c -> c.dificultad = d, Component.translatable("venatio.config.dificultad", d.nombre()));
	}

	/**
	 * Quita (excluir true) o vuelve a poner una entrada del pool ("minecraft:diamond" o "#minecraft:signs").
	 * Se puede aunque haya partida: cuenta desde la siguiente. Se guarda en config/venatio/pool.json.
	 */
	public static Component excluir(String entrada, boolean excluir) {
		Juego j = Juego.INSTANCIA;
		if (entrada == null || !j.pool().entradas().contains(entrada)) {
			return Component.translatable("venatio.pool.error.no_esta", entrada == null ? "?" : entrada);
		}
		Component nombre = nombreEntrada(entrada);
		if (!Exclusiones.cambiar(entrada, excluir)) {
			return Component.translatable(excluir ? "venatio.pool.error.ya_quitado" : "venatio.pool.error.no_quitado", nombre);
		}
		j.difundir(Component.translatable(excluir ? "venatio.pool.quitado" : "venatio.pool.puesto", nombre));
		Fichas.enviarItemsPosiblesATodos();
		for (Runnable o : oyentes) o.run();
		return null;
	}

	/** La entrada del pool que corresponde a un id escrito por el anfitrión: el item, o el grupo "#id". null si no está. */
	public static String entradaDelPool(String id) {
		var entradas = Juego.INSTANCIA.pool().entradas();
		if (entradas.contains(id)) return id;
		if (entradas.contains("#" + id)) return "#" + id;
		return null;
	}

	/** "Diamante", o "Cualquier letrero" para un grupo. */
	public static Component nombreEntrada(String entrada) {
		Component nombre;
		try {
			nombre = Objetivo.de(entrada).icono().getHoverName();
		} catch (IllegalArgumentException e) {
			return Component.literal(entrada); // pool.json editado a mano con un item que ya no existe
		}
		return entrada.startsWith("#") ? Component.translatable("venatio.pool.grupo", nombre) : nombre;
	}

	/** Switcheroo: minutos que dura cada item antes de cambiar solo. */
	public static Component switcheroo(int minutos) {
		if (!permitido(MINUTOS_SWITCHEROO, minutos)) return noPermitido(MINUTOS_SWITCHEROO);
		return cambiar(c -> c.switcheroo = minutos, Component.translatable("venatio.config.switcheroo", minutos));
	}

	/** Eventos sorpresa: minutos aproximados entre uno y otro. */
	public static Component eventosMinutos(int minutos) {
		if (!permitido(MINUTOS_EVENTOS, minutos)) return noPermitido(MINUTOS_EVENTOS);
		return cambiar(c -> c.eventosMinutos = minutos, Component.translatable("venatio.config.eventos_minutos", minutos));
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

	private static boolean permitido(int[] valores, int v) {
		for (int x : valores) if (x == v) return true;
		return false;
	}

	private static Component noPermitido(int[] valores) {
		return Component.translatable("venatio.config.error.valores", java.util.Arrays.stream(valores)
			.mapToObj(String::valueOf).collect(java.util.stream.Collectors.joining(", ")));
	}

	private static Component fueraDeRango(int min, int max) {
		return Component.translatable("venatio.stats.error.rango", min, max);
	}
}
