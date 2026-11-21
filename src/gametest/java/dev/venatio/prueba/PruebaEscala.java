package dev.venatio.prueba;

import dev.venatio.Venatio;
import dev.venatio.cliente.PantallaCarton;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Partida;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * La interfaz de Venatio a varias escalas de interfaz y tamaños de ventana: HUD con cartón y minimapa,
 * cartón grande (B) y tienda (H). Solo deja capturas; hay que revisarlas a ojo.
 */
public final class PruebaEscala implements FabricClientGameTest {
	private record Caso(String nombre, int ancho, int alto, int escala) {}

	private static final Caso[] CASOS = {
		new Caso("1080p_escala2", 1920, 1080, 2),
		new Caso("1080p_escala3", 1920, 1080, 3),
		new Caso("1080p_escala4", 1920, 1080, 4),
		new Caso("720p_escala3", 1280, 720, 3),
		new Caso("720p_auto", 1280, 720, 0),
	};

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 600);
			sp.getServer().runCommand("execute as Player0 run venatio equipo rojo");
			sp.getServer().runCommand("venatio iniciar");
			sp.getServer().waitFor(s -> {
				Partida p = Juego.INSTANCIA.partida();
				return p != null && p.fase() == Partida.Fase.JUGANDO;
			}, 20 * 180);
			ctx.waitTicks(60);
			sp.getServer().runCommand("execute as Player0 run venatiodev minimapa 600 64");
			sp.getServer().runCommand("give Player0 minecraft:iron_ingot 16");
			ctx.waitTicks(40);

			for (Caso c : CASOS) {
				ctx.getInput().resizeWindow(c.ancho(), c.alto());
				ctx.runOnClient(mc -> {
					mc.options.guiScale().set(c.escala());
					mc.resizeGui();
				});
				ctx.waitTicks(10);
				ctx.takeScreenshot("escala_" + c.nombre() + "_hud");

				ctx.setScreen(PantallaCarton::new);
				ctx.waitTicks(10);
				ctx.takeScreenshot("escala_" + c.nombre() + "_carton");
				ctx.setScreen(() -> null);
				ctx.waitTicks(5);

				ctx.getInput().pressKey(InputConstants.KEY_H);
				ctx.waitTicks(15);
				ctx.takeScreenshot("escala_" + c.nombre() + "_tienda");
				ctx.setScreen(() -> null);
				ctx.waitTicks(5);
				Venatio.LOG.info("PRUEBA ESCALA ✔ capturas de {}", c.nombre());
			}
			ctx.runOnClient(mc -> {
				mc.options.guiScale().set(0);
				mc.resizeGui();
			});
			ctx.getInput().resizeWindow(854, 480);
			sp.getServer().runCommand("venatio terminar");
			sp.getServer().waitFor(s -> Juego.INSTANCIA.partida() == null, 20 * 30);
		}
	}
}
