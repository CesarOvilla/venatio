package dev.venatio.juego;

import dev.venatio.carton.Carton;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Foto de una partida al terminar: resultado, puesto de cada equipo y línea de tiempo de casillas.
 * La arma Partida al declarar el final; la usan las estadísticas, los tableros y la pantalla final.
 *
 * @param segundoVictoria segundo en que el ganador cumplió el objetivo (-1 sin ganador)
 */
public record ResumenPartida(Modo modo, ColorEquipo ganador, boolean empate, int duracion, int segundoVictoria, int totalCasillas,
							 List<Equipo> equipos, List<Hito> hitos, Map<UUID, ColorEquipo> participantes, Map<UUID, String> nombres) {

	/** casillas: las que cuentan (con casilla secreta, esa vale doble). puesto: 1 = primero; los empatados comparten puesto. */
	public record Equipo(ColorEquipo color, int casillas, int puesto, List<UUID> miembros) {}

	/** Una casilla conseguida. jugador puede ser null si no se registró quién fue. */
	public record Hito(int casilla, ItemStack icono, UUID jugador, ColorEquipo equipo, int segundo) {}

	/** Jugador con más casillas de la partida (desempata quien llegó antes a ese número). */
	public record Destacado(UUID jugador, int casillas) {}

	/** Terminada por el anfitrión sin ganador: no cuenta para las estadísticas. */
	public boolean cancelada() {
		return ganador == null && !empate;
	}

	public String nombre(UUID jugador) {
		return jugador == null ? "?" : nombres.getOrDefault(jugador, "?");
	}

	public Equipo equipo(ColorEquipo c) {
		for (Equipo e : equipos) if (e.color == c) return e;
		return null;
	}

	public Destacado destacado() {
		Map<UUID, Integer> cuenta = new HashMap<>();
		Map<UUID, Integer> ultimo = new HashMap<>();
		for (Hito h : hitos) {
			if (h.jugador == null) continue;
			cuenta.merge(h.jugador, 1, Integer::sum);
			ultimo.put(h.jugador, h.segundo);
		}
		return cuenta.keySet().stream()
			.min(Comparator.<UUID>comparingInt(u -> -cuenta.get(u)).thenComparingInt(ultimo::get))
			.map(u -> new Destacado(u, cuenta.get(u))).orElse(null);
	}

	public static ResumenPartida de(Partida p) {
		Carton carton = p.carton;
		Map<UUID, String> nombres = new HashMap<>();
		for (UUID u : p.participantes.keySet()) nombres.put(u, Juego.INSTANCIA.nombre(u));

		List<Hito> hitos = new ArrayList<>();
		for (int i = 0; i < carton.casillas.size(); i++) {
			Carton.Casilla cas = carton.casillas.get(i);
			for (Map.Entry<ColorEquipo, Integer> l : cas.logros.entrySet()) {
				UUID jugador = cas.jugadores.get(l.getKey());
				if (jugador != null) nombres.putIfAbsent(jugador, Juego.INSTANCIA.nombre(jugador));
				hitos.add(new Hito(i, cas.icono.copy(), jugador, l.getKey(), l.getValue()));
			}
		}
		hitos.sort(Comparator.comparingInt(Hito::segundo).thenComparingInt(Hito::casilla));

		// Orden final: el ganador primero; después más casillas (la secreta vale doble) y, a igual número, quien llegó antes.
		List<ColorEquipo> orden = new ArrayList<>(p.equipos);
		orden.sort(Comparator.<ColorEquipo>comparingInt(c -> c == p.ganador ? 0 : 1)
			.thenComparingInt(c -> -p.valor(c))
			.thenComparingInt(c -> llegada(carton, c)));
		List<Equipo> equipos = new ArrayList<>();
		for (int i = 0; i < orden.size(); i++) {
			ColorEquipo c = orden.get(i);
			int puesto = i + 1;
			if (i > 0) {
				ColorEquipo anterior = orden.get(i - 1);
				boolean igual = anterior != p.ganador && p.valor(anterior) == p.valor(c) && llegada(carton, anterior) == llegada(carton, c);
				if (igual) puesto = equipos.get(i - 1).puesto();
			}
			List<UUID> miembros = new ArrayList<>();
			p.participantes.forEach((u, eq) -> {
				if (eq == c) miembros.add(u);
			});
			equipos.add(new Equipo(c, p.valor(c), puesto, List.copyOf(miembros)));
		}

		int duracion = p.segundos();
		int victoria = p.ganador == null ? -1 : segundoVictoria(carton, p.cfg.modo, p.ganador, duracion);
		return new ResumenPartida(p.cfg.modo, p.ganador, p.empate, duracion, victoria, carton.casillas.size(),
			List.copyOf(equipos), List.copyOf(hitos), new LinkedHashMap<>(p.participantes), Map.copyOf(nombres));
	}

	/** Segundo de la última casilla del equipo; sin casillas cuenta como el final de los tiempos. */
	private static int llegada(Carton c, ColorEquipo e) {
		int s = c.ultimoLogro(e);
		return s < 0 ? Integer.MAX_VALUE : s;
	}

	/**
	 * Cuándo cumplió el objetivo el ganador. Con fin automático coincide con la duración; sin él,
	 * la partida siguió y hay que reconstruirlo a partir de los segundos de cada casilla.
	 */
	private static int segundoVictoria(Carton c, Modo modo, ColorEquipo e, int duracion) {
		int linea = primeraLinea(c, e);
		boolean lleno = c.puntos(e) == c.casillas.size();
		return switch (modo) {
			case LINEA -> linea >= 0 ? linea : duracion;
			case LOCKOUT -> linea >= 0 ? Math.min(linea, duracion) : duracion;
			case APAGON, CONTRARRELOJ -> lleno ? c.ultimoLogro(e) : duracion;
			case RASTREO -> duracion;
			case RASTREO_CARRERA -> c.ultimoLogro(e) >= 0 ? c.ultimoLogro(e) : duracion;
		};
	}

	/** Segundo en que el equipo completó su primera línea (fila, columna o diagonal), o -1. */
	private static int primeraLinea(Carton c, ColorEquipo e) {
		int n = c.tamano;
		List<int[]> lineas = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			int[] fila = new int[n], col = new int[n];
			for (int j = 0; j < n; j++) {
				fila[j] = i * n + j;
				col[j] = j * n + i;
			}
			lineas.add(fila);
			lineas.add(col);
		}
		int[] d1 = new int[n], d2 = new int[n];
		for (int i = 0; i < n; i++) {
			d1[i] = i * n + i;
			d2[i] = i * n + (n - 1 - i);
		}
		lineas.add(d1);
		lineas.add(d2);

		int mejor = -1;
		for (int[] linea : lineas) {
			int max = -1;
			boolean completa = true;
			for (int idx : linea) {
				Integer s = c.casillas.get(idx).logros.get(e);
				if (s == null) {
					completa = false;
					break;
				}
				max = Math.max(max, s);
			}
			if (completa && (mejor < 0 || max < mejor)) mejor = max;
		}
		return mejor;
	}
}
