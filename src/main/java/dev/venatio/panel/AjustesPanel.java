package dev.venatio.panel;

import dev.venatio.carton.Dificultad;
import dev.venatio.juego.Ajustes;
import dev.venatio.juego.Configuracion;
import dev.venatio.red.PaquetesStats;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * Ajustes del anfitrión que no son sí/no (dificultad, minutos de Switcheroo, vetos del draft…). El panel los muestra
 * solos como botones que rotan valores: para agregar uno basta con registrarlo aquí.
 *
 * Textos: el nombre usa la clave {@code claveNombre} y cada valor {@code prefijoValor + valor}.
 */
public final class AjustesPanel {
	/** Pestaña del panel donde aparece. */
	public static final String PARTIDA = "partida", VARIANTES = "variantes";

	public record Ajuste(String id, String grupo, String claveNombre, String prefijoValor, List<String> valores,
						 Function<Configuracion, String> actual, Function<String, Component> aplicar) {}

	private static final List<Ajuste> TODOS = new ArrayList<>();

	static {
		agregar(new Ajuste("dificultad", PARTIDA, "venatio.ajuste.dificultad", "venatio.dificultad.",
			Arrays.stream(Dificultad.values()).map(Dificultad::id).toList(),
			c -> c.dificultad.id(),
			v -> Ajustes.dificultad(Dificultad.porId(v))));
	}

	private AjustesPanel() {}

	public static void agregar(Ajuste a) {
		TODOS.add(a);
	}

	static List<PaquetesStats.AjusteRed> paraRed(Configuracion c) {
		List<PaquetesStats.AjusteRed> lista = new ArrayList<>();
		for (Ajuste a : TODOS) lista.add(new PaquetesStats.AjusteRed(a.id, a.grupo, a.actual.apply(c), a.valores, a.claveNombre, a.prefijoValor));
		return lista;
	}

	/** "id=valor" desde el panel. Devuelve el error a mostrar o null si se aplicó. */
	static Component aplicar(String texto) {
		int igual = texto.indexOf('=');
		if (igual < 0) return Component.translatable("venatio.stats.panel.invalido");
		String id = texto.substring(0, igual), valor = texto.substring(igual + 1);
		for (Ajuste a : TODOS) {
			if (a.id.equals(id) && a.valores.contains(valor)) return a.aplicar.apply(valor);
		}
		return Component.translatable("venatio.stats.panel.invalido");
	}
}
