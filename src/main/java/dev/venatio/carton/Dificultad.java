package dev.venatio.carton;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Dificultad del cartón: cuánto pesa cada nivel (1 fácil, 2 media, 3 difícil) al elegir la casilla de cada categoría.
 * Normal es el reparto de MCBingo (cada nivel con la misma probabilidad); Mixta trae siempre la misma cantidad de cada nivel.
 */
public enum Dificultad {
	FACIL(60, 30, 10),
	NORMAL(1, 1, 1),
	DIFICIL(10, 30, 60),
	MIXTA(1, 1, 1);

	private final int[] pesos;

	Dificultad(int... pesos) {
		this.pesos = pesos;
	}

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	public MutableComponent nombre() {
		return Component.translatable("venatio.dificultad." + id());
	}

	public static Dificultad porId(String id) {
		for (Dificultad d : values()) if (d.id().equals(id)) return d;
		return NORMAL;
	}

	/** Un nivel al azar según los pesos (1, 2 o 3). */
	public int elegir(Random rnd) {
		int total = pesos[0] + pesos[1] + pesos[2];
		int r = rnd.nextInt(total);
		for (int i = 0; i < 3; i++) {
			if (r < pesos[i]) return i + 1;
			r -= pesos[i];
		}
		return 3;
	}

	/**
	 * Niveles para n casillas. Normal conserva la tirada de siempre (así una semilla da el mismo cartón que antes);
	 * Mixta reparte por tercios exactos y los baraja.
	 */
	List<Integer> niveles(int n, Random rnd) {
		List<Integer> lista = new ArrayList<>(n);
		if (this == MIXTA) {
			for (int i = 0; i < n; i++) lista.add(1 + i % 3);
			Collections.shuffle(lista, rnd);
		} else if (this == NORMAL) {
			for (int i = 0; i < n; i++) lista.add(1 + rnd.nextInt(3));
		} else {
			for (int i = 0; i < n; i++) lista.add(elegir(rnd));
		}
		return lista;
	}
}
