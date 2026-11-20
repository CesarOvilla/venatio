package dev.venatio.info;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.venatio.Venatio;
import dev.venatio.red.PaquetesFicha.Fuente;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.trading.TradeSet;
import net.minecraft.world.item.trading.TradeSets;
import net.minecraft.world.item.trading.VillagerTrade;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSpawnOverride;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * De dónde sale cada item, calculado al arrancar el servidor:
 * <ul>
 *   <li>loot tables (mobs, cofres y barriles de estructuras, bloques, pesca, trueque con piglins, arqueología,
 *       vasijas, dispensadores y generadores de las cámaras de desafío, regalos y lo que sueltan algunos mobs);</li>
 *   <li>trueques de aldeanos y del comerciante nómada (datos de trade_set);</li>
 *   <li>para cada mob, los biomas donde aparece (natural_mob_spawns de cada bioma) y las estructuras que lo generan.</li>
 * </ul>
 * Las loot tables se recorren serializadas a JSON con su propio codec: así no hace falta tocar campos privados y
 * cubre también las tablas de datapacks o modificadas por otros mods.
 */
public final class IndiceFuentes {
	private final Map<Item, List<Fuente>> porItem;
	private final Map<EntityType<?>, Set<Identifier>> biomasDe;
	private final Map<EntityType<?>, Set<Identifier>> estructurasDe;
	private final Set<Identifier> estructurasExistentes;

	private IndiceFuentes(Map<Item, List<Fuente>> porItem, Map<EntityType<?>, Set<Identifier>> biomasDe,
						  Map<EntityType<?>, Set<Identifier>> estructurasDe, Set<Identifier> estructurasExistentes) {
		this.porItem = porItem;
		this.biomasDe = biomasDe;
		this.estructurasDe = estructurasDe;
		this.estructurasExistentes = estructurasExistentes;
	}

	public List<Fuente> de(Item item) {
		return porItem.getOrDefault(item, List.of());
	}

	public int cantidadItems() {
		return porItem.size();
	}

	/** Fuente de tipo MOB con sus biomas y estructuras, para las pistas escritas a mano. */
	public Fuente mob(EntityType<?> tipo, int modo) {
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(tipo);
		return new Fuente(Fuente.MOB, id, modo, Optional.of(id),
			List.copyOf(estructurasDe.getOrDefault(tipo, Set.of())), List.copyOf(biomasDe.getOrDefault(tipo, Set.of())));
	}

	/** Fuente de tipo ESTRUCTURA a partir del id de una estructura concreta (minecraft:village_plains…). */
	public Fuente estructura(Identifier estructura, int modo) {
		Optional<Identifier> guia = estructurasExistentes.contains(estructura) ? Optional.of(estructura) : Optional.empty();
		return new Fuente(Fuente.ESTRUCTURA, grupoDeEstructura(estructura), modo, guia, List.of(), List.of());
	}

	/** Las variantes de una estructura se muestran con un solo nombre: village_desert → village. */
	static Identifier grupoDeEstructura(Identifier estructura) {
		String p = estructura.getPath();
		for (String prefijo : new String[]{"village", "ruined_portal", "shipwreck", "abandoned_camp", "mineshaft", "ocean_ruin"}) {
			if (p.startsWith(prefijo)) return estructura.withPath(prefijo);
		}
		return estructura;
	}

	public static IndiceFuentes construir(MinecraftServer server, IndiceRecetas recetas) {
		long inicio = System.currentTimeMillis();
		Constructor c = new Constructor(server, recetas);
		c.spawns();
		c.lootTables();
		c.trueques();
		IndiceFuentes indice = c.terminar();
		Venatio.LOG.info("Fichas: fuentes de {} items en {} ms ({} tablas sin leer)", indice.cantidadItems(),
			System.currentTimeMillis() - inicio, c.tablasConError);
		return indice;
	}

	// ------------------------------------------------------------------------------------------------ construcción

	/** Fuente antes de pegarle biomas y estructuras. */
	private record Proto(int tipo, Identifier id, int modo, Optional<Identifier> guia) {
		String clave() {
			return tipo == Fuente.ALDEANO ? tipo + "|" + id : tipo + "|" + id + "|" + modo;
		}
	}

	/** Estructura de una loot table: grupo (para el nombre) y estructura concreta a la que guía. */
	private record Lugar(String prefijo, String grupo, String guia) {}

	private static final List<Lugar> LUGARES = List.of(
		new Lugar("trial_chamber", "trial_chambers", "trial_chambers"),
		new Lugar("abandoned_camp", "abandoned_camp", "abandoned_camp_forest"),
		new Lugar("abandoned_mineshaft", "mineshaft", "mineshaft"),
		new Lugar("ancient_city", "ancient_city", "ancient_city"),
		new Lugar("bastion", "bastion_remnant", "bastion_remnant"),
		new Lugar("buried_treasure", "buried_treasure", "buried_treasure"),
		new Lugar("desert_pyramid", "desert_pyramid", "desert_pyramid"),
		new Lugar("desert_well", "desert_well", null),
		new Lugar("end_city", "end_city", "end_city"),
		new Lugar("igloo", "igloo", "igloo"),
		new Lugar("jungle_temple", "jungle_pyramid", "jungle_pyramid"),
		new Lugar("nether_bridge", "fortress", "fortress"),
		new Lugar("pillager_outpost", "pillager_outpost", "pillager_outpost"),
		new Lugar("ruined_portal", "ruined_portal", "ruined_portal"),
		new Lugar("shipwreck", "shipwreck", "shipwreck"),
		new Lugar("simple_dungeon", "dungeon", null),
		new Lugar("stronghold", "stronghold", "stronghold"),
		new Lugar("underwater_ruin", "ocean_ruin", "ocean_ruin_cold"),
		new Lugar("ocean_ruin_warm", "ocean_ruin", "ocean_ruin_warm"),
		new Lugar("ocean_ruin", "ocean_ruin", "ocean_ruin_cold"),
		new Lugar("trail_ruins", "trail_ruins", "trail_ruins"),
		new Lugar("woodland_mansion", "mansion", "mansion"));

	private static final int[] ORDEN_TIPOS = new int[7];

	static {
		int[] orden = {Fuente.BIOMA, Fuente.MOB, Fuente.BLOQUE, Fuente.ESTRUCTURA, Fuente.ALDEANO, Fuente.PESCA, Fuente.TRUEQUE};
		for (int i = 0; i < orden.length; i++) ORDEN_TIPOS[orden[i]] = i;
	}

	static int ordenTipo(Fuente f) {
		return f.tipo() >= 0 && f.tipo() < ORDEN_TIPOS.length ? ORDEN_TIPOS[f.tipo()] : 99;
	}

	private static final class Constructor {
		final MinecraftServer server;
		final IndiceRecetas recetas;
		final HolderLookup.Provider registros;
		final RegistryOps<JsonElement> ops;
		final Map<Item, LinkedHashMap<String, Proto>> protos = new HashMap<>();
		final Map<EntityType<?>, Set<Identifier>> biomasDe = new HashMap<>();
		final Map<EntityType<?>, Set<Identifier>> estructurasDe = new HashMap<>();
		final Set<Identifier> estructuras = new HashSet<>();
		final Map<Identifier, JsonObject> tablas = new HashMap<>();
		final Map<Identifier, Set<Item>> itemsPorTabla = new HashMap<>();
		final Set<Identifier> enCurso = new HashSet<>();
		final Map<Item, Optional<Item>> fundidos = new HashMap<>();
		int tablasConError;

		Constructor(MinecraftServer server, IndiceRecetas recetas) {
			this.server = server;
			this.recetas = recetas;
			this.registros = server.reloadableRegistries().lookup();
			this.ops = registros.createSerializationContext(JsonOps.INSTANCE);
		}

		// ---------------------------------------------------------------- dónde aparece cada mob

		void spawns() {
			server.registryAccess().lookupOrThrow(Registries.BIOME).listElements().forEach(bioma -> {
				MobSpawnSettings s = bioma.value().getAttributes().applyModifier(EnvironmentAttributes.NATURAL_MOB_SPAWNS, MobSpawnSettings.EMPTY);
				for (MobCategory cat : MobCategory.values()) {
					for (Weighted<MobSpawnSettings.SpawnerData> w : s.getMobsToSpawn(cat).unwrap()) {
						biomasDe.computeIfAbsent(w.value().type(), k -> new LinkedHashSet<>()).add(bioma.key().identifier());
					}
				}
			});
			server.registryAccess().lookupOrThrow(Registries.STRUCTURE).listElements().forEach(ref -> {
				Identifier id = ref.key().identifier();
				estructuras.add(id);
				Structure estructura = ref.value();
				for (StructureSpawnOverride o : estructura.spawnOverrides().values()) {
					for (Weighted<MobSpawnSettings.SpawnerData> w : o.spawns().unwrap()) {
						estructurasDe.computeIfAbsent(w.value().type(), k -> new LinkedHashSet<>()).add(grupoDeEstructura(id));
					}
				}
			});
		}

		// ---------------------------------------------------------------- loot tables

		void lootTables() {
			HolderLookup.RegistryLookup<LootTable> registro = registros.lookupOrThrow(Registries.LOOT_TABLE);
			registro.listElements().forEach(ref -> {
				var json = LootTable.DIRECT_CODEC.encodeStart(ops, ref.value()).result();
				if (json.isPresent() && json.get().isJsonObject()) tablas.put(ref.key().identifier(), json.get().getAsJsonObject());
				else tablasConError++;
			});
			for (Identifier tabla : tablas.keySet()) {
				Proto proto = clasificar(tabla);
				if (proto == null) continue;
				for (Item item : itemsDe(tabla)) {
					if (proto.tipo == Fuente.BLOQUE && !bloqueInformativo(proto.id, item)) continue;
					agregar(item, proto);
				}
			}
		}

		/** Items que puede soltar una tabla, incluyendo las tablas anidadas. */
		Set<Item> itemsDe(Identifier tabla) {
			Set<Item> hecho = itemsPorTabla.get(tabla);
			if (hecho != null) return hecho;
			if (!enCurso.add(tabla)) return Set.of();
			Set<Item> items = new LinkedHashSet<>();
			JsonObject json = tablas.get(tabla);
			if (json != null) recorrerTabla(json, List.of(), items);
			enCurso.remove(tabla);
			itemsPorTabla.put(tabla, items);
			return items;
		}

		void recorrerTabla(JsonObject tabla, List<String> funciones, Set<Item> salida) {
			List<String> f = mas(funciones, tabla.get("modifier"));
			for (JsonElement pool : arreglo(tabla, "pools")) {
				if (!pool.isJsonObject()) continue;
				List<String> fp = mas(f, pool.getAsJsonObject().get("modifier"));
				for (JsonElement entrada : arreglo(pool.getAsJsonObject(), "entries")) {
					if (entrada.isJsonObject()) recorrerEntrada(entrada.getAsJsonObject(), fp, salida);
				}
			}
		}

		void recorrerEntrada(JsonObject e, List<String> funciones, Set<Item> salida) {
			List<String> f = mas(funciones, e.get("modifier"));
			String tipo = conEspacio(texto(e, "type"));
			switch (tipo) {
				case "minecraft:item" -> {
					Item item = item(texto(e, "name"));
					if (item != null) agregarSalida(item, f, salida);
				}
				case "minecraft:tag" -> {
					for (Item item : itemsDeConjunto(e.has("items") ? e.get("items") : e.get("name"))) agregarSalida(item, f, salida);
				}
				case "minecraft:loot_table" -> anidada(e.get("value"), f, salida);
				default -> {
					for (JsonElement hijo : arreglo(e, "children")) {
						if (hijo.isJsonObject()) recorrerEntrada(hijo.getAsJsonObject(), f, salida);
					}
				}
			}
		}

		void anidada(JsonElement valor, List<String> funciones, Set<Item> salida) {
			if (valor == null) return;
			if (valor.isJsonArray()) {
				for (JsonElement v : valor.getAsJsonArray()) anidada(v, funciones, salida);
			} else if (valor.isJsonObject()) {
				recorrerTabla(valor.getAsJsonObject(), funciones, salida);
			} else if (valor.isJsonPrimitive() && !valor.getAsString().startsWith("#")) {
				Identifier id = Identifier.tryParse(valor.getAsString());
				if (id != null) salida.addAll(itemsDe(id));
			}
		}

		/** Aplica lo que importa de los modificadores: fundir (mob en llamas), encantar libros, cambiar el item, mapas. */
		void agregarSalida(Item item, List<String> funciones, Set<Item> salida) {
			Item resultado = item;
			if (item == Items.BOOK && (funciones.contains("minecraft:enchant_randomly") || funciones.contains("minecraft:enchant_with_levels"))) {
				resultado = Items.ENCHANTED_BOOK;
			}
			if (funciones.contains("minecraft:exploration_map")) resultado = Items.FILLED_MAP;
			for (String f : funciones) {
				if (f.startsWith("set_item=")) {
					Item otro = item(f.substring("set_item=".length()));
					if (otro != null) resultado = otro;
				}
			}
			salida.add(resultado);
			if (funciones.contains("minecraft:furnace_smelt")) fundido(item).ifPresent(salida::add);
		}

		Optional<Item> fundido(Item item) {
			return fundidos.computeIfAbsent(item, i -> {
				SingleRecipeInput entrada = new SingleRecipeInput(new ItemStack(i));
				return server.getRecipeManager().getRecipeFor(RecipeType.SMELTING, entrada, server.overworld())
					.map(h -> h.value().assemble(entrada).getItem());
			});
		}

		List<String> mas(List<String> base, JsonElement modificador) {
			if (modificador == null) return base;
			List<String> lista = new ArrayList<>(base);
			funciones(modificador, lista);
			return lista;
		}

		void funciones(JsonElement m, List<String> salida) {
			if (m == null) return;
			if (m.isJsonArray()) {
				for (JsonElement x : m.getAsJsonArray()) funciones(x, salida);
			} else if (m.isJsonObject()) {
				JsonObject o = m.getAsJsonObject();
				String tipo = conEspacio(texto(o, "type"));
				salida.add(tipo);
				if (tipo.equals("minecraft:set_item")) salida.add("set_item=" + texto(o, "item"));
				funciones(o.get("functions"), salida);
				funciones(o.get("on_pass"), salida);
			}
		}

		List<Item> itemsDeConjunto(JsonElement conjunto) {
			List<Item> lista = new ArrayList<>();
			if (conjunto == null) return lista;
			if (conjunto.isJsonArray()) {
				for (JsonElement x : conjunto.getAsJsonArray()) lista.addAll(itemsDeConjunto(x));
			} else if (conjunto.isJsonPrimitive()) {
				String s = conjunto.getAsString();
				if (s.startsWith("#")) {
					Identifier tag = Identifier.tryParse(s.substring(1));
					if (tag != null) {
						for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, tag))) lista.add(h.value());
					}
				} else {
					Item item = item(s);
					if (item != null) lista.add(item);
				}
			}
			return lista;
		}

		/** Qué fuente representa una loot table, según su carpeta. null si no interesa. */
		Proto clasificar(Identifier tabla) {
			String[] seg = tabla.getPath().split("/");
			if (seg.length < 2) return null;
			String ns = tabla.getNamespace();
			String resto = tabla.getPath().substring(seg[0].length() + 1);
			return switch (seg[0]) {
				case "entities" -> mobProto(ns, seg[1], Fuente.MATAR);
				case "charged_creeper" -> seg[1].equals("root") ? null : mobProto(ns, seg[1], Fuente.CARGADO);
				case "shearing" -> mobProto(ns, seg[1], Fuente.TRASQUILAR);
				case "brush" -> mobProto(ns, seg[1], Fuente.CEPILLAR);
				case "gameplay" -> juego(ns, seg);
				case "blocks" -> bloqueProto(ns, seg[1], Fuente.ROMPER);
				case "harvest" -> bloqueProto(ns, seg[1], Fuente.COSECHAR);
				case "carve" -> bloqueProto(ns, seg[1], Fuente.TALLAR);
				case "till" -> bloqueProto(ns, seg[1], Fuente.LABRAR);
				case "chests" -> {
					if (resto.startsWith("spawn_bonus_chest")) yield null;
					int modo = resto.startsWith("trial_chambers/reward") ? Fuente.ARCON
						: resto.endsWith("_barrel") ? Fuente.BARRIL
						: resto.endsWith("_dispenser") ? Fuente.DISPENSADOR : Fuente.COFRE;
					yield estructuraProto(ns, resto, modo);
				}
				case "barrels" -> estructuraProto(ns, resto, Fuente.BARRIL);
				case "archaeology" -> estructuraProto(ns, resto, Fuente.ARQUEOLOGIA);
				case "dispensers" -> estructuraProto(ns, resto, Fuente.DISPENSADOR);
				case "pots" -> estructuraProto(ns, resto, Fuente.VASIJA);
				case "spawners" -> estructuraProto(ns, resto.startsWith("ominous/") ? resto.substring(8) : resto, Fuente.GENERADOR);
				default -> null;
			};
		}

		Proto juego(String ns, String[] seg) {
			String nombre = seg[1];
			if (nombre.startsWith("fishing")) {
				int modo = seg.length < 3 ? Fuente.PESCA_GENERAL : switch (seg[2]) {
					case "fish" -> Fuente.PESCA_PEZ;
					case "junk" -> Fuente.PESCA_BASURA;
					case "treasure" -> Fuente.PESCA_TESORO;
					default -> Fuente.PESCA_GENERAL;
				};
				return new Proto(Fuente.PESCA, Identifier.withDefaultNamespace("fishing"), modo, Optional.empty());
			}
			return switch (nombre) {
				case "piglin_bartering" -> new Proto(Fuente.TRUEQUE, Identifier.withDefaultNamespace("piglin"), 0, Optional.empty());
				case "hero_of_the_village" -> mobProto(ns, "villager", Fuente.REGALO_HEROE);
				case "cat_morning_gift" -> mobProto(ns, "cat", Fuente.REGALO_GATO);
				case "panda_sneeze" -> mobProto(ns, "panda", Fuente.ESTORNUDO);
				case "sniffer_digging" -> mobProto(ns, "sniffer", Fuente.DESENTIERRA);
				case "turtle_grow" -> mobProto(ns, "turtle", Fuente.CRECER);
				case "chicken_lay" -> mobProto(ns, "chicken", Fuente.PONE);
				default -> mobProto(ns, nombre.split("_")[0], Fuente.SOLTAR);
			};
		}

		Proto mobProto(String ns, String nombre, int modo) {
			Identifier id = Identifier.tryBuild(ns, nombre);
			if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) return null;
			return new Proto(Fuente.MOB, id, modo, Optional.of(id));
		}

		Proto bloqueProto(String ns, String nombre, int modo) {
			for (String candidato : new String[]{nombre, nombre + "s"}) {
				Identifier id = Identifier.tryBuild(ns, candidato);
				if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) return new Proto(Fuente.BLOQUE, id, modo, Optional.empty());
			}
			return null;
		}

		Proto estructuraProto(String ns, String resto, int modo) {
			String hoja = resto.substring(resto.lastIndexOf('/') + 1);
			for (Lugar l : LUGARES) {
				if (!resto.startsWith(l.prefijo) && !hoja.startsWith(l.prefijo)) continue;
				return new Proto(Fuente.ESTRUCTURA, Identifier.fromNamespaceAndPath(ns, l.grupo), modo, guiaEstructura(ns, l.guia));
			}
			if (resto.startsWith("village")) {
				return new Proto(Fuente.ESTRUCTURA, Identifier.fromNamespaceAndPath(ns, "village"), modo, guiaEstructura(ns, aldea(hoja)));
			}
			Identifier propia = Identifier.tryBuild(ns, hoja);
			if (propia != null && estructuras.contains(propia)) {
				return new Proto(Fuente.ESTRUCTURA, grupoDeEstructura(propia), modo, Optional.of(propia));
			}
			return propia == null ? null : new Proto(Fuente.ESTRUCTURA, propia, modo, Optional.empty());
		}

		/** Cofre de aldea → la variante de aldea si el nombre la dice (village_desert_house), si no la de llanura. */
		static String aldea(String hoja) {
			for (String v : new String[]{"desert", "plains", "savanna", "snowy", "taiga"}) {
				if (hoja.startsWith("village_" + v)) return "village_" + v;
			}
			return "village_plains";
		}

		Optional<Identifier> guiaEstructura(String ns, String path) {
			if (path == null) return Optional.empty();
			Identifier id = Identifier.fromNamespaceAndPath(ns, path);
			return estructuras.contains(id) ? Optional.of(id) : Optional.empty();
		}

		/**
		 * Un bloque que se suelta a sí mismo solo dice algo si el item no se fabrica (una flor, una campana).
		 * Las macetas y los calderos llenos son ruido.
		 */
		boolean bloqueInformativo(Identifier bloqueId, Item item) {
			String p = bloqueId.getPath();
			if (p.startsWith("potted_") || p.endsWith("_cauldron") || p.endsWith("candle_cake")) return false;
			Block bloque = BuiltInRegistries.BLOCK.getValue(bloqueId);
			return bloque.asItem() != item || !recetas.seFabrica(item);
		}

		// ---------------------------------------------------------------- trueques

		void trueques() {
			var conjuntos = server.registryAccess().lookupOrThrow(Registries.TRADE_SET);
			Map<Holder<VillagerTrade>, Optional<Item>> cache = new HashMap<>();
			BuiltInRegistries.VILLAGER_PROFESSION.listElements().forEach(prof -> {
				Identifier profId = prof.key().identifier();
				VillagerProfession profesion = prof.value();
				for (var e : profesion.tradeSetsByLevel().int2ObjectEntrySet()) {
					conjuntos.get(e.getValue()).ifPresent(conjunto -> registrarTrueques(conjunto.value(), profId, e.getIntKey(), cache));
				}
			});
			Identifier nomada = BuiltInRegistries.ENTITY_TYPE.getKey(EntityTypes.WANDERING_TRADER);
			for (ResourceKey<TradeSet> clave : List.of(TradeSets.WANDERING_TRADER_COMMON, TradeSets.WANDERING_TRADER_UNCOMMON, TradeSets.WANDERING_TRADER_BUYING)) {
				conjuntos.get(clave).ifPresent(conjunto -> registrarTrueques(conjunto.value(), nomada, 0, cache));
			}
		}

		void registrarTrueques(TradeSet conjunto, Identifier quien, int nivel, Map<Holder<VillagerTrade>, Optional<Item>> cache) {
			for (Holder<VillagerTrade> trueque : conjunto.trades()) {
				cache.computeIfAbsent(trueque, t -> loQueDa(t.value()))
					.ifPresent(item -> agregar(item, new Proto(Fuente.ALDEANO, quien, nivel, Optional.empty())));
			}
		}

		Optional<Item> loQueDa(VillagerTrade trueque) {
			return VillagerTrade.CODEC.encodeStart(ops, trueque).result()
				.filter(JsonElement::isJsonObject)
				.map(j -> j.getAsJsonObject().get("gives"))
				.filter(JsonElement::isJsonObject)
				.map(g -> item(texto(g.getAsJsonObject(), "id")));
		}

		// ---------------------------------------------------------------- resultado

		void agregar(Item item, Proto proto) {
			if (item == null || item == Items.AIR) return;
			LinkedHashMap<String, Proto> lista = protos.computeIfAbsent(item, k -> new LinkedHashMap<>());
			Proto previo = lista.get(proto.clave());
			// Aldeanos: una sola fila por profesión con el nivel más bajo en que lo ofrece.
			if (previo == null || (proto.tipo == Fuente.ALDEANO && proto.modo < previo.modo)) lista.put(proto.clave(), proto);
		}

		IndiceFuentes terminar() {
			Map<Item, List<Fuente>> porItem = new HashMap<>();
			protos.forEach((item, mapa) -> {
				List<Fuente> lista = new ArrayList<>(mapa.size());
				for (Proto p : mapa.values()) {
					List<Identifier> est = List.of(), bio = List.of();
					if (p.tipo == Fuente.MOB) {
						EntityType<?> tipo = BuiltInRegistries.ENTITY_TYPE.getValue(p.id);
						est = List.copyOf(estructurasDe.getOrDefault(tipo, Set.of()));
						bio = List.copyOf(biomasDe.getOrDefault(tipo, Set.of()));
					}
					lista.add(new Fuente(p.tipo, p.id, p.modo, p.guia, est, bio));
				}
				lista.sort(Comparator.comparingInt(IndiceFuentes::ordenTipo));
				porItem.put(item, List.copyOf(lista));
			});
			return new IndiceFuentes(porItem, biomasDe, estructurasDe, estructuras);
		}

		// ---------------------------------------------------------------- utilidades JSON

		static Iterable<JsonElement> arreglo(JsonObject o, String campo) {
			JsonElement e = o.get(campo);
			return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
		}

		static String texto(JsonObject o, String campo) {
			JsonElement e = o.get(campo);
			return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
		}

		static String conEspacio(String id) {
			return id.isEmpty() || id.contains(":") ? id : "minecraft:" + id;
		}

		static Item item(String id) {
			Identifier ident = Identifier.tryParse(id);
			if (ident == null) return null;
			return BuiltInRegistries.ITEM.getOptional(ident).filter(i -> i != Items.AIR).orElse(null);
		}
	}
}
