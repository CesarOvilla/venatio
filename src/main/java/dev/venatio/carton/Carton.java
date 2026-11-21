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
		/** Nivel con que se eligió (1 fácil a 3 difícil; 0 en el historial de Rastreo). */
		public final int dificultad;
		/** Equipo → segundo de partida en que la consiguió. */
		public final Map<ColorEquipo, Integer> logros = new EnumMap<>(ColorEquipo.class);
		/** Equipo → jugador que la consiguió para ese equipo. */
		public final Map<ColorEquipo, UUID> jugadores = new EnumMap<>(ColorEquipo.class);
		/** Casillas con cantidad: equipo → unidades que juntan ahora entre todos (como mucho, las pedidas). */
		public final Map<ColorEquipo, Integer> progreso = new EnumMap<>(ColorEquipo.class);

		Casilla(Objetivo objetivo, int dificultad) {
			this.objetivo = objetivo;
			this.icono = objetivo.icono();
			this.dificultad = dificultad;
		}
	}

	/** Como MCBingo: solo items. */
	public static Carton generar(Pool pool, long semilla, int tamano) {
		return generar(pool, semilla, tamano, Reglas.CLASICAS);
	}

	/**
	 * Igual que MCBingo: categorías distintas al azar y un nivel para cada una (con los pesos de la dificultad).
	 * Con logros, ~25–30 % de las casillas son logros; con cantidades, ~30 % de las de item piden varias unidades.
	 * La misma semilla da siempre el mismo cartón; con las reglas clásicas, el mismo que antes de que existieran.
	 */
	public static Carton generar(Pool pool, long semilla, int tamano, Reglas reglas) {
		Random rnd = new Random(semilla ^ 0x5DEECE66DL);
		// Logros, cantidades y posiciones van con su propio generador: así no cambian qué items salen.
		Random variantes = new Random(semilla * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L);
		int total = tamano * tamano;
		int retos = reglas.logros() ? Math.max(1, Math.round(total * (0.25f + 0.05f * variantes.nextFloat()))) : 0;
		int items = Math.min(total - retos, pool.categorias().size());
		// Si el pool no alcanza (muchos items quitados), los logros rellenan.
		if (reglas.logros()) retos = Math.min(total - items, Retos.TODOS.size());
		if (items + retos < total) {
			throw new IllegalStateException("El pool no alcanza para un cartón de " + tamano + " (" + pool.categorias().size() + " categorías)");
		}

		List<Integer> indices = new ArrayList<>();
		for (int i = 0; i < pool.categorias().size(); i++) indices.add(i);
		Collections.shuffle(indices, rnd);
		List<Integer> niveles = reglas.dificultad().niveles(items, rnd);
		List<Casilla> casillas = new ArrayList<>(total);
		for (int i = 0; i < items; i++) {
			int nivel = niveles.get(i);
			casillas.add(new Casilla(Objetivo.de(Pool.deNivel(pool.categorias().get(indices.get(i)), nivel)), nivel));
		}

		if (reglas.cantidades()) {
			List<Integer> apilables = new ArrayList<>();
			for (int i = 0; i < casillas.size(); i++) if (Cantidades.apilable(casillas.get(i).objetivo.itemFicha())) apilables.add(i);
			Collections.shuffle(apilables, variantes);
			int conCantidad = Math.min(apilables.size(), Math.round(items * Cantidades.PROPORCION));
			for (int k = 0; k < conCantidad; k++) {
				int i = apilables.get(k);
				Casilla c = casillas.get(i);
				int n = Cantidades.para(c.objetivo.itemFicha(), c.dificultad, reglas.dificultad(), variantes);
				casillas.set(i, new Casilla(c.objetivo.conCantidad(n), c.dificultad));
			}
		}
		if (retos > 0) {
			for (Reto r : Retos.elegir(retos, variantes, reglas.dificultad())) casillas.add(new Casilla(Objetivo.de(r), r.dificultad()));
			Collections.shuffle(casillas, variantes);
		}
		return new Carton(tamano, casillas);
	}

	/** Historial del modo Rastreo: empieza vacío y cada item encontrado se agrega como casilla de quien lo encontró. */
	public static Carton historial() {
		return new Carton(1, new ArrayList<>());
	}

	public void anotar(Objetivo objetivo, ColorEquipo equipo, int segundo, java.util.UUID jugador) {
		Casilla cas = new Casilla(objetivo, 0);
		cas.logros.put(equipo, segundo);
		cas.jugadores.put(equipo, jugador);
		casillas.add(cas);
	}

	/** Cambia lo que pide una casilla todavía sin dueño (pruebas, y para armar cartones a mano). */
	public void reemplazar(int indice, Objetivo objetivo) {
		Casilla vieja = casillas.get(indice);
		if (!vieja.logros.isEmpty()) throw new IllegalStateException("La casilla " + indice + " ya tiene dueño");
		casillas.set(indice, new Casilla(objetivo, objetivo.esReto() ? objetivo.reto().dificultad() : vieja.dificultad));
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
