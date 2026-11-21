package dev.venatio.juego;

import dev.venatio.carton.Objetivo;
import dev.venatio.carton.Pool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Modo Rastreo (estilo ItemFinder): una lista de items al azar (misma semilla, misma lista) que se busca de uno en uno.
 * Compartido: todos buscan el mismo item y el que lo encuentra primero hace avanzar a todos.
 * Independiente: cada equipo recorre la misma lista a su ritmo.
 */
public final class Rastreo {
	/** Segundos entre saltos de item (por equipo si es independiente, para todos si es compartido). */
	public static final int ESPERA_SALTO = 60;

	private final List<Objetivo> secuencia;
	private final boolean independiente;
	private int indiceComun;
	private final Map<ColorEquipo, Integer> indices = new EnumMap<>(ColorEquipo.class);
	private final Map<ColorEquipo, Integer> ultimoSalto = new EnumMap<>(ColorEquipo.class);
	private int ultimoSaltoComun = -ESPERA_SALTO;
	/** Segundo de partida en que empezó el item compartido actual (Switcheroo cuenta desde aquí). */
	private int inicioComun;

	private Rastreo(List<Objetivo> secuencia, boolean independiente) {
		this.secuencia = secuencia;
		this.independiente = independiente;
	}

	/** Todos los items distintos del pool, en orden aleatorio según la semilla. */
	public static Rastreo crear(Pool pool, long semilla, boolean independiente) {
		Set<String> textos = new LinkedHashSet<>();
		for (Map<Integer, String> cat : pool.categorias()) textos.addAll(cat.values());
		List<Objetivo> lista = new ArrayList<>();
		for (String t : textos) lista.add(Objetivo.de(t));
		Collections.shuffle(lista, new Random(semilla ^ 0x2545F4914F6CDD1DL));
		return new Rastreo(List.copyOf(lista), independiente);
	}

	public boolean independiente() {
		return independiente;
	}

	public Objetivo actual(ColorEquipo equipo) {
		int i = independiente && equipo != null ? indices.getOrDefault(equipo, 0) : indiceComun;
		return secuencia.get(i % secuencia.size());
	}

	/** El equipo encontró su item (o lo saltó, o se le acabó el tiempo): pasa al siguiente. En modo compartido avanzan todos. */
	void avanzar(ColorEquipo equipo, int segundo) {
		if (independiente) indices.merge(equipo, 1, Integer::sum);
		else {
			indiceComun++;
			inicioComun = segundo;
		}
	}

	/** Segundo de partida en que empezó el item compartido actual. */
	public int inicioComun() {
		return inicioComun;
	}

	/** Para las pruebas: hace como si el item compartido actual llevara esos segundos más en juego. */
	public void adelantar(int segundos) {
		inicioComun -= segundos;
	}

	/** true si algún item de la lista pide este stack (los eventos no lo regalan). */
	public boolean pide(net.minecraft.world.item.ItemStack stack) {
		for (Objetivo o : secuencia) if (o.cumple(stack)) return true;
		return false;
	}

	/** Segundos que faltan para poder saltar; 0 si ya se puede. */
	int esperaParaSaltar(ColorEquipo equipo, int segundoActual) {
		int ultimo = independiente ? ultimoSalto.getOrDefault(equipo, -ESPERA_SALTO) : ultimoSaltoComun;
		return Math.max(0, ultimo + ESPERA_SALTO - segundoActual);
	}

	void registrarSalto(ColorEquipo equipo, int segundoActual) {
		if (independiente) ultimoSalto.put(equipo, segundoActual);
		else ultimoSaltoComun = segundoActual;
	}
}
