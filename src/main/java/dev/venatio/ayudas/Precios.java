package dev.venatio.ayudas;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.venatio.Venatio;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * Precios, enfriamientos y parámetros de las ayudas. Se leen de config/venatio/precios.json del servidor
 * si existe; si no, de data/venatio/ayudas/precios.json dentro del mod.
 */
public final class Precios {
	private static final String RECURSO = "/data/venatio/ayudas/precios.json";

	/** Lo que cuesta una ayuda; enfriamiento en segundos. Sin item o con cantidad 0 es gratis. */
	public record Precio(Item item, int cantidad, int enfriamiento) {
		public boolean gratis() {
			return cantidad <= 0 || item == Items.AIR;
		}
	}

	public final boolean activas;
	private final Map<TipoAyuda, Precio> precios;
	public final int minimapaSegundos, minimapaRadio;
	public final int guiaDuracion, guiaLlegada, guiaDesplazamiento, radioBusqueda;
	public final int marcaDuracion, marcaMaximo, marcaAlcance;

	private Precios(JsonObject j) {
		activas = GsonHelper.getAsBoolean(j, "activas", true);
		precios = new EnumMap<>(TipoAyuda.class);
		for (TipoAyuda t : TipoAyuda.values()) {
			JsonObject o = j.has(t.id) ? GsonHelper.getAsJsonObject(j, t.id) : new JsonObject();
			Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(GsonHelper.getAsString(o, "item", "minecraft:air")));
			precios.put(t, new Precio(item, Math.max(0, GsonHelper.getAsInt(o, "cantidad", 0)),
				Math.max(0, GsonHelper.getAsInt(o, "enfriamiento", 0))));
		}
		JsonObject mini = j.has("minimapa") ? GsonHelper.getAsJsonObject(j, "minimapa") : new JsonObject();
		minimapaSegundos = Math.max(1, GsonHelper.getAsInt(mini, "segundos", 180));
		minimapaRadio = Math.max(8, GsonHelper.getAsInt(mini, "radio", 64));
		JsonObject guia = j.has("guia") ? GsonHelper.getAsJsonObject(j, "guia") : new JsonObject();
		guiaDuracion = Math.max(10, GsonHelper.getAsInt(guia, "duracion", 600));
		guiaLlegada = Math.max(4, GsonHelper.getAsInt(guia, "llegada", 48));
		guiaDesplazamiento = Math.max(0, GsonHelper.getAsInt(guia, "desplazamiento", 48));
		radioBusqueda = Math.clamp(GsonHelper.getAsInt(guia, "radio_busqueda", 4096), 256, 6400);
		JsonObject marca = j.has("marca") ? GsonHelper.getAsJsonObject(j, "marca") : new JsonObject();
		marcaDuracion = Math.max(5, GsonHelper.getAsInt(marca, "duracion", 120));
		marcaMaximo = Math.max(1, GsonHelper.getAsInt(marca, "maximo", 3));
		marcaAlcance = Math.clamp(GsonHelper.getAsInt(marca, "alcance", 128), 8, 256);
	}

	public Precio de(TipoAyuda t) {
		return precios.get(t);
	}

	public static Precios cargar() {
		Path externo = FabricLoader.getInstance().getConfigDir().resolve("venatio").resolve("precios.json");
		if (Files.isRegularFile(externo)) {
			try (Reader r = Files.newBufferedReader(externo, StandardCharsets.UTF_8)) {
				Venatio.LOG.info("Precios de ayudas leídos de {}", externo);
				return new Precios(JsonParser.parseReader(r).getAsJsonObject());
			} catch (Exception e) {
				Venatio.LOG.error("{} no es válido; se usan los precios de fábrica", externo, e);
			}
		}
		try (var in = Precios.class.getResourceAsStream(RECURSO)) {
			if (in == null) throw new IllegalStateException("No existe " + RECURSO);
			return new Precios(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
		} catch (Exception e) {
			throw new IllegalStateException("No se pudieron leer los precios de las ayudas", e);
		}
	}
}
