package dev.venatio.juego;

import dev.venatio.carton.Carton;
import dev.venatio.carton.Objetivo;
import dev.venatio.carton.Reemplazos;
import dev.venatio.red.PaquetesVariantes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Variante "Draft": mientras se genera el mundo, con el cartón a la vista de todos, cada equipo veta 1 o 2 casillas
 * a la vez que los demás (cualquier miembro decide) y cada vetada se cambia por otra del pool. Dura {@link #SEGUNDOS}
 * segundos o hasta que todos los equipos vetaron; quien no vetó a tiempo se queda sin vetar.
 */
final class Draft {
	static final int SEGUNDOS = 45;

	/** Un veto hecho: quién, qué casilla y qué pedía antes y después. */
	record Veto(ColorEquipo equipo, String jugador, int casilla, ItemStack antes, ItemStack despues) {}

	private final Partida partida;
	private final int vetosPorEquipo;
	private final List<Veto> vetos = new ArrayList<>();
	/** Lo que se quitó del cartón: no vuelve a salir como reemplazo. */
	private final Set<String> quitados = new HashSet<>();
	private int ticks;
	private boolean terminado;

	Draft(Partida partida, int vetosPorEquipo) {
		this.partida = partida;
		this.vetosPorEquipo = Math.clamp(vetosPorEquipo, 1, 2);
	}

	boolean enCurso() {
		return !terminado;
	}

	int segundosRestantes() {
		return terminado ? 0 : Math.max(0, SEGUNDOS - ticks / 20);
	}

	/** Avanza el reloj; true si el draft terminó en este tick. */
	boolean tick() {
		if (terminado) return false;
		if (ticks == 0) Juego.INSTANCIA.difundir(Component.translatable("venatio.draft.aviso", cuantas(vetosPorEquipo), SEGUNDOS));
		ticks++;
		if (ticks < SEGUNDOS * 20 && !todosVetaron()) return false;
		terminar();
		return true;
	}

	/** Lo corta (por si la cuenta atrás tuviera que empezar antes). */
	void terminar() {
		if (terminado) return;
		terminado = true;
		Juego.INSTANCIA.difundir(Component.translatable(todosVetaron() ? "venatio.draft.fin_todos" : "venatio.draft.fin"));
	}

	private boolean todosVetaron() {
		for (ColorEquipo c : partida.equipos) if (usados(c) < vetosPorEquipo) return false;
		return true;
	}

	private int usados(ColorEquipo c) {
		int n = 0;
		for (Veto v : vetos) if (v.equipo() == c) n++;
		return n;
	}

	/** Un jugador veta una casilla para su equipo. Devuelve el error, o null si se vetó. */
	Component vetar(ServerPlayer p, int indice) {
		if (terminado || partida.fase() != Partida.Fase.PREPARANDO) return Component.translatable("venatio.draft.error.no_draft");
		ColorEquipo equipo = partida.equipoDe(p);
		if (equipo == null) return Component.translatable("venatio.draft.error.no_participa");
		if (usados(equipo) >= vetosPorEquipo) return Component.translatable("venatio.draft.error.sin_vetos");
		Carton carton = partida.carton;
		if (indice < 0 || indice >= carton.casillas.size()) return Component.translatable("venatio.draft.error.no_draft");
		for (Veto v : vetos) if (v.casilla() == indice) return Component.translatable("venatio.draft.error.ya_vetada");

		Objetivo viejo = carton.casillas.get(indice).objetivo;
		// Reproducible: la misma semilla y los mismos vetos dan el mismo cartón.
		Random rnd = new Random(partida.semilla * 0x9E3779B97F4A7C15L + 31L * (vetos.size() + 1) + indice);
		Objetivo nuevo = Reemplazos.para(carton, indice, Juego.INSTANCIA.poolDePartida(), partida.cfg.reglas(), quitados, rnd);
		if (nuevo == null) return Component.translatable("venatio.draft.error.sin_reemplazo");
		ItemStack antes = carton.casillas.get(indice).icono.copy();
		quitados.add(viejo.base());
		carton.reemplazar(indice, nuevo);
		ItemStack despues = carton.casillas.get(indice).icono.copy();
		vetos.add(new Veto(equipo, p.getGameProfile().name(), indice, antes, despues));
		Juego.INSTANCIA.difundir(Component.translatable("venatio.draft.vetada", p.getDisplayName(), equipo.nombre(),
			antes.getHoverName(), despues.getHoverName()));
		Juego.INSTANCIA.enviarEstadoATodos();
		return null;
	}

	PaquetesVariantes.Draft red() {
		List<PaquetesVariantes.Veto> lista = new ArrayList<>();
		for (Veto v : vetos) lista.add(new PaquetesVariantes.Veto(v.equipo().ordinal(), v.jugador(), v.casilla(), v.antes(), v.despues()));
		return new PaquetesVariantes.Draft(!terminado, segundosRestantes(), vetosPorEquipo, lista);
	}

	static Component cuantas(int n) {
		return n == 1 ? Component.translatable("venatio.draft.una") : Component.translatable("venatio.draft.varias", n);
	}
}
