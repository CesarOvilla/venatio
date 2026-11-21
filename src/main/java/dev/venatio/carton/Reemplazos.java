package dev.venatio.carton;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Casilla nueva para una vetada en el draft: del mismo tipo (item o logro), del mismo nivel si se puede, de una categoría
 * que no esté ya en el cartón y sin repetir nada que se haya vetado. Si la vetada pedía cantidad, la nueva también.
 */
public final class Reemplazos {
	private Reemplazos() {}

	/**
	 * @param vetados entradas del pool (o "reto:…") que ya se quitaron del cartón en el draft: no vuelven
	 * @return el objetivo nuevo, o null si el pool ya no tiene nada que ofrecer
	 */
	public static Objetivo para(Carton carton, int indice, Pool pool, Reglas reglas, Set<String> vetados, Random rnd) {
		Carton.Casilla vieja = carton.casillas.get(indice);
		Set<String> usadas = new HashSet<>(vetados);
		for (Carton.Casilla c : carton.casillas) usadas.add(c.objetivo.base());

		if (vieja.objetivo.esReto()) {
			Objetivo reto = reto(vieja.dificultad, usadas, rnd);
			if (reto != null) return reto;
		}
		List<Map<Integer, String>> libres = new ArrayList<>();
		for (Map<Integer, String> cat : pool.categorias()) {
			boolean enUso = false;
			for (String e : cat.values()) enUso |= usadas.contains(e);
			if (!enUso) libres.add(cat);
		}
		if (libres.isEmpty()) return vieja.objetivo.esReto() ? null : reto(vieja.dificultad, usadas, rnd);
		int nivel = Math.max(1, vieja.dificultad);
		Objetivo nuevo = Objetivo.de(Pool.deNivel(libres.get(rnd.nextInt(libres.size())), nivel));
		if (vieja.objetivo.cantidad() > 1 && reglas.cantidades() && Cantidades.apilable(nuevo.itemFicha())) {
			nuevo = nuevo.conCantidad(Cantidades.para(nuevo.itemFicha(), nivel, reglas.dificultad(), rnd));
		}
		return nuevo;
	}

	/** Un logro que no esté en uso, del mismo nivel si hay. */
	private static Objetivo reto(int nivel, Set<String> usadas, Random rnd) {
		List<Reto> mismoNivel = new ArrayList<>(), otros = new ArrayList<>();
		for (Reto r : Retos.TODOS) {
			if (usadas.contains(Objetivo.PREFIJO_RETO + r.id())) continue;
			(r.dificultad() == nivel ? mismoNivel : otros).add(r);
		}
		List<Reto> lista = mismoNivel.isEmpty() ? otros : mismoNivel;
		return lista.isEmpty() ? null : Objetivo.de(lista.get(rnd.nextInt(lista.size())));
	}
}
