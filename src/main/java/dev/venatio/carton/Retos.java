package dev.venatio.carton;

import dev.venatio.carton.Reto.Tipo;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Catálogo de casillas de logro. Nada obliga a ir al End. El nombre y el "cómo" de cada uno están en
 * assets/venatio_carton/lang (venatio.reto.&lt;id&gt; y venatio.reto.&lt;id&gt;.como).
 */
public final class Retos {
	private Retos() {}

	public static final List<Reto> TODOS = List.of(
		// ---------------------------------------------------------------- biomas del mundo normal
		bioma("bioma_pantano", 1, "lily_pad", "swamp", "mangrove_swamp"),
		bioma("bioma_jungla", 2, "jungle_sapling", "jungle", "sparse_jungle", "bamboo_jungle"),
		bioma("bioma_desierto", 1, "dead_bush", "desert"),
		bioma("bioma_badlands", 3, "terracotta", "badlands", "eroded_badlands", "wooded_badlands"),
		bioma("bioma_cerezos", 2, "cherry_sapling", "cherry_grove"),
		bioma("bioma_campo_fungico", 3, "mycelium", "mushroom_fields"),
		bioma("bioma_nieve", 1, "snowball", "snowy_plains", "ice_spikes", "snowy_taiga", "snowy_slopes", "grove", "frozen_peaks",
			"jagged_peaks", "snowy_beach", "frozen_river"),
		bioma("bioma_sabana", 1, "acacia_sapling", "savanna", "savanna_plateau", "windswept_savanna"),
		bioma("bioma_oceano", 1, "kelp", "ocean", "deep_ocean", "warm_ocean", "lukewarm_ocean", "deep_lukewarm_ocean", "cold_ocean",
			"deep_cold_ocean", "frozen_ocean", "deep_frozen_ocean"),
		bioma("bioma_oceano_calido", 2, "tube_coral", "warm_ocean"),
		bioma("bioma_taiga", 1, "spruce_sapling", "taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga", "snowy_taiga"),
		bioma("bioma_bosque_oscuro", 1, "dark_oak_sapling", "dark_forest"),
		bioma("bioma_jardin_palido", 3, "pale_oak_sapling", "pale_garden"),
		bioma("bioma_abedules", 1, "birch_sapling", "birch_forest", "old_growth_birch_forest"),
		bioma("bioma_pradera", 2, "cornflower", "meadow"),
		bioma("bioma_bosque_floral", 2, "lilac", "flower_forest"),
		bioma("bioma_cumbres", 2, "goat_horn", "stony_peaks", "jagged_peaks", "frozen_peaks"),
		bioma("bioma_cuevas_frondosas", 2, "azalea", "lush_caves"),
		bioma("bioma_cuevas_karsticas", 2, "dripstone_block", "dripstone_caves"),
		bioma("bioma_oscuridad_profunda", 3, "sculk", "deep_dark"),
		bioma("bioma_playa", 1, "sand", "beach", "snowy_beach"),
		bioma("bioma_girasoles", 2, "sunflower", "sunflower_plains"),
		bioma("bioma_picos_de_hielo", 3, "packed_ice", "ice_spikes"),
		// ---------------------------------------------------------------- biomas del Nether
		bioma("bioma_valle_almas", 2, "soul_sand", "soul_sand_valley"),
		bioma("bioma_bosque_carmesi", 2, "crimson_fungus", "crimson_forest"),
		bioma("bioma_bosque_distorsionado", 2, "warped_fungus", "warped_forest"),
		bioma("bioma_deltas", 3, "basalt", "basalt_deltas"),

		// ---------------------------------------------------------------- mobs
		matar("matar_zombi", 1, "zombie_head", "zombie", "zombie_villager"),
		matar("matar_esqueleto", 1, "skeleton_skull", "skeleton", "bogged"),
		matar("matar_creeper", 1, "creeper_head", "creeper"),
		matar("matar_arana", 1, "spider_spawn_egg", "spider"),
		matar("matar_enderman", 2, "enderman_spawn_egg", "enderman"),
		matar("matar_bruja", 3, "witch_spawn_egg", "witch"),
		matar("matar_slime", 2, "slime_spawn_egg", "slime"),
		matar("matar_ahogado", 2, "drowned_spawn_egg", "drowned"),
		matar("matar_saqueador", 2, "pillager_spawn_egg", "pillager"),
		matar("matar_guardian", 3, "guardian_spawn_egg", "guardian"),
		matar("matar_disecado", 2, "husk_spawn_egg", "husk"),
		matar("matar_errante", 2, "stray_spawn_egg", "stray"),
		matar("matar_arana_cueva", 2, "cave_spider_spawn_egg", "cave_spider"),
		matar("matar_pez_de_plata", 3, "silverfish_spawn_egg", "silverfish"),
		matar("matar_breeze", 3, "breeze_spawn_egg", "breeze"),
		matar("matar_blaze", 3, "blaze_spawn_egg", "blaze"),
		matar("matar_ghast", 3, "ghast_spawn_egg", "ghast"),
		matar("matar_cubo_magma", 2, "magma_cube_spawn_egg", "magma_cube"),
		matar("matar_esqueleto_wither", 3, "wither_skeleton_skull", "wither_skeleton"),
		matar("matar_piglin", 2, "piglin_head", "piglin"),
		matar("matar_hoglin", 3, "hoglin_spawn_egg", "hoglin"),

		// ---------------------------------------------------------------- comer y beber
		comer("comer_galleta", 2, "cookie"),
		comer("comer_pastel_calabaza", 2, "pumpkin_pie"),
		comer("comer_bayas_brillantes", 2, "glow_berries"),
		comer("comer_bayas_dulces", 1, "sweet_berries"),
		comer("comer_sopa_conejo", 3, "rabbit_stew"),
		comer("comer_papa_venenosa", 2, "poisonous_potato"),
		comer("comer_ojo_arana", 1, "spider_eye"),
		comer("comer_carne_podrida", 1, "rotten_flesh"),
		comer("comer_manzana_dorada", 3, "golden_apple"),
		comer("comer_zanahoria_dorada", 2, "golden_carrot"),
		comer("comer_alga_seca", 1, "dried_kelp"),
		comer("comer_miel", 3, "honey_bottle"),
		comer("comer_sandia", 1, "melon_slice"),
		comer("comer_pez_globo", 3, "pufferfish"),
		comer("comer_sopa_sospechosa", 3, "suspicious_stew"),
		estadistica("comer_pastel", 2, "cake", "cake", 1, "eat_cake_slice"),

		// ---------------------------------------------------------------- acciones
		new Reto("dormir", Tipo.DORMIR, 1, mc("red_bed"), mc("red_bed"), ids("sleep_in_bed", "sleep_in_straw_bed"), 1),
		new Reto("domesticar", Tipo.DOMESTICAR, 2, mc("bone"), mc("bone"), List.of(), 1),
		estadistica("criar", 1, "wheat", "wheat", 1, "animals_bred"),
		estadistica("pescar", 1, "fishing_rod", "fishing_rod", 1, "fish_caught"),
		estadistica("encantar", 3, "enchanting_table", "enchanting_table", 1, "enchant_item"),
		estadistica("comerciar", 2, "emerald", "emerald", 1, "traded_with_villager"),
		estadistica("montar_caballo", 2, "saddle", "saddle", 500, "horse_one_cm"),
		estadistica("montar_cerdo", 3, "carrot_on_a_stick", "carrot_on_a_stick", 500, "pig_one_cm"),
		estadistica("montar_strider", 3, "warped_fungus_on_a_stick", "warped_fungus_on_a_stick", 500, "strider_one_cm"),
		estadistica("campana", 2, "bell", "bell", 1, "bell_ring"),
		estadistica("bote", 1, "oak_boat", "oak_boat", 10000, "boat_one_cm"),
		estadistica("diana", 2, "target", "target", 1, "target_hit"),
		estadistica("maceta", 1, "flower_pot", "flower_pot", 1, "pot_flower"),
		estadistica("bloque_musical", 1, "note_block", "note_block", 1, "play_noteblock"),
		estadistica("disco", 3, "jukebox", "jukebox", 1, "play_record"),
		estadistica("caldero", 1, "cauldron", "cauldron", 1, "fill_cauldron"),
		minar("minar_diamante", 2, "diamond_ore", "iron_pickaxe", "diamond_ore", "deepslate_diamond_ore"),
		minar("minar_esmeralda", 3, "emerald_ore", "iron_pickaxe", "emerald_ore", "deepslate_emerald_ore"),
		minar("minar_obsidiana", 2, "obsidian", "diamond_pickaxe", "obsidian"),
		minar("minar_cuarzo", 2, "nether_quartz_ore", "wooden_pickaxe", "nether_quartz_ore"),
		new Reto("altura_200", Tipo.ALTURA, 2, mc("ladder"), null, List.of(), 200),
		new Reto("altura_menos_50", Tipo.ALTURA, 2, mc("deepslate"), null, List.of(), -50),
		new Reto("nether", Tipo.NETHER, 2, mc("netherrack"), mc("flint_and_steel"), List.of(), 0)
	);

	private static final Map<String, Reto> POR_ID = new LinkedHashMap<>();

	static {
		for (Reto r : TODOS) {
			if (POR_ID.put(r.id(), r) != null) throw new IllegalStateException("Logro repetido: " + r.id());
		}
	}

	/** El logro con ese id, o null. */
	public static Reto porId(String id) {
		return POR_ID.get(id);
	}

	/**
	 * n logros distintos al azar. El nivel de cada uno sale de la dificultad del cartón; para que el cartón no se llene
	 * de un solo tipo, ningún grupo (biomas, mobs, comida, acciones) pasa del 40 %.
	 */
	public static List<Reto> elegir(int n, Random rnd, Dificultad dificultad) {
		List<Reto> disponibles = new ArrayList<>(TODOS);
		Collections.shuffle(disponibles, rnd);
		int tope = Math.max(1, (int) Math.ceil(n * 0.4));
		int[] porGrupo = new int[4];
		List<Integer> niveles = dificultad.niveles(n, rnd);
		List<Reto> elegidos = new ArrayList<>(n);
		for (int k = 0; k < n && !disponibles.isEmpty(); k++) {
			Reto r = buscar(disponibles, niveles.get(k), porGrupo, tope);
			if (r == null) r = buscar(disponibles, 0, porGrupo, tope);
			if (r == null) r = disponibles.get(0);
			disponibles.remove(r);
			porGrupo[r.grupo()]++;
			elegidos.add(r);
		}
		return elegidos;
	}

	/** El primero del nivel pedido (0 = cualquiera) cuyo grupo no esté lleno. */
	private static Reto buscar(List<Reto> lista, int nivel, int[] porGrupo, int tope) {
		for (Reto r : lista) {
			if ((nivel == 0 || r.dificultad() == nivel) && porGrupo[r.grupo()] < tope) return r;
		}
		return null;
	}

	// ---------------------------------------------------------------- construcción

	private static Identifier mc(String path) {
		return Identifier.withDefaultNamespace(path);
	}

	private static List<Identifier> ids(String... paths) {
		List<Identifier> lista = new ArrayList<>(paths.length);
		for (String p : paths) lista.add(mc(p));
		return List.copyOf(lista);
	}

	private static Reto bioma(String id, int dificultad, String icono, String... biomas) {
		return new Reto(id, Tipo.BIOMA, dificultad, mc(icono), null, ids(biomas), 0);
	}

	private static Reto matar(String id, int dificultad, String icono, String... mobs) {
		return new Reto(id, Tipo.MATAR, dificultad, mc(icono), null, ids(mobs), 1);
	}

	private static Reto comer(String id, int dificultad, String comida) {
		return new Reto(id, Tipo.COMER, dificultad, mc(comida), mc(comida), ids(comida), 1);
	}

	private static Reto estadistica(String id, int dificultad, String icono, String relacionado, int umbral, String... stats) {
		return new Reto(id, Tipo.ESTADISTICA, dificultad, mc(icono), relacionado == null ? null : mc(relacionado), ids(stats), umbral);
	}

	private static Reto minar(String id, int dificultad, String icono, String herramienta, String... bloques) {
		return new Reto(id, Tipo.MINAR, dificultad, mc(icono), mc(herramienta), ids(bloques), 1);
	}
}
