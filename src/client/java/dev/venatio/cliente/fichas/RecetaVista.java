package dev.venatio.cliente.fichas;

import dev.venatio.red.PaquetesFicha.Destilacion;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;

import java.util.ArrayList;
import java.util.List;

/**
 * Una receta lista para dibujar en una caja de ANCHO × ALTO, con las ranuras donde las pondría la estación real:
 * cuadrícula 3×3 de la mesa, entrada y combustible del horno, las tres ranuras de la herrería…
 */
final class RecetaVista {
	static final int ANCHO = 134, ALTO = 76;

	/** Ranura con todas las opciones que acepta; las que tienen varias van rotando cada segundo. */
	record Ranura(int x, int y, List<ItemStack> opciones, boolean resultado) {
		ItemStack actual() {
			if (opciones.isEmpty()) return ItemStack.EMPTY;
			return opciones.get((int) ((Util.getMillis() / 1000) % opciones.size()));
		}

		int lado() {
			return resultado ? 26 : 18;
		}
	}

	final List<Ranura> ranuras;
	final ItemStack estacion;
	/** Posición de la flecha y del fuego (fuegoX < 0 si no hay). */
	final int flechaX, flechaY, fuegoX, fuegoY;
	/** Detalle bajo la caja: "sin orden fijo", "tarda 10 s"… o null. */
	final Component nota;

	private RecetaVista(List<Ranura> ranuras, ItemStack estacion, int flechaX, int flechaY, int fuegoX, int fuegoY, Component nota) {
		this.ranuras = ranuras;
		this.estacion = estacion;
		this.flechaX = flechaX;
		this.flechaY = flechaY;
		this.fuegoX = fuegoX;
		this.fuegoY = fuegoY;
		this.nota = nota;
	}

	static RecetaVista de(RecipeDisplay d, ContextMap ctx) {
		List<Ranura> r = new ArrayList<>();
		ItemStack estacion = d.craftingStation().resolveForFirstStack(ctx);
		switch (d) {
			case ShapedCraftingRecipeDisplay s -> {
				SlotDisplay[] cuadricula = new SlotDisplay[9];
				for (int i = 0; i < s.ingredients().size(); i++) cuadricula[(i / s.width()) * 3 + i % s.width()] = s.ingredients().get(i);
				mesa(r, cuadricula, d.result(), ctx);
				return new RecetaVista(r, estacion, 70, 36, -1, 0, null);
			}
			case ShapelessCraftingRecipeDisplay s -> {
				SlotDisplay[] cuadricula = new SlotDisplay[9];
				for (int i = 0; i < s.ingredients().size() && i < 9; i++) cuadricula[i] = s.ingredients().get(i);
				mesa(r, cuadricula, d.result(), ctx);
				return new RecetaVista(r, estacion, 70, 36, -1, 0, Component.translatable("venatio.fichas.receta.sin_forma"));
			}
			case FurnaceRecipeDisplay f -> {
				r.add(ranura(26, 17, f.ingredient(), ctx));
				r.add(ranura(26, 53, f.fuel(), ctx));
				r.add(resultado(88, 31, d.result(), ctx));
				String segundos = String.valueOf(Math.max(1, Math.round(f.duration() / 20f)));
				return new RecetaVista(r, estacion, 54, 36, 28, 37, Component.translatable("venatio.fichas.receta.tarda", segundos));
			}
			case StonecutterRecipeDisplay s -> {
				r.add(ranura(26, 35, s.input(), ctx));
				r.add(resultado(88, 31, d.result(), ctx));
				return new RecetaVista(r, estacion, 54, 36, -1, 0, null);
			}
			case SmithingRecipeDisplay s -> {
				r.add(ranura(8, 35, s.template(), ctx));
				r.add(ranura(26, 35, s.base(), ctx));
				r.add(ranura(44, 35, s.addition(), ctx));
				r.add(resultado(100, 31, d.result(), ctx));
				return new RecetaVista(r, estacion, 68, 36, -1, 0, null);
			}
			default -> {
				r.add(resultado(88, 31, d.result(), ctx));
				return new RecetaVista(r, estacion, 54, 36, -1, 0, null);
			}
		}
	}

	/** Soporte para pociones: ingrediente arriba, botella abajo, como en el soporte real. */
	static RecetaVista de(Destilacion d, ContextMap ctx) {
		List<Ranura> r = new ArrayList<>();
		r.add(ranura(26, 17, d.reactivo(), ctx));
		r.add(ranura(26, 53, d.entrada(), ctx));
		r.add(resultado(88, 31, d.resultado(), ctx));
		return new RecetaVista(r, new ItemStack(Items.BREWING_STAND), 54, 36, -1, 0, null);
	}

	/** Cuadrícula 3×3 completa, como la mesa de trabajo, con las ranuras vacías incluidas. */
	private static void mesa(List<Ranura> r, SlotDisplay[] cuadricula, SlotDisplay resultado, ContextMap ctx) {
		for (int i = 0; i < 9; i++) {
			int x = 8 + (i % 3) * 18, y = 17 + (i / 3) * 18;
			r.add(cuadricula[i] == null ? new Ranura(x, y, List.of(), false) : ranura(x, y, cuadricula[i], ctx));
		}
		r.add(resultado(100, 31, resultado, ctx));
	}

	private static Ranura ranura(int x, int y, SlotDisplay display, ContextMap ctx) {
		return new Ranura(x, y, display.resolveForStacks(ctx), false);
	}

	private static Ranura resultado(int x, int y, SlotDisplay display, ContextMap ctx) {
		return new Ranura(x, y, display.resolveForStacks(ctx), true);
	}
}
