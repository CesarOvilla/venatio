package dev.venatio.info;

import dev.venatio.red.PaquetesFicha.Destilacion;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.BrewingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Para cada item, las recetas del servidor que lo producen: mesa (con y sin forma), horno, alto horno, ahumador,
 * fogata, cortapiedras, mesa de herrería y soporte para pociones. Se guardan como RecipeDisplay (lo mismo que usa
 * el libro de recetas), que ya viaja por la red y el cliente resuelve a stacks.
 */
public final class IndiceRecetas {
	private final Map<Item, List<RecipeDisplay>> recetas;
	private final Map<Item, List<Destilacion>> destilaciones;

	private IndiceRecetas(Map<Item, List<RecipeDisplay>> recetas, Map<Item, List<Destilacion>> destilaciones) {
		this.recetas = recetas;
		this.destilaciones = destilaciones;
	}

	public static IndiceRecetas construir(MinecraftServer server) {
		ContextMap contexto = SlotDisplayContext.fromLevel(server.overworld());
		FeatureFlagSet activas = server.getWorldData().enabledFeatures();
		Map<Item, List<RecipeDisplay>> recetas = new HashMap<>();
		Map<Item, List<Destilacion>> destilaciones = new HashMap<>();

		for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
			Recipe<?> receta = holder.value();
			if (receta instanceof BrewingRecipe b) {
				Destilacion d = new Destilacion(b.getInput().ingredient().display(), b.getReagent().ingredient().display(),
					new SlotDisplay.ItemStackSlotDisplay(b.getOutput()));
				for (Item i : producidos(d.resultado(), contexto)) destilaciones.computeIfAbsent(i, k -> new ArrayList<>()).add(d);
				continue;
			}
			for (RecipeDisplay display : receta.display()) {
				if (!display.isEnabled(activas) || esDemostracion(display.result())) continue;
				for (Item i : producidos(display.result(), contexto)) recetas.computeIfAbsent(i, k -> new ArrayList<>()).add(display);
			}
		}
		recetas.values().forEach(l -> l.sort(Comparator.comparingInt(IndiceRecetas::orden)));
		return new IndiceRecetas(recetas, destilaciones);
	}

	/** Teñir armadura o poner adornos: no producen un item nuevo, solo lo modifican. */
	private static boolean esDemostracion(SlotDisplay resultado) {
		return resultado instanceof SlotDisplay.DyedSlotDemo || resultado instanceof SlotDisplay.SmithingTrimDemoSlotDisplay;
	}

	private static Set<Item> producidos(SlotDisplay resultado, ContextMap contexto) {
		Set<Item> items = new LinkedHashSet<>();
		for (ItemStack s : resultado.resolveForStacks(contexto)) if (!s.isEmpty()) items.add(s.getItem());
		return items;
	}

	private static int orden(RecipeDisplay d) {
		return switch (d) {
			case ShapedCraftingRecipeDisplay x -> 0;
			case ShapelessCraftingRecipeDisplay x -> 0;
			case FurnaceRecipeDisplay x -> 1;
			case StonecutterRecipeDisplay x -> 2;
			case SmithingRecipeDisplay x -> 3;
			default -> 4;
		};
	}

	public List<RecipeDisplay> de(Item item) {
		return recetas.getOrDefault(item, List.of());
	}

	public List<Destilacion> destilacionesDe(Item item) {
		return destilaciones.getOrDefault(item, List.of());
	}

	public boolean seFabrica(Item item) {
		return recetas.containsKey(item) || destilaciones.containsKey(item);
	}

	public int cantidadItems() {
		return recetas.size();
	}
}
