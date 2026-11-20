package dev.venatio.prueba;

import dev.venatio.Venatio;
import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Partida;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;

/** Modo Rastreo carrera con meta 3: encontrar, saltar y ganar. */
public final class PruebaRastreo implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 600);
			sp.getServer().runCommand("venatio modo rastreo_carrera");
			sp.getServer().runCommand("venatio meta 3");
			sp.getServer().runCommand("execute as @a run venatio equipo rojo");
			sp.getServer().runCommand("venatio iniciar");
			sp.getServer().waitFor(s -> fase(s) == Partida.Fase.JUGANDO, 20 * 180);
			ctx.waitTicks(60);
			ctx.takeScreenshot("rastreo_01_busca");
			paso("Rastreo en juego; busca " + actual(sp));

			String primero = actual(sp);
			sp.getServer().runCommand("give @a " + primero);
			sp.getServer().waitFor(s -> puntos(s) == 1, 200);
			ctx.waitTicks(10);
			ctx.takeScreenshot("rastreo_02_encontrado");
			paso("Encontró " + primero + "; ahora busca " + actual(sp));
			if (actual(sp).equals(primero)) throw new AssertionError("No avanzó al siguiente item");

			String antes = actual(sp);
			sp.getServer().runCommand("execute as @a run venatio saltar");
			ctx.waitTicks(5);
			if (actual(sp).equals(antes)) throw new AssertionError("Saltar no cambió el item");
			paso("Saltó " + antes + "; ahora busca " + actual(sp) + " (sin sumar punto: " + puntos(sp) + ")");

			for (int i = 0; i < 2; i++) {
				int esperado = 2 + i;
				sp.getServer().runCommand("give @a " + actual(sp));
				sp.getServer().waitFor(s -> puntos(s) >= esperado || fase(s) == Partida.Fase.FINAL, 200);
			}
			sp.getServer().waitFor(s -> fase(s) == Partida.Fase.FINAL, 200);
			ctx.waitTicks(20);
			ctx.takeScreenshot("rastreo_03_meta");
			paso("Llegó a la meta de 3 y ganó");
			sp.getServer().waitFor(s -> Juego.INSTANCIA.partida() == null, 20 * 30);
		}
	}

	private static String actual(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(s -> BuiltInRegistries.ITEM.getKey(
			Juego.INSTANCIA.partida().rastreo().actual(ColorEquipo.ROJO).icono().getItem()).toString());
	}

	private static int puntos(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(PruebaRastreo::puntos);
	}

	private static int puntos(MinecraftServer s) {
		Partida p = Juego.INSTANCIA.partida();
		return p == null ? -1 : p.carton().puntos(ColorEquipo.ROJO);
	}

	private static Partida.Fase fase(MinecraftServer s) {
		Partida p = Juego.INSTANCIA.partida();
		return p == null ? null : p.fase();
	}

	private static void paso(String texto) {
		Venatio.LOG.info("PRUEBA RASTREO ✔ {}", texto);
	}
}
