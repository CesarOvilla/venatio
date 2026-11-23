package dev.venatio.prueba;

import dev.venatio.Venatio;
import dev.venatio.juego.BloquesSuerte;
import dev.venatio.juego.Eventos;
import dev.venatio.juego.Juego;
import dev.venatio.juego.LugarSeguro;
import dev.venatio.juego.Partida;
import dev.venatio.juego.TipoEvento;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bloques de la suerte: no salen con la opción apagada; con ella se colocan en suelo firme, abrirlos da cada tipo de
 * resultado, nunca regalan algo del cartón (solo partes de sus recetas), un bloque abierto no vuelve y los eventos le
 * pasan solo a quien lo abrió.
 */
public final class PruebaSuerte implements FabricClientGameTest {
	private static final long SEMILLA = 20261123L;
	private static final Map<TipoEvento, Holder<MobEffect>> CON_EFECTO = Map.of(TipoEvento.VELOCIDAD, MobEffects.SPEED,
		TipoEvento.LUNAR, MobEffects.JUMP_BOOST, TipoEvento.NIEBLA, MobEffects.DARKNESS, TipoEvento.BRILLO, MobEffects.GLOWING);

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 600);
			server.runCommand("player Bot1 spawn");
			server.waitFor(s -> bot(s) != null, 200);
			server.runCommand("venatio modo linea");
			server.runCommand("venatio semilla " + SEMILLA);
			server.runCommand("execute as Player0 run venatio equipo rojo");
			server.runCommand("execute as Bot1 run venatio equipo azul");

			// ---------------- Apagada (por defecto): no aparece ninguno
			server.runCommand("venatio opcion suerte no");
			iniciar(server);
			ctx.waitTicks(60);
			comprobar(server.computeOnServer(s -> partida().suerte().colocados().isEmpty()), "con la opción apagada no hay bloques");
			server.runCommand("venatio terminar");
			server.waitFor(s -> Juego.INSTANCIA.partida() == null, 20 * 30);
			ctx.waitTicks(10);
			paso("Con la opción apagada no aparece ningún bloque");

			// ---------------- Encendida: se colocan en suelo firme y a cielo abierto
			server.runCommand("venatio opcion suerte si");
			server.runCommand("execute as Player0 run venatio equipo rojo");
			server.runCommand("execute as Bot1 run venatio equipo azul");
			iniciar(server);
			server.waitFor(s -> partida().suerte().colocados().size() >= 2, 20 * 30);
			int colocados = server.computeOnServer(s -> {
				ServerLevel mundo = jugador(s).level();
				List<BlockPos> lista = partida().suerte().colocados();
				comprobar(new HashSet<>(lista).size() == lista.size(), "sin bloques repetidos");
				for (BlockPos pos : lista) {
					comprobar(mundo.getBlockState(pos).is(BloquesSuerte.BLOQUE), "hay un bloque en " + pos);
					comprobar(mundo.getBlockState(pos.above()).isAir(), "aire encima de " + pos);
					comprobar(mundo.getBlockState(pos.below()).isFaceSturdy(mundo, pos.below(), Direction.UP), "suelo firme bajo " + pos);
					comprobar(mundo.getBlockState(pos.below()).getFluidState().isEmpty(), "no flota en agua en " + pos);
				}
				return lista.size();
			});
			paso("Se colocaron " + colocados + " bloques, todos en suelo firme");
			server.runCommand("effect give Player0 minecraft:resistance 300 4 true");
			server.runCommand("effect give Player0 minecraft:fire_resistance 300 0 true");
			server.runCommand("effect give Player0 minecraft:saturation 300 0 true");

			BlockPos junto = server.computeOnServer(s -> jugador(s).blockPosition().offset(3, 1, 0));
			server.runCommand("execute as Player0 at @s run setblock " + junto.getX() + " " + junto.getY() + " " + junto.getZ() + " venatio:bloque_suerte");
			ctx.getInput().lookAt(junto);
			ctx.waitTicks(15);
			ctx.takeScreenshot("suerte_01_bloque");
			server.runCommand("execute as Player0 at @s run setblock " + junto.getX() + " " + junto.getY() + " " + junto.getZ() + " minecraft:air");

			// ---------------- Las partes de crafteo nunca son algo que pida la partida
			int partes = server.computeOnServer(s -> {
				int n = 0;
				for (int i = 0; i < 300; i++) {
					ItemStack parte = partida().suerte().parteDeCrafteo(jugador(s));
					if (parte.isEmpty()) continue;
					comprobar(!partida().suerte().pideLaPartida(parte), "la parte " + parte + " la pide el cartón");
					n++;
				}
				return n;
			});
			comprobar(partes > 0, "hubo partes de crafteo");
			paso("300 sorteos de partes: " + partes + " con premio, ninguno es un item del cartón");

			// ---------------- Un bloque abierto no vuelve a salir
			BlockPos abierto = server.computeOnServer(s -> partida().suerte().colocados().get(0));
			BloquesSuerte.forzarTipo = BloquesSuerte.Tipo.INUTIL;
			server.runOnServer(s -> jugador(s).gameMode.destroyBlock(abierto));
			ctx.waitTicks(50);
			comprobar(server.computeOnServer(s -> !jugador(s).level().getBlockState(abierto).is(BloquesSuerte.BLOQUE)), "el bloque abierto no reaparece");
			paso("Un bloque abierto no reaparece");

			// ---------------- Cada tipo de resultado
			Set<String> vistos = new HashSet<>();
			for (BloquesSuerte.Tipo tipo : List.of(BloquesSuerte.Tipo.BUENO, BloquesSuerte.Tipo.INUTIL, BloquesSuerte.Tipo.MALO)) {
				BloquesSuerte.forzarTipo = tipo;
				for (int i = 0; i < 25; i++) {
					vistos.add(abrirJunto(ctx, server));
					ctx.waitTick();
				}
			}
			server.runCommand("kill @e[type=minecraft:tnt]");
			server.runCommand("execute as Player0 at @s run fill ~-2 ~ ~-2 ~2 ~2 ~2 minecraft:air replace minecraft:cobweb");
			comprobar(vistos.containsAll(List.of("parte", "premio", "inutil", "monstruos", "telaranas", "tnt")), "salieron los resultados: " + vistos);
			ctx.waitTicks(5);
			paso("Abrió 75 bloques; resultados: " + vistos);

			// ---------------- Los eventos le pasan solo a quien abrió el bloque (el teletransporte, al final)
			BloquesSuerte.forzarTipo = BloquesSuerte.Tipo.EVENTO;
			Eventos.forzarDestino = LugarSeguro.Destino.SUELO;
			List<TipoEvento> eventos = new ArrayList<>(BloquesSuerte.EVENTOS);
			eventos.remove(TipoEvento.TELETRANSPORTE);
			eventos.add(TipoEvento.TELETRANSPORTE);
			for (TipoEvento t : eventos) {
				BloquesSuerte.forzarEvento = t;
				double[] botAntes = server.computeOnServer(s -> new double[]{bot(s).getX(), bot(s).getZ()});
				double[] yoAntes = server.computeOnServer(s -> new double[]{jugador(s).getX(), jugador(s).getZ()});
				String salio = abrirJunto(ctx, server);
				comprobar(salio.equals("evento:" + t.id), "salió " + salio + " en vez de " + t.id);
				Holder<MobEffect> efecto = CON_EFECTO.get(t);
				if (efecto != null) {
					comprobar(server.computeOnServer(s -> jugador(s).hasEffect(efecto)), t.id + ": lo recibe quien abrió");
					comprobar(server.computeOnServer(s -> !bot(s).hasEffect(efecto)), t.id + ": el rival no lo recibe");
				}
				if (t == TipoEvento.TELETRANSPORTE) {
					comprobar(server.computeOnServer(s -> Math.hypot(jugador(s).getX() - yoAntes[0], jugador(s).getZ() - yoAntes[1]) > 100),
						"el teletransporte movió a quien abrió");
				}
				comprobar(server.computeOnServer(s -> Math.hypot(bot(s).getX() - botAntes[0], bot(s).getZ() - botAntes[1]) < 3),
					t.id + ": el rival no se movió");
				ctx.waitTicks(3);
			}
			paso("Los " + eventos.size() + " eventos le pasaron solo a quien abrió el bloque");

			BloquesSuerte.forzarTipo = null;
			BloquesSuerte.forzarEvento = null;
			Eventos.forzarDestino = null;
			server.runCommand("venatio terminar");
			server.waitFor(s -> Juego.INSTANCIA.partida() == null, 20 * 30);
			server.runCommand("venatio opcion suerte no");
			server.runCommand("player Bot1 kill");
		} finally {
			BloquesSuerte.forzarTipo = null;
			BloquesSuerte.forzarEvento = null;
			Eventos.forzarDestino = null;
		}
	}

	/** Pone un bloque junto al jugador, lo rompe como él y devuelve qué salió. */
	private static String abrirJunto(ClientGameTestContext ctx, TestServerContext server) {
		return server.computeOnServer(s -> {
			ServerPlayer p = jugador(s);
			BlockPos pos = p.blockPosition().offset(2, 1, 0);
			p.level().setBlockAndUpdate(pos, BloquesSuerte.BLOQUE.defaultBlockState());
			comprobar(p.gameMode.destroyBlock(pos), "se pudo romper el bloque");
			comprobar(!p.level().getBlockState(pos).is(BloquesSuerte.BLOQUE), "el bloque se abrió");
			return partida().suerte().ultimo();
		});
	}

	private static void iniciar(TestServerContext server) {
		server.runCommand("venatio iniciar");
		server.waitFor(s -> {
			Partida p = Juego.INSTANCIA.partida();
			return p != null && p.fase() == Partida.Fase.JUGANDO;
		}, 20 * 180);
	}

	private static Partida partida() {
		return Juego.INSTANCIA.partida();
	}

	private static ServerPlayer jugador(MinecraftServer s) {
		return s.getPlayerList().getPlayerByName("Player0");
	}

	private static ServerPlayer bot(MinecraftServer s) {
		return s.getPlayerList().getPlayerByName("Bot1");
	}

	private static void comprobar(boolean condicion, String que) {
		if (!condicion) throw new AssertionError("Falló: " + que);
	}

	private static void paso(String texto) {
		Venatio.LOG.info("PRUEBA SUERTE ✔ {}", texto);
	}
}
