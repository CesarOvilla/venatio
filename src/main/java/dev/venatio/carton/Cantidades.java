package dev.venatio.carton;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Random;
import java.util.Set;

/**
 * Casillas con cantidad (opción "Casillas con cantidad"): cuántas unidades pide una casilla de item.
 * Sale de la semilla, del nivel de la casilla (1 fácil a 3 difícil), del tamaño de pila y de la dificultad del cartón.
 * Los items que no se apilan nunca piden más de uno.
 */
public final class Cantidades {
	/** Parte de las casillas de item que piden varias unidades. */
	public static final float PROPORCION = 0.30f;

	/** Items que cuestan mucho aunque su categoría sea fácil: piden pocas unidades. */
	private static final Set<Item> VALIOSOS = Set.of(Items.DIAMOND, Items.EMERALD, Items.ENDER_PEARL, Items.GOLDEN_APPLE,
		Items.BREEZE_ROD, Items.TRIAL_KEY, Items.NAME_TAG, Items.ARMADILLO_SCUTE, Items.HONEYCOMB, Items.HONEY_BOTTLE,
		Items.SLIME_BALL, Items.GLISTERING_MELON_SLICE, Items.FERMENTED_SPIDER_EYE, Items.SEA_PICKLE, Items.FIREFLY_BUSH,
		Items.OPEN_EYEBLOSSOM);

	private Cantidades() {}

	/** true si tiene sentido pedir varias unidades de este item. */
	public static boolean apilable(Item item) {
		return item != null && item.getDefaultMaxStackSize() > 1;
	}

	/** Unidades para una casilla; 1 si el item no se apila. */
	public static int para(Item item, int nivel, Dificultad dificultad, Random rnd) {
		if (!apilable(item)) return 1;
		int pila = item.getDefaultMaxStackSize();
		int min, max;
		if (VALIOSOS.contains(item)) {
			min = 2;
			max = nivel >= 3 ? 3 : 5;
		} else if (pila >= 64) {
			min = switch (nivel) { case 1 -> 8; case 2 -> 4; default -> 2; };
			max = switch (nivel) { case 1 -> 24; case 2 -> 12; default -> 6; };
		} else {
			min = 2;
			max = switch (nivel) { case 1 -> 8; case 2 -> 6; default -> 4; };
		}
		int n = min + rnd.nextInt(max - min + 1);
		float factor = switch (dificultad) {
			case FACIL -> 0.6f;
			case DIFICIL -> 1.5f;
			default -> 1f;
		};
		n = Math.round(n * factor);
		// Números redondos en las cantidades grandes: 16 troncos, no 15.
		if (n > 10) n = Math.round(n / 4f) * 4;
		return Math.clamp(n, 2, pila);
	}
}
