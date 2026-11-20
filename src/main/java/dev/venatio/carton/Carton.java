package dev.venatio.carton;

import dev.venatio.juego.ColorEquipo;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Cartón de N×N casillas. Las casillas van por filas: índice = fila * N + columna. */
public final class Carton {
	public final int tamano;
	public final List<Casilla> casillas;

	private Carton(int tamano, List<Casilla> casillas) {
		this.tamano = tamano;
		this.casillas = casillas;
	}

	public static final class Casilla {
		public final Objetivo objetivo;
		public final ItemStack icono;
		/** Equipo → segundo de partida en que la consiguió. */
		public final Map<ColorEquipo, Integer> logros = new EnumMap<>(ColorEquipo.class);
		/** Equipo → jugador que la consiguió para ese equipo. */
		public final Map<ColorEquipo, UUID> jugadores = new EnumMap<>(ColorEquipo.class);

		Casilla(Objetivo objetivo) {
			this.objetivo = objetivo;
			this.icono = objetivo.icono();
		}
	}

	/**
	 * Igual que MCBingo: N² categorías distintas al azar y una dificultad al azar para cada una.
	 * La misma semilla da siempre el mismo cartón.
	 */
	public static Carton generar(Pool pool, long semilla, int tamano) {
		Random rnd = new Random(semilla ^ 0x5DEECE66DL);
		int total = tamano * tamano;
		if (pool.categorias().size() < total) throw new IllegalStateException("El pool no alcanza para un cartón de " + tamano);
		List<Integer> indices = new ArrayList<>();
		for (int i = 0; i < pool.categorias().size(); i++) indices.add(i);
		Collections.shuffle(indices, rnd);
		List<Casilla> casillas = new ArrayList<>(total);
		for (int i = 0; i < total; i++) {
			Map<Integer, String> cat = pool.categorias().get(indices.get(i));
			int dificultad = 1 + rnd.nextInt(3);
			String texto = cat.getOrDefault(dificultad, cat.values().iterator().next());
			casillas.add(new Casilla(Objetivo.de(texto)));
		}
		return new Carton(tamano, List.copyOf(casillas));
	}

	/** Historial del modo Rastreo: empieza vacío y cada item encontrado se agrega como casilla de quien lo encontró. */
	public static Carton historial() {
		return new Carton(1, new ArrayList<>());
	}

	public void anotar(Objetivo objetivo, ColorEquipo equipo, int segundo, java.util.UUID jugador) {
		Casilla cas = new Casilla(objetivo);
		cas.logros.put(equipo, segundo);
		cas.jugadores.put(equipo, jugador);
		casillas.add(cas);
	}

	public int puntos(ColorEquipo c) {
		int n = 0;
		for (Casilla cas : casillas) if (cas.logros.containsKey(c)) n++;
		return n;
	}

	public int sinReclamar() {
		int n = 0;
		for (Casilla cas : casillas) if (cas.logros.isEmpty()) n++;
		return n;
	}

	/** Segundo en que el equipo consiguió su última casilla (para desempatar). */
	public int ultimoLogro(ColorEquipo c) {
		int max = -1;
		for (Casilla cas : casillas) max = Math.max(max, cas.logros.getOrDefault(c, -1));
		return max;
	}

	public boolean tieneLinea(ColorEquipo c) {
		if (casillas.size() != tamano * tamano) return false; // historial de Rastreo: no hay cuadrícula
		int n = tamano;
		boolean diag1 = true, diag2 = true;
		for (int i = 0; i < n; i++) {
			boolean fila = true, col = true;
			for (int j = 0; j < n; j++) {
				fila &= tiene(i, j, c);
				col &= tiene(j, i, c);
			}
			if (fila || col) return true;
			diag1 &= tiene(i, i, c);
			diag2 &= tiene(i, n - 1 - i, c);
		}
		return diag1 || diag2;
	}

	private boolean tiene(int fila, int col, ColorEquipo c) {
		return casillas.get(fila * tamano + col).logros.containsKey(c);
	}
}
