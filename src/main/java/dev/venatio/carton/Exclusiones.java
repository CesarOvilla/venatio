package dev.venatio.carton;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.venatio.Venatio;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * Entradas del pool que el anfitrión quitó (/venatio pool quitar, o clic derecho en "Items posibles").
 * Se guardan en config/venatio/pool.json y valen para todas las partidas hasta que se vuelvan a poner.
 */
public final class Exclusiones {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Set<String> excluidas = new TreeSet<>();

	private Exclusiones() {}

	private static Path archivo() {
		return FabricLoader.getInstance().getConfigDir().resolve("venatio").resolve("pool.json");
	}

	public static synchronized void cargar() {
		excluidas.clear();
		Path ruta = archivo();
		if (!Files.exists(ruta)) return;
		try {
			JsonObject raiz = JsonParser.parseString(Files.readString(ruta, StandardCharsets.UTF_8)).getAsJsonObject();
			if (raiz.has("excluidos")) for (JsonElement e : raiz.getAsJsonArray("excluidos")) excluidas.add(e.getAsString());
		} catch (Exception e) {
			Venatio.LOG.error("No se pudo leer {}; se juega con el pool completo", ruta, e);
		}
	}

	/** Copia de las entradas quitadas ("minecraft:diamond", "#minecraft:signs"). */
	public static synchronized Set<String> lista() {
		return Collections.unmodifiableSet(new TreeSet<>(excluidas));
	}

	public static synchronized boolean excluida(String entrada) {
		return excluidas.contains(entrada);
	}

	/** Quita (excluir true) o vuelve a poner una entrada. Devuelve false si ya estaba así. */
	public static synchronized boolean cambiar(String entrada, boolean excluir) {
		boolean cambio = excluir ? excluidas.add(entrada) : excluidas.remove(entrada);
		if (cambio) guardar();
		return cambio;
	}

	private static void guardar() {
		Path ruta = archivo();
		JsonObject raiz = new JsonObject();
		raiz.addProperty("descripcion", "Items que el anfitrión quitó del pool de Venatio (/venatio pool quitar|poner|lista).");
		JsonArray lista = new JsonArray();
		for (String e : excluidas) lista.add(e);
		raiz.add("excluidos", lista);
		try {
			Files.createDirectories(ruta.getParent());
			Files.writeString(ruta, GSON.toJson(raiz), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Venatio.LOG.error("No se pudo guardar {}", ruta, e);
		}
	}
}
