package dev.venatio.cliente.fichas;

import dev.venatio.red.PaquetesAyudas;
import dev.venatio.red.PaquetesFicha.Fuente;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Cómo se presenta una fuente: icono, nombre, cómo se consigue y dónde aparece. Nombres vanilla traducidos. */
final class VistaFuente {
	private VistaFuente() {}

	private static final Map<String, Item> CABEZAS = Map.of(
		"skeleton", Items.SKELETON_SKULL,
		"wither_skeleton", Items.WITHER_SKELETON_SKULL,
		"zombie", Items.ZOMBIE_HEAD,
		"creeper", Items.CREEPER_HEAD,
		"piglin", Items.PIGLIN_HEAD,
		"ender_dragon", Items.DRAGON_HEAD);

	/** Estación de trabajo de cada profesión: dice qué bloque poner para tener ese aldeano. */
	private static final Map<String, Item> ESTACIONES = Map.ofEntries(
		Map.entry("armorer", Items.BLAST_FURNACE),
		Map.entry("butcher", Items.SMOKER),
		Map.entry("cartographer", Items.CARTOGRAPHY_TABLE),
		Map.entry("cleric", Items.BREWING_STAND),
		Map.entry("farmer", Items.COMPOSTER),
		Map.entry("fisherman", Items.BARREL),
		Map.entry("fletcher", Items.FLETCHING_TABLE),
		Map.entry("leatherworker", Items.CAULDRON),
		Map.entry("librarian", Items.LECTERN),
		Map.entry("mason", Items.STONECUTTER),
		Map.entry("shepherd", Items.LOOM),
		Map.entry("toolsmith", Items.SMITHING_TABLE),
		Map.entry("weaponsmith", Items.GRINDSTONE));

	private static final String[] MODOS_MOB = {"matar", "cargado", "trasquilar", "cepillar", "regalo_gato", "regalo_heroe",
		"soltar", "estornudo", "desentierra", "crecer", "pone", "curado"};
	private static final String[] MODOS_ESTRUCTURA = {"cofre", "barril", "arqueologia", "dispensador", "vasija", "generador",
		"arcon", "natural"};
	private static final String[] MODOS_BLOQUE = {"romper", "cosechar", "tallar", "labrar"};
	private static final String[] MODOS_PESCA = {"general", "pez", "basura", "tesoro"};

	static ItemStack icono(Fuente f, Item delaFicha) {
		return switch (f.tipo()) {
			case Fuente.MOB -> iconoMob(f.id());
			case Fuente.ESTRUCTURA -> new ItemStack(switch (f.modo()) {
				case Fuente.BARRIL -> Items.BARREL;
				case Fuente.ARQUEOLOGIA -> Items.BRUSH;
				case Fuente.DISPENSADOR -> Items.DISPENSER;
				case Fuente.VASIJA -> Items.DECORATED_POT;
				case Fuente.GENERADOR -> Items.TRIAL_SPAWNER;
				case Fuente.ARCON -> Items.VAULT;
				case Fuente.NATURAL -> Items.FILLED_MAP;
				default -> Items.CHEST;
			});
			case Fuente.BLOQUE -> {
				Item item = BuiltInRegistries.BLOCK.getOptional(f.id()).map(Block::asItem).orElse(Items.AIR);
				yield new ItemStack(item == Items.AIR ? delaFicha : item);
			}
			case Fuente.ALDEANO -> f.id().getPath().equals("wandering_trader") ? new ItemStack(Items.WANDERING_TRADER_SPAWN_EGG)
				: new ItemStack(ESTACIONES.getOrDefault(f.id().getPath(), Items.VILLAGER_SPAWN_EGG));
			case Fuente.BIOMA -> new ItemStack(iconoBioma(f.id().getPath()));
			case Fuente.PESCA -> new ItemStack(Items.FISHING_ROD);
			case Fuente.TRUEQUE -> new ItemStack(Items.PIGLIN_HEAD);
			default -> new ItemStack(Items.BARRIER);
		};
	}

	private static ItemStack iconoMob(Identifier id) {
		Item cabeza = CABEZAS.get(id.getPath());
		if (cabeza != null && id.getNamespace().equals("minecraft")) return new ItemStack(cabeza);
		return BuiltInRegistries.ENTITY_TYPE.getOptional(id)
			.flatMap(SpawnEggItem::byId)
			.map(ItemStack::new)
			.orElse(new ItemStack(Items.NAME_TAG));
	}

	private static Item iconoBioma(String path) {
		if (path.contains("nether") || path.contains("crimson") || path.contains("warped") || path.contains("soul_sand")
			|| path.contains("basalt")) return Items.NETHERRACK;
		if (path.startsWith("end_") || path.equals("the_end") || path.contains("end_islands")) return Items.END_STONE;
		if (path.contains("ocean") || path.contains("river") || path.contains("beach")) return Items.SAND;
		return Items.GRASS_BLOCK;
	}

	static Component nombre(Fuente f) {
		Identifier id = f.id();
		return switch (f.tipo()) {
			case Fuente.MOB, Fuente.TRUEQUE -> nombreMob(id);
			case Fuente.ESTRUCTURA -> nombreEstructura(id);
			case Fuente.BLOQUE -> BuiltInRegistries.BLOCK.getOptional(id).<Component>map(Block::getName).orElse(Component.literal(id.getPath()));
			case Fuente.ALDEANO -> id.getPath().equals("wandering_trader") ? nombreMob(id)
				: BuiltInRegistries.VILLAGER_PROFESSION.getOptional(id).map(VillagerProfession::name).orElse(Component.literal(id.getPath()));
			case Fuente.BIOMA -> nombreBioma(id);
			case Fuente.PESCA -> Component.translatable("venatio.fichas.fuente.pesca");
			default -> Component.literal(id.toString());
		};
	}

	static Component nombreMob(Identifier id) {
		return BuiltInRegistries.ENTITY_TYPE.getOptional(id).map(EntityType::getDescription).orElse(Component.literal(id.getPath()));
	}

	static Component nombreEstructura(Identifier grupo) {
		return Component.translatableWithFallback("venatio.fichas.estructura." + grupo.getPath(), grupo.getPath());
	}

	static Component nombreBioma(Identifier id) {
		return Component.translatableWithFallback(id.toLanguageKey("biome"), id.getPath());
	}

	static Component modo(Fuente f) {
		return switch (f.tipo()) {
			case Fuente.MOB -> clave("mob", MODOS_MOB, f.modo());
			case Fuente.ESTRUCTURA -> clave("estructura", MODOS_ESTRUCTURA, f.modo());
			case Fuente.BLOQUE -> clave("bloque", MODOS_BLOQUE, f.modo());
			case Fuente.ALDEANO -> f.modo() > 0
				? Component.translatable("venatio.fichas.modo.aldeano.nivel", Component.translatable("merchant.level." + f.modo()))
				: Component.translatable("venatio.fichas.modo.aldeano");
			case Fuente.BIOMA -> Component.translatable("venatio.fichas.modo.bioma");
			case Fuente.PESCA -> clave("pesca", MODOS_PESCA, f.modo());
			case Fuente.TRUEQUE -> Component.translatable("venatio.fichas.modo.trueque");
			default -> Component.empty();
		};
	}

	private static Component clave(String tipo, String[] modos, int modo) {
		String nombre = modo >= 0 && modo < modos.length ? modos[modo] : modos[0];
		return Component.translatable("venatio.fichas.modo." + tipo + "." + nombre);
	}

	/** Estructuras y biomas donde aparece un mob, en ese orden. */
	static List<Component> lugares(Fuente f) {
		List<Component> lista = new ArrayList<>();
		for (Identifier e : f.estructuras()) lista.add(nombreEstructura(e));
		for (Identifier b : f.biomas()) lista.add(nombreBioma(b));
		return lista;
	}

	/** Tipo de guía de PaquetesAyudas para el botón "Guíame", o -1. */
	static int tipoGuia(Fuente f) {
		if (f.guia().isEmpty()) return -1;
		return switch (f.tipo()) {
			case Fuente.MOB -> PaquetesAyudas.PedirGuia.MOB;
			case Fuente.ESTRUCTURA -> PaquetesAyudas.PedirGuia.ESTRUCTURA;
			case Fuente.BIOMA -> PaquetesAyudas.PedirGuia.BIOMA;
			default -> -1;
		};
	}
}
