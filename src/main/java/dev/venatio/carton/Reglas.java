package dev.venatio.carton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Cómo se arma el cartón: dificultad, si lleva casillas de logro y si hay casillas que piden varias unidades.
 * Las mismas reglas y la misma semilla dan el mismo cartón.
 */
public record Reglas(Dificultad dificultad, boolean logros, boolean cantidades) {
	/** Como MCBingo: solo items, una unidad, cada nivel con la misma probabilidad. */
	public static final Reglas CLASICAS = new Reglas(Dificultad.NORMAL, false, false);

	/** Parte de la secuencia del Rastreo que son logros cuando la opción está activa. */
	private static final float LOGROS_RASTREO = 0.28f;

	/**
	 * Pool para el modo Rastreo, que recorre todas las entradas una por una: con cantidades, ~30 % de los items apilables
	 * pasan a pedir varias unidades ("16*minecraft:oak_log"); con logros, se agregan logros ("reto:dormir") hasta ser ~28 %.
	 */
	public Pool paraRastreo(Pool pool, long semilla) {
		if (!logros && !cantidades) return pool;
		Random rnd = new Random(semilla * 0x9E3779B97F4A7C15L + 0x2545F4914F6CDD1DL);
		Map<String, Integer> nivelDe = new LinkedHashMap<>();
		for (Map<Integer, String> cat : pool.categorias()) {
			for (Map.Entry<Integer, String> e : cat.entrySet()) nivelDe.merge(e.getValue(), e.getKey(), Math::min);
		}
		Map<String, String> texto = new LinkedHashMap<>();
		for (String e : nivelDe.keySet()) texto.put(e, e);
		if (cantidades) {
			List<String> apilables = new ArrayList<>();
			for (String e : nivelDe.keySet()) if (Cantidades.apilable(Objetivo.de(e).itemFicha())) apilables.add(e);
			Collections.shuffle(apilables, rnd);
			int n = Math.min(apilables.size(), Math.round(nivelDe.size() * Cantidades.PROPORCION));
			for (String e : apilables.subList(0, n)) {
				Objetivo o = Objetivo.de(e);
				texto.put(e, o.conCantidad(Cantidades.para(o.itemFicha(), nivelDe.get(e), dificultad, rnd)).texto());
			}
		}
		List<Map<Integer, String>> cats = new ArrayList<>();
		texto.forEach((e, t) -> cats.add(Map.of(nivelDe.get(e), t)));
		if (logros) {
			int n = Math.round(nivelDe.size() * LOGROS_RASTREO / (1 - LOGROS_RASTREO));
			for (Reto r : Retos.elegir(n, rnd, dificultad)) cats.add(Map.of(r.dificultad(), Objetivo.de(r).texto()));
		}
		return new Pool(List.copyOf(cats));
	}
}
