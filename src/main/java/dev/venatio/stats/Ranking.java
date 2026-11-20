package dev.venatio.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Ranking por temporada. Cada partida da puntos por puesto del equipo (1.º 3, 2.º 2, 3.º 1) a todos sus miembros;
 * un equipo sin casillas no suma. En la tabla se ordena por puntos, luego victorias y luego casillas.
 */
public final class Ranking {
	private Ranking() {}

	public static int puntosPorPuesto(int puesto, int casillas) {
		if (casillas <= 0) return 0;
		return switch (puesto) {
			case 1 -> 3;
			case 2 -> 2;
			case 3 -> 1;
			default -> 0;
		};
	}

	/** puesto: los empatados en puntos, victorias y casillas lo comparten. */
	record Fila(int puesto, String uuid, String nombre, int puntos, int victorias, int casillas, int partidas) {}

	private static final Comparator<Fila> ORDEN = Comparator.comparingInt(Fila::puntos).reversed()
		.thenComparing(Comparator.comparingInt(Fila::victorias).reversed())
		.thenComparing(Comparator.comparingInt(Fila::casillas).reversed())
		.thenComparing(Fila::nombre, String.CASE_INSENSITIVE_ORDER);

	/** Tabla completa de una temporada: solo quien jugó al menos una partida en ella. */
	static List<Fila> tabla(int temporada) {
		Estadisticas.Datos d = Estadisticas.datos();
		List<Fila> filas = new ArrayList<>();
		if (d == null) return filas;
		String clave = String.valueOf(temporada);
		for (Map.Entry<String, Estadisticas.Jugador> e : d.jugadores.entrySet()) {
			Estadisticas.Temporada t = e.getValue().temporadas.get(clave);
			if (t == null || t.partidas <= 0) continue;
			filas.add(new Fila(0, e.getKey(), e.getValue().nombre, t.puntos, t.victorias, t.casillas, t.partidas));
		}
		filas.sort(ORDEN);
		List<Fila> conPuesto = new ArrayList<>(filas.size());
		for (int i = 0; i < filas.size(); i++) {
			Fila f = filas.get(i);
			int puesto = i + 1;
			if (i > 0) {
				Fila a = conPuesto.get(i - 1);
				if (a.puntos == f.puntos && a.victorias == f.victorias && a.casillas == f.casillas) puesto = a.puesto;
			}
			conPuesto.add(new Fila(puesto, f.uuid, f.nombre, f.puntos, f.victorias, f.casillas, f.partidas));
		}
		return conPuesto;
	}
}
