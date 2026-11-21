package dev.venatio.prueba;

import dev.venatio.Venatio;
import dev.venatio.carton.Carton;
import dev.venatio.cliente.PantallaCarton;
import dev.venatio.cliente.fichas.PantallaFicha;
import dev.venatio.cliente.fichas.PantallaItemsPosibles;
import dev.venatio.info.Fichas;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Partida;
import dev.venatio.red.PaquetesFicha.Ficha;
import dev.venatio.red.PaquetesFicha.Fuente;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Fichas con un cliente real: items posibles (sin partida), ficha de un item con receta de mesa, navegación a un
 * ingrediente y vuelta, fichas de items que salen de mobs, y clic en una casilla del cartón durante una partida.
 * Deja capturas fichas_* en screenshots/.
 */
public final class PruebaFichas implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 600);
			idioma(ctx, "es_mx");
			ctx.waitTicks(20);

			comprobarIndices(sp);

			// Items posibles desde la pantalla del cartón, sin partida.
			ctx.setScreen(PantallaCarton::new);
			ctx.waitTicks(5);
			ctx.clickScreenButton("Items posibles");
			ctx.waitFor(pantalla(PantallaItemsPosibles.class, PantallaItemsPosibles::cargada), 200);
			ctx.waitTicks(5);
			ctx.takeScreenshot("fichas_01_items_posibles");
			paso("Pantalla de items posibles abierta desde el cartón");

			// Clic en el primer item posible: abre su ficha.
			int[] primero = ctx.computeOnClient(mc -> ((PantallaItemsPosibles) mc.gui.screen()).centroDe(0));
			clic(ctx, primero[0], primero[1]);
			ctx.waitFor(pantalla(PantallaFicha.class, PantallaFicha::cargada), 200);
			ctx.waitTicks(5);
			ctx.takeScreenshot("fichas_02_ficha_desde_items_posibles");
			paso("Clic en un item posible abrió su ficha: " + ctx.computeOnClient(mc -> nombre(((PantallaFicha) mc.gui.screen()).item())));
			ctx.clickScreenButton("Volver");
			ctx.waitFor(pantalla(PantallaItemsPosibles.class, p -> true), 100);
			paso("Volver regresó a items posibles");

			// Pastel: receta de mesa; clic en el azúcar abre su ficha; Volver regresa al pastel.
			abrirFicha(ctx, Items.CAKE);
			ctx.takeScreenshot("fichas_03_pastel");
			int[] azucar = ctx.computeOnClient(mc -> ((PantallaFicha) mc.gui.screen()).centroDeIngrediente(Items.SUGAR).orElseThrow());
			clic(ctx, azucar[0], azucar[1]);
			ctx.waitFor(pantalla(PantallaFicha.class, p -> p.item() == Items.SUGAR && p.cargada()), 200);
			ctx.waitTicks(5);
			ctx.takeScreenshot("fichas_04_azucar");
			paso("Clic en el azúcar del pastel abrió la ficha del azúcar");
			ctx.clickScreenButton("Volver");
			ctx.waitFor(pantalla(PantallaFicha.class, p -> p.item() == Items.CAKE && p.cargada()), 100);
			ctx.waitTicks(5);
			ctx.takeScreenshot("fichas_05_volver_al_pastel");
			paso("Volver regresó a la ficha del pastel");

			// Items que salen de mobs y otros casos.
			abrirFicha(ctx, Items.BREEZE_ROD);
			ctx.takeScreenshot("fichas_06_vara_de_breeze");
			int[] guia = ctx.computeOnClient(mc -> ((PantallaFicha) mc.gui.screen()).centroBotonGuia(0).orElseThrow());
			clic(ctx, guia[0], guia[1]);
			ctx.waitTicks(5);
			ctx.takeScreenshot("fichas_06b_guiame");
			paso("Clic en Guíame de la vara de breeze (envía PedirGuia si el servidor lo acepta)");
			abrirFicha(ctx, Items.BONE);
			ctx.takeScreenshot("fichas_07_hueso");
			abrirFicha(ctx, Items.COOKED_RABBIT);
			ctx.takeScreenshot("fichas_08_conejo_asado");
			abrirFicha(ctx, Items.OAK_SIGN);
			ctx.takeScreenshot("fichas_09_letrero_grupo");
			abrirFicha(ctx, Items.EMERALD);
			ctx.takeScreenshot("fichas_10_esmeralda");
			ctx.getInput().setCursorPos(0, 0);
			ctx.setScreen(() -> null);
			paso("Fichas de vara de breeze, hueso, conejo asado, letrero y esmeralda");

			// En partida: clic en una casilla del cartón abre su ficha.
			sp.getServer().runCommand("execute as @a run venatio equipo rojo");
			sp.getServer().runCommand("venatio iniciar");
			sp.getServer().waitFor(s -> fase() == Partida.Fase.JUGANDO, 20 * 180);
			ctx.waitTicks(40);
			Item primeraCasilla = sp.getServer().computeOnServer(s -> {
				Carton c = Juego.INSTANCIA.partida().carton();
				return c.casillas.get(0).icono.getItem();
			});
			ctx.setScreen(PantallaCarton::new);
			ctx.waitTicks(10);
			int[] casilla = ctx.computeOnClient(mc -> centroCasilla(mc, 0));
			apuntar(ctx, casilla[0], casilla[1]);
			ctx.waitTicks(5);
			ctx.takeScreenshot("fichas_11_carton_en_partida");
			clic(ctx, casilla[0], casilla[1]);
			ctx.waitFor(pantalla(PantallaFicha.class, p -> p.item() == primeraCasilla && p.cargada()), 200);
			ctx.waitTicks(5);
			ctx.takeScreenshot("fichas_12_ficha_de_casilla");
			paso("Clic en la casilla 0 abrió la ficha de " + nombre(primeraCasilla));
			ctx.clickScreenButton("Volver");
			ctx.waitFor(pantalla(PantallaCarton.class, p -> true), 100);
			ctx.clickScreenButton("Items posibles");
			ctx.waitFor(pantalla(PantallaItemsPosibles.class, PantallaItemsPosibles::cargada), 200);
			ctx.getInput().setCursorPos(0, 0);
			ctx.waitTicks(5);
			ctx.takeScreenshot("fichas_13_items_posibles_en_partida");
			paso("Items posibles en partida, con las casillas del cartón marcadas");
			ctx.setScreen(() -> null);

			sp.getServer().runCommand("venatio terminar");
			sp.getServer().waitFor(s -> Juego.INSTANCIA.partida() == null, 20 * 30);
			idioma(ctx, "en_us");
		}
	}

	/** Revisa en el servidor lo que deben traer algunas fichas. */
	private static void comprobarIndices(TestSingleplayerContext sp) {
		sp.getServer().runOnServer(s -> {
			Ficha pastel = Fichas.ficha(Items.CAKE);
			exigir(pastel.recetas().stream().anyMatch(r -> r instanceof ShapedCraftingRecipeDisplay), "el pastel no tiene receta de mesa");
			exigir(!pastel.pista().isEmpty(), "el pastel no tiene pista");

			Ficha vara = Fichas.ficha(Items.BREEZE_ROD);
			exigir(tieneFuente(vara, Fuente.MOB, "breeze"), "la vara de breeze no sale del breeze");
			exigir(tieneFuente(vara, Fuente.ESTRUCTURA, "trial_chambers"), "la vara de breeze no menciona las cámaras de desafío");

			Ficha hueso = Fichas.ficha(Items.BONE);
			Fuente esqueleto = hueso.fuentes().stream()
				.filter(f -> f.tipo() == Fuente.MOB && f.id().getPath().equals("skeleton")).findFirst().orElse(null);
			exigir(esqueleto != null, "el hueso no sale del esqueleto");
			exigir(!esqueleto.biomas().isEmpty(), "el esqueleto no tiene biomas");
			exigir(tieneFuente(hueso, Fuente.PESCA, "fishing"), "el hueso no sale pescando");

			exigir(tieneFuente(Fichas.ficha(Items.COOKED_RABBIT), Fuente.MOB, "rabbit"), "el conejo asado no sale del conejo en llamas");
			exigir(Fichas.ficha(Items.COOKED_RABBIT).recetas().stream().anyMatch(r -> r instanceof FurnaceRecipeDisplay),
				"el conejo asado no tiene receta de horno");
			exigir(tieneFuente(Fichas.ficha(Items.ENCHANTED_BOOK), Fuente.ALDEANO, "librarian"), "el bibliotecario no vende libros encantados");
			exigir(tieneFuente(Fichas.ficha(Items.ENCHANTED_BOOK), Fuente.ESTRUCTURA, "stronghold"), "no hay libros encantados en fortalezas");
			exigir(tieneFuente(Fichas.ficha(Items.FLINT), Fuente.BLOQUE, "gravel"), "el pedernal no sale de la grava");
			exigir(tieneFuente(Fichas.ficha(Items.ENDER_PEARL), Fuente.TRUEQUE, "piglin"), "los piglins no dan perlas de ender");
			exigir(tieneFuente(Fichas.ficha(Items.ARMADILLO_SCUTE), Fuente.MOB, "armadillo"), "la escama no sale del armadillo");
			exigir(tieneFuente(Fichas.ficha(Items.BLUE_ORCHID), Fuente.BIOMA, "swamp"), "la orquídea no apunta al pantano");
			exigir(Fichas.ficha(Items.OAK_SIGN).grupo().map(Identifier::getPath).orElse("").equals("signs"), "el letrero no sabe que vale cualquier letrero");

			int sinPista = 0;
			for (Item i : new Item[]{Items.GLASS_BOTTLE, Items.BELL, Items.TRIAL_KEY, Items.OPEN_EYEBLOSSOM, Items.LAPIS_LAZULI}) {
				if (Fichas.ficha(i).pista().isEmpty()) sinPista++;
			}
			exigir(sinPista == 0, sinPista + " items del pool sin pista");
		});
		paso("Índices del servidor: recetas, mobs, estructuras, pesca, aldeanos, bloques, biomas y grupos");
	}

	private static boolean tieneFuente(Ficha f, int tipo, String path) {
		return f.fuentes().stream().anyMatch(x -> x.tipo() == tipo && x.id().getPath().equals(path));
	}

	private static void exigir(boolean condicion, String error) {
		if (!condicion) throw new AssertionError(error);
	}

	private static void abrirFicha(ClientGameTestContext ctx, Item item) {
		ctx.setScreen(() -> new PantallaFicha(null, item));
		ctx.waitFor(pantalla(PantallaFicha.class, p -> p.item() == item && p.cargada()), 200);
		ctx.getInput().setCursorPos(0, 0);
		ctx.waitTicks(5);
	}

	@SuppressWarnings("unchecked")
	private static <T extends Screen> Predicate<Minecraft> pantalla(Class<T> tipo, Predicate<T> condicion) {
		return mc -> tipo.isInstance(mc.gui.screen()) && condicion.test((T) mc.gui.screen());
	}

	/** Mueve el ratón a coordenadas de interfaz. */
	private static void apuntar(ClientGameTestContext ctx, int x, int y) {
		double escala = ctx.computeOnClient(mc -> (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth());
		ctx.getInput().setCursorPos(x * escala + escala / 2, y * escala + escala / 2);
	}

	/** Clic izquierdo en coordenadas de interfaz. */
	private static void clic(ClientGameTestContext ctx, int x, int y) {
		apuntar(ctx, x, y);
		ctx.waitTick();
		ctx.getInput().pressMouse(0);
		ctx.waitTicks(2);
	}

	/** Centro de una casilla en PantallaCarton. */
	private static int[] centroCasilla(Minecraft mc, int i) {
		return ((PantallaCarton) mc.gui.screen()).centroDe(i);
	}

	private static void idioma(ClientGameTestContext ctx, String codigo) {
		CompletableFuture<Void> recarga = ctx.computeOnClient(mc -> {
			mc.options.languageCode = codigo;
			mc.getLanguageManager().setSelected(codigo);
			return mc.reloadResourcePacks();
		});
		ctx.waitFor(mc -> recarga.isDone() && mc.gui.overlay() == null, 20 * 60);
	}

	private static Partida.Fase fase() {
		Partida p = Juego.INSTANCIA.partida();
		return p == null ? null : p.fase();
	}

	private static String nombre(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	private static void paso(String texto) {
		Venatio.LOG.info("PRUEBA FICHAS ✔ {}", texto);
	}
}
