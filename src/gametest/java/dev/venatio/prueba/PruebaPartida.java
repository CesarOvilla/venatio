package dev.venatio.prueba;

import dev.venatio.Venatio;
import dev.venatio.carton.Carton;
import dev.venatio.cliente.PantallaCarton;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Partida;
import dev.venatio.mundo.MundosPartida;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Set;

/**
 * Partida completa con un cliente real: lobby → iniciar → mundo nuevo → casillas → portal al Nether → victoria → vuelta al lobby.
 * Deja capturas en el directorio de ejecución (screenshots/).
 */
public final class PruebaPartida implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 600);
			ctx.waitTicks(60);
			ctx.takeScreenshot("01_lobby");
			paso("Jugador en el lobby");

			// Pisar la zona azul debe unirlo al equipo Azul.
			sp.getServer().runCommand("execute as @a at @s run tp @s 6.5 65 -13.5 180 0");
			sp.getServer().waitFor(s -> {
				var equipo = s.getScoreboard().getPlayersTeam(jugador(s).getScoreboardName());
				return equipo != null && equipo.getName().equals("venatio_azul");
			}, 100);
			paso("Pisar la zona azul lo unió al equipo Azul");
			sp.getServer().runCommand("execute as @a at @s run tp @s 0.5 65 -8 180 -8");
			ctx.waitTicks(40);
			ctx.takeScreenshot("01b_tableros");

			sp.getServer().runCommand("execute as @a run venatio equipo rojo");
			sp.getServer().runCommand("venatio iniciar");
			sp.getServer().waitFor(s -> fase(s) == Partida.Fase.JUGANDO, 20 * 180);
			ctx.waitTicks(100);
			ctx.takeScreenshot("02_jugando");
			paso("Partida en juego en " + sp.getServer().computeOnServer(s -> jugador(s).level().dimension().toString()));

			List<String> fila = sp.getServer().computeOnServer(s -> {
				Carton c = Juego.INSTANCIA.partida().carton();
				return c.casillas.subList(0, c.tamano).stream()
					.map(cas -> BuiltInRegistries.ITEM.getKey(cas.icono.getItem()).toString()).toList();
			});
			paso("Primera fila del cartón: " + fila);

			// Dos casillas: el HUD debe marcarlas.
			sp.getServer().runCommand("give @a " + fila.get(0));
			sp.getServer().runCommand("give @a " + fila.get(1));
			sp.getServer().waitFor(s -> Juego.INSTANCIA.partida().carton().puntos(dev.venatio.juego.ColorEquipo.ROJO) >= 2, 200);
			ctx.waitTicks(10);
			ctx.takeScreenshot("03_dos_casillas");
			paso("Dos casillas detectadas");

			boolean vision = sp.getServer().computeOnServer(s -> jugador(s).hasEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION));
			if (!vision) throw new AssertionError("La visión nocturna (activada por defecto) no se aplicó");
			paso("Visión nocturna activa");

			// Minado en veta: agachado, romper un mineral rompe los conectados.
			int restantes = sp.getServer().computeOnServer(s -> {
				ServerPlayer p = jugador(s);
				ServerLevel nivel = p.level();
				BlockPos base = p.blockPosition().offset(-3, 0, -3);
				for (int i = 0; i < 4; i++) nivel.setBlockAndUpdate(base.offset(i, 0, 0), Blocks.IRON_ORE.defaultBlockState());
				nivel.setBlockAndUpdate(base.offset(1, 1, 1), Blocks.IRON_ORE.defaultBlockState());
				p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE));
				p.setShiftKeyDown(true);
				p.gameMode.destroyBlock(base);
				p.setShiftKeyDown(false);
				int quedan = 0;
				for (int i = 0; i < 4; i++) if (nivel.getBlockState(base.offset(i, 0, 0)).is(Blocks.IRON_ORE)) quedan++;
				if (nivel.getBlockState(base.offset(1, 1, 1)).is(Blocks.IRON_ORE)) quedan++;
				return quedan;
			});
			if (restantes != 0) throw new AssertionError("El minado en veta dejó " + restantes + " bloques de hierro");
			paso("Minado en veta rompió los 5 bloques de hierro");

			// Simula que otro equipo (Azul) ya tiene dos casillas: deben verse sus puntitos.
			sp.getServer().runOnServer(s -> {
				Carton c = Juego.INSTANCIA.partida().carton();
				c.casillas.get(1).logros.put(dev.venatio.juego.ColorEquipo.AZUL, 3);
				c.casillas.get(7).logros.put(dev.venatio.juego.ColorEquipo.AZUL, 4);
				Juego.INSTANCIA.enviarEstadoATodos();
			});
			ctx.waitTicks(5);
			ctx.setScreen(PantallaCarton::new);
			ctx.waitTicks(10);
			ctx.takeScreenshot("04_pantalla_carton");
			ctx.setScreen(() -> null);
			paso("Pantalla del cartón abierta y cerrada");

			// Portal del Nether construido en el mundo de la partida.
			sp.getServer().runOnServer(PruebaPartida::construirPortal);
			boolean hayPortal = sp.getServer().computeOnServer(s -> {
				ServerPlayer p = jugador(s);
				BlockPos base = p.blockPosition().offset(3, 0, 0);
				return p.level().getBlockState(base.offset(1, 1, 0)).is(Blocks.NETHER_PORTAL);
			});
			if (!hayPortal) throw new AssertionError("El fuego no encendió el portal en el mundo de la partida");
			paso("Portal encendido en el mundo de la partida");
			sp.getServer().runOnServer(s -> {
				ServerPlayer p = jugador(s);
				BlockPos base = p.blockPosition().offset(3, 0, 0);
				p.teleportTo((ServerLevel) p.level(), base.getX() + 1.5, base.getY() + 1, base.getZ() + 0.5, Set.of(), 0f, 0f, true);
			});
			sp.getServer().waitFor(s -> jugador(s).level() == MundosPartida.activa().nether(), 20 * 30);
			ctx.waitTicks(80);
			ctx.takeScreenshot("05_nether_de_la_partida");
			paso("Cruzó al Nether de la partida: " + sp.getServer().computeOnServer(s -> jugador(s).level().dimension().toString()));

			// Completar la fila: debe ganar Rojo.
			for (int i = 2; i < fila.size(); i++) sp.getServer().runCommand("give @a " + fila.get(i));
			sp.getServer().waitFor(s -> fase(s) == Partida.Fase.FINAL, 200);
			ctx.waitTicks(20);
			ctx.takeScreenshot("06_victoria");
			paso("Victoria detectada");

			sp.getServer().waitFor(s -> Juego.INSTANCIA.partida() == null, 20 * 30);
			ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 200);
			ctx.waitTicks(40);
			ctx.takeScreenshot("07_vuelta_al_lobby");
			boolean borrados = sp.getServer().computeOnServer(s -> MundosPartida.activa() == null);
			if (!borrados) throw new AssertionError("Los mundos de la partida no se borraron");
			paso("De vuelta en el lobby y mundos borrados");
		}
	}

	private static void construirPortal(MinecraftServer s) {
		ServerPlayer p = jugador(s);
		ServerLevel nivel = (ServerLevel) p.level();
		BlockPos base = p.blockPosition().offset(3, 0, 0);
		for (int x = 0; x < 4; x++) {
			for (int y = 0; y < 5; y++) {
				boolean marco = x == 0 || x == 3 || y == 0 || y == 4;
				nivel.setBlockAndUpdate(base.offset(x, y, 0), marco ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState());
			}
		}
		nivel.setBlockAndUpdate(base.offset(1, 1, 0), Blocks.FIRE.defaultBlockState());
	}

	private static Partida.Fase fase(MinecraftServer s) {
		Partida p = Juego.INSTANCIA.partida();
		return p == null ? null : p.fase();
	}

	private static ServerPlayer jugador(MinecraftServer s) {
		return s.getPlayerList().getPlayers().get(0);
	}

	private static void paso(String texto) {
		Venatio.LOG.info("PRUEBA ✔ {}", texto);
	}
}
