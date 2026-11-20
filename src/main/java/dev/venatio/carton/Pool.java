package dev.venatio.carton;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
}
