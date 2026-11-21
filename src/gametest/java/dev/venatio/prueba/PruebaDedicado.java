package dev.venatio.prueba;

import com.mojang.blaze3d.platform.InputConstants;
import dev.venatio.Venatio;
import dev.venatio.carton.Exclusiones;
import dev.venatio.cliente.PantallaCarton;
import dev.venatio.cliente.fichas.PantallaFicha;
import dev.venatio.cliente.fichas.PantallaItemsPosibles;
import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Partida;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.Properties;
import java.util.function.Predicate;

/**
 * Lo que se probó jugando en exaroton: servidor dedicado (no un jugador), interfaz en 2x, partida de Rastreo.
 * Cartón grande con un cartón de 1×1 sin textos encimados, y en "Items posibles" el clic izquierdo abre la ficha
 * (no toca el pool) y el clic derecho del operador quita y vuelve a poner.
 */
public final class PruebaDedicado implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		Properties props = new Properties();
		props.setProperty("view-distance", "12");
		props.setProperty("simulation-distance", "8");
		props.setProperty("spawn-protection", "0");
		props.setProperty("allow-flight", "true");
		try (TestDedicatedServerContext server = ctx.worldBuilder().createServer(props);
			 TestDedicatedServerConnection ignored = server.connect()) {
			ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 1200);
			server.runCommand("op Player0");
			server.runOnServer(s -> Exclusiones.lista().forEach(e -> Exclusiones.cambiar(e, false)));
			server.runCommand("venatio modo rastreo");
			server.runCommand("execute as Player0 run venatio equipo rojo");
			server.runCommand("venatio iniciar");
			server.waitFor(s -> fase() == Partida.Fase.JUGANDO, 20 * 180);
			ctx.waitTicks(40);

			ctx.getInput().resizeWindow(1920, 1080);
			ctx.runOnClient(mc -> {
				mc.options.guiScale().set(2);
				mc.resizeGui();
			});
			ctx.waitTicks(10);

			ctx.setScreen(PantallaCarton::new);
			ctx.waitTicks(20);
			ctx.takeScreenshot("dedicado_01_carton_rastreo_2x");
			paso("Cartón de Rastreo en 2x con servidor dedicado (revisar la captura: nada encimado)");

			int[] boton = ctx.computeOnClient(mc -> centroBoton(mc.gui.screen(), "venatio.fichas.boton_items_posibles"));
			clic(ctx, boton[0], boton[1], InputConstants.MOUSE_BUTTON_LEFT);
			ctx.waitFor(pantalla(PantallaItemsPosibles.class, PantallaItemsPosibles::cargada), 200);
			ctx.waitTicks(10);
			ctx.takeScreenshot("dedicado_02_items_posibles");

			String objetivo = server.computeOnServer(s -> BuiltInRegistries.ITEM.getKey(
				Juego.INSTANCIA.partida().rastreo().actual(ColorEquipo.ROJO).icono().getItem()).toString());
			int indice = ctx.computeOnClient(mc -> ((PantallaItemsPosibles) mc.gui.screen()).indiceDe(objetivo));
			if (indice < 0) throw new AssertionError("El objetivo " + objetivo + " no está en Items posibles");
			int[] centro = ctx.computeOnClient(mc -> ((PantallaItemsPosibles) mc.gui.screen()).centroDe(indice));

			clic(ctx, centro[0], centro[1], InputConstants.MOUSE_BUTTON_LEFT);
			ctx.waitFor(pantalla(PantallaFicha.class, PantallaFicha::cargada), 200);
			ctx.waitTicks(10);
			ctx.takeScreenshot("dedicado_03_ficha");
			if (server.computeOnServer(s -> Exclusiones.excluida(objetivo))) {
				throw new AssertionError("El clic izquierdo quitó " + objetivo + " del pool");
			}
			paso("Clic izquierdo en " + objetivo + " abrió su ficha y no lo quitó del pool");

			ctx.setScreen(() -> new PantallaItemsPosibles(null, java.util.Set.of()));
			ctx.waitFor(pantalla(PantallaItemsPosibles.class, PantallaItemsPosibles::cargada), 200);
			ctx.waitTicks(5);
			clic(ctx, centro[0], centro[1], InputConstants.MOUSE_BUTTON_RIGHT);
			server.waitFor(s -> Exclusiones.excluida(objetivo), 100);
			clic(ctx, centro[0], centro[1], InputConstants.MOUSE_BUTTON_RIGHT);
			server.waitFor(s -> !Exclusiones.excluida(objetivo), 100);
			paso("Clic derecho del operador quitó " + objetivo + " del pool y lo volvió a poner");

			ctx.getInput().setCursorPos(0, 0);
			ctx.setScreen(() -> null);
			ctx.runOnClient(mc -> {
				mc.options.guiScale().set(0);
				mc.resizeGui();
			});
			ctx.getInput().resizeWindow(854, 480);
			server.runCommand("venatio terminar");
			server.waitFor(s -> Juego.INSTANCIA.partida() == null, 20 * 30);
		}
	}

	private static int[] centroBoton(Screen pantalla, String clave) {
		for (var hijo : pantalla.children()) {
			if (hijo instanceof Button b && b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(clave)) {
				AbstractWidget w = b;
				return new int[]{w.getX() + w.getWidth() / 2, w.getY() + w.getHeight() / 2};
			}
		}
		throw new AssertionError("No hay botón " + clave);
	}

	@SuppressWarnings("unchecked")
	private static <T extends Screen> Predicate<Minecraft> pantalla(Class<T> tipo, Predicate<T> condicion) {
		return mc -> tipo.isInstance(mc.gui.screen()) && condicion.test((T) mc.gui.screen());
	}

	private static void clic(ClientGameTestContext ctx, int x, int y, int boton) {
		double escala = ctx.computeOnClient(mc -> (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth());
		ctx.getInput().setCursorPos(x * escala + escala / 2, y * escala + escala / 2);
		ctx.waitTick();
		ctx.getInput().pressMouse(boton);
		ctx.waitTicks(2);
	}

	private static Partida.Fase fase() {
		Partida p = Juego.INSTANCIA.partida();
		return p == null ? null : p.fase();
	}

	private static void paso(String texto) {
		Venatio.LOG.info("PRUEBA DEDICADO ✔ {}", texto);
	}
}
