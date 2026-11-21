package dev.venatio.carton;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Pool de casillas: categorías, cada una con un item por dificultad (1 fácil, 2 media, 3 difícil). */
public record Pool(List<Map<Integer, String>> categorias) {

	public static Pool cargar(String recurso) {
		try (var in = Pool.class.getResourceAsStream(recurso)) {
			if (in == null) throw new IllegalStateException("No existe " + recurso);
			JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			List<Map<Integer, String>> cats = new ArrayList<>();
			for (JsonElement e : json.getAsJsonArray("categorias")) {
				Map<Integer, String> porDificultad = new TreeMap<>();
				for (var item : e.getAsJsonObject().getAsJsonObject("items").entrySet()) {
					porDificultad.put(Integer.parseInt(item.getKey()), item.getValue().getAsString());
				}
				cats.add(porDificultad);
			}
			return new Pool(List.copyOf(cats));
		} catch (Exception ex) {
			throw new IllegalStateException("No se pudo leer el pool " + recurso, ex);
		}
	}

	/** Entradas distintas del pool, en orden ("minecraft:cake", "#minecraft:signs"). */
	public List<String> entradas() {
		Set<String> lista = new LinkedHashSet<>();
		for (Map<Integer, String> cat : categorias) lista.addAll(cat.values());
		return List.copyOf(lista);
	}

	/** El pool sin esas entradas. Una categoría que se queda sin ninguna se quita entera. */
	public Pool sin(Set<String> excluidas) {
		if (excluidas.isEmpty()) return this;
		List<Map<Integer, String>> cats = new ArrayList<>();
		for (Map<Integer, String> cat : categorias) {
			Map<Integer, String> quedan = new TreeMap<>(cat);
			quedan.values().removeIf(excluidas::contains);
			if (!quedan.isEmpty()) cats.add(quedan);
		}
		return new Pool(List.copyOf(cats));
	}

	/** La entrada de ese nivel; si se excluyó, la del nivel más cercano (a igual distancia, la más fácil). */
	static String deNivel(Map<Integer, String> cat, int nivel) {
		for (int d = 0; d < 3; d++) {
			String s = cat.get(nivel - d);
			if (s != null) return s;
			s = cat.get(nivel + d);
			if (s != null) return s;
		}
		return cat.values().iterator().next();
	}
}
