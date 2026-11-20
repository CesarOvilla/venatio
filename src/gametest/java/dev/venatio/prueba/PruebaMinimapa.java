package dev.venatio.prueba;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import dev.venatio.Venatio;
import dev.venatio.cliente.minimapa.Minimapa;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Partida;
import dev.venatio.mundo.MundosPartida;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Set;
import java.util.UUID;

/**
 * Minimapa con un cliente real: M sin permiso, permiso con /venatiodev, mapa en el mundo de la partida, moverse,
 * compañero, efectos activos, ocultar con M, otro radio, permiso que vence, aviso en el Nether y permiso perdido al volver al lobby.
 * Capturas en screenshots/minimapa_*.
 */
public final class PruebaMinimapa implements FabricClientGameTest {
	private static final int ID_COMPANERO = -4242;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 600);
			ctx.waitTicks(40);
			sp.getServer().runCommand("execute as @a run venatio equipo rojo");
			sp.getServer().runCommand("venatio iniciar");
			sp.getServer().waitFor(s -> fase(s) == Partida.Fase.JUGANDO, 20 * 180);
			ctx.waitTicks(60);
			paso("Partida en juego");

			// Sin permiso, la M solo avisa.
			ctx.getInput().pressKey(InputConstants.KEY_M);
			ctx.waitTicks(5);
			afirmar(ctx.computeOnClient(mc -> !Minimapa.mostrando()), "Con M y sin permiso no debe mostrarse");
			ctx.takeScreenshot("minimapa_01_sin_permiso");
			paso("M sin permiso: aviso y nada más");

			// Permiso con el comando de pruebas; lo lleva a tierra firme a unos bloques, mirando al norte.
			sp.getServer().runCommand("execute as @a run venatiodev minimapa 120 64");
			ctx.waitFor(mc -> Minimapa.mostrando() && Minimapa.radio() == 64, 100);
			sp.getServer().runOnServer(s -> aTierra(s, 180f));
			ctx.waitTicks(100);
			ctx.takeScreenshot("minimapa_02_visible");
			paso("Minimapa visible en el mundo de la partida");

			// 32 bloques al norte: el terreno baja en el mapa y entra lo nuevo por arriba.
			sp.getServer().runOnServer(s -> aSuperficie(s, 0, -32, 180f));
			ctx.waitTicks(60);
			ctx.takeScreenshot("minimapa_03_al_norte");
			paso("Minimapa después de moverse al norte");

			// Compañero: un jugador del lado del cliente con el nombre de un miembro del equipo (el propio, que es el único).
			ctx.runOnClient(mc -> {
				RemotePlayer otro = new RemotePlayer(mc.level, new GameProfile(UUID.randomUUID(), mc.player.getGameProfile().name()));
				otro.setId(ID_COMPANERO);
				otro.snapTo(mc.player.getX() + 25, mc.player.getY(), mc.player.getZ() - 18, 0f, 0f);
				mc.level.addEntity(otro);
			});
			ctx.waitTicks(5);
			ctx.takeScreenshot("minimapa_04_companero");
			ctx.runOnClient(mc -> mc.level.removeEntity(ID_COMPANERO, Entity.RemovalReason.DISCARDED));
			paso("Compañero dibujado");

			// Con efectos buenos y malos, el mapa baja para no taparlos.
			sp.getServer().runCommand("effect give @a minecraft:night_vision 60 0");
			sp.getServer().runCommand("effect give @a minecraft:mining_fatigue 60 0");
			ctx.waitTicks(10);
			ctx.takeScreenshot("minimapa_05_efectos");
			sp.getServer().runCommand("effect clear @a");
			paso("Minimapa debajo de los efectos");

			// M lo oculta y lo vuelve a mostrar.
			ctx.getInput().pressKey(InputConstants.KEY_M);
			ctx.waitTicks(3);
			afirmar(ctx.computeOnClient(mc -> !Minimapa.mostrando() && Minimapa.vigente()), "M debía ocultarlo sin quitar el permiso");
			ctx.takeScreenshot("minimapa_06_oculto");
			ctx.getInput().pressKey(InputConstants.KEY_M);
			ctx.waitTicks(3);
			afirmar(ctx.computeOnClient(mc -> Minimapa.mostrando()), "M debía volver a mostrarlo");
			paso("M oculta y muestra");

			// Radio más corto: más acercamiento.
			sp.getServer().runCommand("execute as @a run venatiodev minimapa 120 24");
			ctx.waitFor(mc -> Minimapa.radio() == 24, 100);
			sp.getServer().runCommand("execute as @a at @s run tp @s ~ ~ ~ 135 20");
			ctx.waitTicks(30);
			ctx.takeScreenshot("minimapa_07_radio_24");
			paso("Radio 24");

			// El permiso vence solo.
			sp.getServer().runCommand("execute as @a run venatiodev minimapa 3 64");
			ctx.waitFor(mc -> Minimapa.mostrando() && Minimapa.radio() == 64, 100);
			ctx.waitFor(mc -> !Minimapa.vigente(), 20 * 6);
			ctx.waitTicks(5);
			afirmar(ctx.computeOnClient(mc -> !Minimapa.mostrando()), "El minimapa debía desaparecer al vencer");
			ctx.takeScreenshot("minimapa_08_vencido");
			paso("Permiso vencido");

			// Nether de la partida: solo el aviso.
			sp.getServer().runCommand("execute as @a run venatiodev minimapa 90 64");
			ctx.waitFor(mc -> Minimapa.mostrando(), 100);
			sp.getServer().runOnServer(PruebaMinimapa::alNether);
			ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("_nether"), 20 * 30);
			ctx.waitTicks(60);
			afirmar(ctx.computeOnClient(mc -> Minimapa.mostrando() && !Minimapa.funcionaEn(mc.level)), "En el Nether debía verse solo el aviso");
			ctx.takeScreenshot("minimapa_09_nether");
			paso("Aviso en el Nether");

			// Al terminar la partida y volver al lobby, el permiso se pierde.
			sp.getServer().runCommand("venatio terminar");
			sp.getServer().waitFor(s -> Juego.INSTANCIA.partida() == null, 20 * 30);
			ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 200);
			ctx.waitTicks(20);
			afirmar(ctx.computeOnClient(mc -> !Minimapa.vigente()), "El permiso no debía sobrevivir a la partida");
			ctx.takeScreenshot("minimapa_10_lobby");
			paso("Permiso perdido al volver al lobby");
		}
	}

	/** Lleva al jugador a tierra firme (sin agua ni copas de árbol) a unos 30–120 bloques, mirando con el ángulo dado. */
	private static void aTierra(MinecraftServer s, float angulo) {
		ServerPlayer p = jugador(s);
		ServerLevel nivel = (ServerLevel) p.level();
		BlockPos origen = p.blockPosition();
		for (int d = 30; d <= 120; d += 10) {
			for (int k = 0; k < 8; k++) {
				int x = origen.getX() + (int) (Math.cos(k * Math.PI / 4) * d);
				int z = origen.getZ() + (int) (Math.sin(k * Math.PI / 4) * d);
				int y = nivel.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
				BlockState suelo = nivel.getBlockState(new BlockPos(x, y - 1, z));
				if (suelo.getFluidState().isEmpty() && !suelo.is(BlockTags.LEAVES)) {
					p.teleportTo(nivel, x + 0.5, y, z + 0.5, Set.of(), angulo, 30f, true);
					return;
				}
			}
		}
		throw new AssertionError("No hay tierra firme cerca del spawn");
	}

	/** Lleva al jugador a la superficie a (dx, dz) de donde está. */
	private static void aSuperficie(MinecraftServer s, int dx, int dz, float angulo) {
		ServerPlayer p = jugador(s);
		ServerLevel nivel = (ServerLevel) p.level();
		int x = p.getBlockX() + dx, z = p.getBlockZ() + dz;
		int y = nivel.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		p.teleportTo(nivel, x + 0.5, y, z + 0.5, Set.of(), angulo, 30f, true);
	}

	/** Al Nether de la partida, sobre una plataforma con aire alrededor. */
	private static void alNether(MinecraftServer s) {
		ServerLevel nether = MundosPartida.activa().nether();
		BlockPos base = new BlockPos(0, 70, 0);
		for (int x = -2; x <= 2; x++) {
			for (int z = -2; z <= 2; z++) {
				nether.setBlockAndUpdate(base.offset(x, -1, z), Blocks.OBSIDIAN.defaultBlockState());
				for (int y = 0; y < 3; y++) nether.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState());
			}
		}
		jugador(s).teleportTo(nether, 0.5, 70, 0.5, Set.of(), 0f, 0f, true);
	}

	private static Partida.Fase fase(MinecraftServer s) {
		Partida p = Juego.INSTANCIA.partida();
		return p == null ? null : p.fase();
	}

	private static ServerPlayer jugador(MinecraftServer s) {
		return s.getPlayerList().getPlayers().get(0);
	}

	private static void afirmar(boolean condicion, String mensaje) {
		if (!condicion) throw new AssertionError(mensaje);
	}

	private static void paso(String texto) {
		Venatio.LOG.info("PRUEBA MINIMAPA ✔ {}", texto);
	}
}
