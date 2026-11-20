package dev.venatio.cliente;

import dev.venatio.ayudas.NombresObjetivo;
import dev.venatio.ayudas.TipoAyuda;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;

import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Lo que se puede elegir como objetivo de una guía, con nombre en el idioma del juego e ícono. */
final class CatalogoObjetivos {
	enum Dimension { MUNDO, NETHER, END }

	/** disponible: existe en la dimensión donde está el jugador (las demás se listan al final, en gris). */
	record Entrada(Identifier id, Component nombre, ItemStack icono, boolean disponible, String clave) {}

	private record Estructura(String id, Item icono, Set<Dimension> dimensiones) {}

	/**
	 * Estructuras curadas. Los ids sin variante (village, shipwreck, ocean_ruin, ruined_portal, mineshaft, abandoned_camp)
	 * son etiquetas de vanilla que agrupan todas las variantes; el servidor acepta tanto ids como etiquetas.
	 */
	private static final List<Estructura> ESTRUCTURAS = List.of(
		new Estructura("village", Items.BELL, EnumSet.of(Dimension.MUNDO)),
		new Estructura("desert_pyramid", Items.CHISELED_SANDSTONE, EnumSet.of(Dimension.MUNDO)),
		new Estructura("jungle_pyramid", Items.MOSSY_COBBLESTONE, EnumSet.of(Dimension.MUNDO)),
		new Estructura("swamp_hut", Items.CAULDRON, EnumSet.of(Dimension.MUNDO)),
		new Estructura("mansion", Items.DARK_OAK_LOG, EnumSet.of(Dimension.MUNDO)),
		new Estructura("monument", Items.PRISMARINE_BRICKS, EnumSet.of(Dimension.MUNDO)),
		new Estructura("shipwreck", Items.OAK_BOAT, EnumSet.of(Dimension.MUNDO)),
		new Estructura("ocean_ruin", Items.SUSPICIOUS_SAND, EnumSet.of(Dimension.MUNDO)),
		new Estructura("ruined_portal", Items.CRYING_OBSIDIAN, EnumSet.of(Dimension.MUNDO, Dimension.NETHER)),
		new Estructura("pillager_outpost", Items.CROSSBOW, EnumSet.of(Dimension.MUNDO)),
		new Estructura("trial_chambers", Items.TRIAL_KEY, EnumSet.of(Dimension.MUNDO)),
		new Estructura("ancient_city", Items.ECHO_SHARD, EnumSet.of(Dimension.MUNDO)),
		new Estructura("mineshaft", Items.RAIL, EnumSet.of(Dimension.MUNDO)),
		new Estructura("stronghold", Items.ENDER_EYE, EnumSet.of(Dimension.MUNDO)),
		new Estructura("igloo", Items.SNOW_BLOCK, EnumSet.of(Dimension.MUNDO)),
		new Estructura("trail_ruins", Items.SUSPICIOUS_GRAVEL, EnumSet.of(Dimension.MUNDO)),
		new Estructura("abandoned_camp", Items.CAMPFIRE, EnumSet.of(Dimension.MUNDO)),
		new Estructura("buried_treasure", Items.HEART_OF_THE_SEA, EnumSet.of(Dimension.MUNDO)),
		new Estructura("fortress", Items.NETHER_BRICKS, EnumSet.of(Dimension.NETHER)),
		new Estructura("bastion_remnant", Items.GILDED_BLACKSTONE, EnumSet.of(Dimension.NETHER)),
		new Estructura("nether_fossil", Items.BONE_BLOCK, EnumSet.of(Dimension.NETHER)),
		new Estructura("end_city", Items.PURPUR_BLOCK, EnumSet.of(Dimension.END)));

	private CatalogoObjetivos() {}

	static List<Entrada> de(TipoAyuda tipo, Minecraft mc) {
		List<Entrada> lista = switch (tipo) {
			case GUIA_BIOMA -> biomas(mc);
			case GUIA_ESTRUCTURA -> estructuras(mc);
			default -> mobs();
		};
		Collator orden = Collator.getInstance(Locale.forLanguageTag("es"));
		orden.setStrength(Collator.PRIMARY);
		lista.sort(Comparator.comparing((Entrada e) -> !e.disponible()).thenComparing(e -> e.nombre().getString(), orden));
		return lista;
	}

	private static Dimension actual(Minecraft mc) {
		if (mc.level == null) return Dimension.MUNDO;
		var tipo = mc.level.dimensionTypeRegistration();
		if (tipo.is(BuiltinDimensionTypes.NETHER)) return Dimension.NETHER;
		if (tipo.is(BuiltinDimensionTypes.END)) return Dimension.END;
		return Dimension.MUNDO;
	}

	private static List<Entrada> biomas(Minecraft mc) {
		List<Entrada> lista = new ArrayList<>();
		if (mc.level == null) return lista;
		Dimension aqui = actual(mc);
		var registro = mc.level.registryAccess().lookupOrThrow(Registries.BIOME);
		// Si el servidor no mandó las etiquetas de biomas, no se filtra por dimensión.
		boolean hayEtiquetas = registro.listElements().anyMatch(b -> b.is(BiomeTags.IS_OVERWORLD));
		registro.listElements().forEach(ref -> {
			Identifier id = ref.key().identifier();
			if (id.getPath().equals("the_void")) return;
			Dimension dim = dimensionDe(ref);
			Item icono = switch (dim) {
				case NETHER -> Items.NETHERRACK;
				case END -> Items.END_STONE;
				case MUNDO -> Items.GRASS_BLOCK;
			};
			lista.add(entrada(id, NombresObjetivo.bioma(id), new ItemStack(icono), !hayEtiquetas || dim == aqui));
		});
		return lista;
	}

	private static Dimension dimensionDe(Holder<Biome> b) {
		if (b.is(BiomeTags.IS_NETHER)) return Dimension.NETHER;
		if (b.is(BiomeTags.IS_END)) return Dimension.END;
		return Dimension.MUNDO;
	}

	private static List<Entrada> estructuras(Minecraft mc) {
		Dimension aqui = actual(mc);
		List<Entrada> lista = new ArrayList<>();
		for (Estructura e : ESTRUCTURAS) {
			Identifier id = Identifier.withDefaultNamespace(e.id);
			lista.add(entrada(id, NombresObjetivo.estructura(id), new ItemStack(e.icono), e.dimensiones.contains(aqui)));
		}
		return lista;
	}

	/** Todo lo que tiene huevo de aparición: los mobs del juego. */
	private static List<Entrada> mobs() {
		List<Entrada> lista = new ArrayList<>();
		BuiltInRegistries.ENTITY_TYPE.listElements().forEach(ref -> SpawnEggItem.byId(ref.value()).ifPresent(huevo -> {
			Identifier id = ref.key().identifier();
			lista.add(entrada(id, ref.value().getDescription(), new ItemStack(huevo), true));
		}));
		return lista;
	}

	private static Entrada entrada(Identifier id, Component nombre, ItemStack icono, boolean disponible) {
		return new Entrada(id, nombre, icono, disponible, normalizar(nombre.getString() + " " + id.getPath().replace('_', ' ')));
	}

	/** Minúsculas y sin acentos, para buscar "ciudad antigua" escribiendo "antigu" o "ANTÍGUA". */
	static String normalizar(String s) {
		return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
	}
}
