package dev.venatio.prueba;

import dev.venatio.Venatio;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Partida;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Los puntos de aparición de los equipos quedan a cielo abierto, sobre suelo firme y no dentro de una cueva o bajo
 * un saliente. Varias partidas con semillas distintas; deja una captura de cada aparición.
 */
public final class PruebaSpawns implements FabricClientGameTest {
	private static final long[] SEMILLAS = {11, -4242, 31337};

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 600);
			sp.getServer().runCommand("execute as Player0 run venatio equipo rojo");
			int malos = 0;
			for (long semilla : SEMILLAS) {
				sp.getServer().runCommand("venatio semilla " + semilla);
				sp.getServer().runCommand("venatio iniciar");
				sp.getServer().waitFor(s -> fase() == Partida.Fase.CUENTA_ATRAS || fase() == Partida.Fase.JUGANDO, 20 * 180);
				ctx.waitTicks(40);
				String informe = sp.getServer().computeOnServer(PruebaSpawns::revisar);
				ctx.takeScreenshot("spawn_" + semilla);
				Venatio.LOG.info("PRUEBA SPAWNS {} semilla {}: {}", informe.startsWith("OK") ? "✔" : "✘", semilla, informe);
				if (!informe.startsWith("OK")) malos++;
				sp.getServer().runCommand("venatio terminar");
				sp.getServer().waitFor(s -> Juego.INSTANCIA.partida() == null, 20 * 30);
				ctx.waitTicks(20);
			}
			sp.getServer().runCommand("venatio semilla aleatoria");
			if (malos > 0) throw new AssertionError("Falló: " + malos + " de " + SEMILLAS.length + " apariciones no quedaron a cielo abierto");
		}
	}

	private static String revisar(MinecraftServer s) {
		ServerPlayer p = s.getPlayerList().getPlayerByName("Player0");
		ServerLevel nivel = p.level();
		BlockPos pies = p.blockPosition();
		int alturaSolida = nivel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pies.getX(), pies.getZ());
		int superficie = nivel.getHeight(Heightmap.Types.WORLD_SURFACE, pies.getX(), pies.getZ());
		boolean libre = nivel.getBlockState(pies).getCollisionShape(nivel, pies).isEmpty()
			&& nivel.getBlockState(pies.above()).getCollisionShape(nivel, pies.above()).isEmpty();
		boolean suelo = !nivel.getBlockState(pies.below()).getCollisionShape(nivel, pies.below()).isEmpty();
		String datos = "pies " + pies.toShortString() + " · tope sólido " + alturaSolida + " · superficie " + superficie
			+ " · libre " + libre + " · suelo " + suelo + " · bajo los pies " + nivel.getBlockState(pies.below()).getBlock();
		boolean ok = libre && suelo && pies.getY() >= alturaSolida;
		return (ok ? "OK " : "MAL ") + datos;
	}

	private static Partida.Fase fase() {
		Partida p = Juego.INSTANCIA.partida();
		return p == null ? null : p.fase();
	}
}
