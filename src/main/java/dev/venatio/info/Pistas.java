package dev.venatio.info;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.venatio.Venatio;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pistas escritas a mano (data/venatio/pistas/clasico.json): una o dos líneas por item del pool y, opcionalmente,
 * biomas, estructuras y mobs donde buscarlo, que la ficha muestra con botón "Guíame".
 * La clave es el id del item, o "#tag" para las casillas de "cualquier X".
 */
public final class Pistas {
	public static final Identifier RECURSO = Identifier.fromNamespaceAndPath("venatio", "pistas/clasico.json");

	public record Pista(String texto, List<Identifier> biomas, List<Identifier> estructuras, List<Identifier> mobs) {
		static final Pista VACIA = new Pista("", List.of(), List.of(), List.of());
	}

	private final Map<String, Pista> porClave;

	private Pistas(Map<String, Pista> porClave) {
		this.porClave = porClave;
	}

	public static Pistas cargar(ResourceManager recursos) {
		Map<String, Pista> mapa = new HashMap<>();
		var recurso = recursos.getResource(RECURSO);
		if (recurso.isEmpty()) {
			Venatio.LOG.warn("No se encontró {}; las fichas van sin pistas", RECURSO);
			return new Pistas(mapa);
		}
		try (Reader in = recurso.get().openAsReader()) {
			JsonObject raiz = JsonParser.parseReader(in).getAsJsonObject().getAsJsonObject("pistas");
			for (var e : raiz.entrySet()) {
				JsonObject o = e.getValue().getAsJsonObject();
				String texto = o.has("texto") ? o.get("texto").getAsString() : "";
				mapa.put(e.getKey(), new Pista(texto, ids(o, "biomas"), ids(o, "estructuras"), ids(o, "mobs")));
			}
		} catch (Exception ex) {
			Venatio.LOG.error("No se pudo leer {}", RECURSO, ex);
		}
		return new Pistas(mapa);
	}

	private static List<Identifier> ids(JsonObject o, String campo) {
		if (!o.has(campo)) return List.of();
		List<Identifier> lista = new ArrayList<>();
		for (JsonElement e : o.getAsJsonArray(campo)) lista.add(Identifier.parse(e.getAsString()));
		return List.copyOf(lista);
	}

	/** Pista de un item, o de su grupo ("#minecraft:signs") si el item no tiene propia. */
	public Pista de(Identifier item, Identifier grupo) {
		Pista p = porClave.get(item.toString());
		if (p == null && grupo != null) p = porClave.get("#" + grupo);
		return p == null ? Pista.VACIA : p;
	}

	public int cantidad() {
		return porClave.size();
	}
}
