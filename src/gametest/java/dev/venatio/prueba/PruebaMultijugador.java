package dev.venatio.prueba;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.venatio.Venatio;
import dev.venatio.ayudas.Ayudas;
import dev.venatio.carton.Carton;
import dev.venatio.cliente.AyudasCliente;
import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Partida;
import dev.venatio.mundo.Lobby;
import dev.venatio.mundo.MundosPartida;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Varios jugadores con bots de Carpet (/player Bot spawn), como el BingoCam del server viejo.
 * Player0 (cliente real) y Bot1 van en Rojo; Bot2 en Azul. Partida de Lockout.
 */
public final class PruebaMultijugador implements FabricClientGameTest {
	private static final Path ESTADISTICAS = FabricLoader.getInstance().getConfigDir().resolve("venatio").resolve("estadisticas.json");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 600);
			ctx.waitTicks(40);

			// ---------------- Bots
			sp.getServer().runCommand("player Bot1 spawn");
			sp.getServer().runCommand("player Bot2 spawn");
			sp.getServer().waitFor(s -> bot(s, "Bot1") != null && bot(s, "Bot2") != null
				&& Lobby.esLobby(bot(s, "Bot1").level()) && Lobby.esLobby(bot(s, "Bot2").level()), 200);
			paso("Los bots entraron sin el mod y quedaron en el lobby");

			sp.getServer().runCommand("execute as Player0 run venatio equipo rojo");
			sp.getServer().runCommand("execute as Bot1 run venatio equipo rojo");
			sp.getServer().runCommand("execute as Bot2 run venatio equipo azul");
			sp.getServer().runCommand("venatio modo lockout");
			ctx.waitTicks(10);

			String p0 = uuid(sp, "Player0"), b1 = uuid(sp, "Bot1"), b2 = uuid(sp, "Bot2");
			int[] antes = {puntos(p0), puntos(b1), puntos(b2)};

			sp.getServer().runCommand("venatio iniciar");
			sp.getServer().waitFor(s -> fase(s) == Partida.Fase.JUGANDO, 20 * 180);
			ctx.waitTicks(40);
			comprobar(sp.getServer().computeOnServer(s -> {
				Partida p = Juego.INSTANCIA.partida();
				return p.participa(bot(s, "Bot1")) && p.participa(bot(s, "Bot2"))
					&& p.equipoDe(bot(s, "Bot1")) == ColorEquipo.ROJO && p.equipoDe(bot(s, "Bot2")) == ColorEquipo.AZUL
					&& MundosPartida.esDePartida(bot(s, "Bot2").level());
			}), "los tres jugadores están en la partida con su equipo");
			double distancia = sp.getServer().computeOnServer(s -> Math.sqrt(bot(s, "Bot2").distanceToSqr(jugador(s))));
			paso("Partida de Lockout con 2 equipos; Azul aparece a " + Math.round(distancia) + " bloques de Rojo");
			ctx.takeScreenshot("multi_01_partida");

			// ---------------- Lockout: lo que toma Azul ya no lo puede tomar Rojo
			String item0 = item(sp, 0);
			sp.getServer().runCommand("give Bot2 " + item0);
			sp.getServer().waitFor(s -> casilla(0).logros.containsKey(ColorEquipo.AZUL), 100);
			sp.getServer().runCommand("give Player0 " + item0);
			ctx.waitTicks(30);
			comprobar(sp.getServer().computeOnServer(s -> !casilla(0).logros.containsKey(ColorEquipo.ROJO)), "Rojo no pudo tomar la casilla de Azul");
			paso("Lockout: Azul tomó " + item0 + " y Rojo ya no pudo marcarla");
			ctx.takeScreenshot("multi_02_lockout");

			// ---------------- Minimapa con compañero
			sp.getServer().runCommand("execute as Player0 run venatiodev minimapa 120 64");
			sp.getServer().runCommand("execute as Player0 at @s run tp Bot1 ~12 ~ ~-12");
			sp.getServer().runCommand("execute as Player0 at @s run tp Bot2 ~-12 ~ ~-12");
			ctx.waitTicks(60);
			ctx.takeScreenshot("multi_03_minimapa_companero");
			paso("Minimapa con Bot1 (compañero) cerca; Bot2 (rival) no debe aparecer");

			// ---------------- Marcas: las del equipo, no las del rival
			sp.getServer().runCommand("execute as Player0 at @s run tp @s ~ ~ ~ 0 60");
			sp.getServer().runCommand("execute as Bot1 at @s run tp @s ~ ~ ~ 0 60");
			ctx.waitTicks(5);
			ponerMarca(sp, "Player0");
			ponerMarca(sp, "Bot1");
			ponerMarca(sp, "Bot2");
			ctx.waitFor(mc -> AyudasCliente.marcasVisibles() == 2, 100);
			comprobar(sp.getServer().computeOnServer(s -> Ayudas.marcasDe(ColorEquipo.ROJO) == 2 && Ayudas.marcasDe(ColorEquipo.AZUL) == 1),
				"marcas por equipo: Rojo 2, Azul 1");
			ctx.waitTicks(10);
			ctx.takeScreenshot("multi_04_marcas");
			paso("Marcas: Player0 ve las 2 de Rojo (suya y de Bot1) y no la de Azul");

			// ---------------- Un bot muere: Carpet lo saca del server. Al volver a entrar debe regresar a su partida.
			sp.getServer().runCommand("kill Bot1");
			sp.getServer().waitFor(s -> bot(s, "Bot1") == null, 100);
			sp.getServer().runCommand("player Bot1 spawn");
			sp.getServer().waitFor(s -> {
				ServerPlayer b = bot(s, "Bot1");
				return b != null && b.isAlive() && MundosPartida.esDePartida(b.level());
			}, 200);
			comprobar(sp.getServer().computeOnServer(s -> Juego.INSTANCIA.partida().equipoDe(bot(s, "Bot1")) == ColorEquipo.ROJO),
				"Bot1 sigue en Rojo");
			paso("Bot1 murió, salió del server y al volver a entrar regresó a su partida en Rojo");

			// ---------------- El jugador real muere y reaparece en el spawn de su equipo
			ServerPlayer antes0 = sp.getServer().computeOnServer(s -> jugador(s));
			sp.getServer().runCommand("kill Player0");
			ctx.waitForScreen(DeathScreen.class);
			ctx.waitTicks(30);
			ctx.takeScreenshot("multi_05a_muerte");
			ctx.clickScreenButton("deathScreen.respawn");
			sp.getServer().waitFor(s -> jugador(s) != antes0 && jugador(s).isAlive(), 200);
			ctx.waitFor(mc -> mc.gui.screen() == null, 100);
			double alSpawn = sp.getServer().computeOnServer(s -> {
				Partida p = Juego.INSTANCIA.partida();
				ServerPlayer j = jugador(s);
				return MundosPartida.esDePartida(j.level()) ? Math.sqrt(j.blockPosition().distSqr(p.spawnDe(j))) : -1;
			});
			comprobar(alSpawn >= 0 && alSpawn < 16, "reapareció en el mundo de la partida junto al spawn de Rojo (" + Math.round(alSpawn) + " bloques)");
			comprobar(sp.getServer().computeOnServer(s -> jugador(s).getInventory().countItem(casilla(0).icono.getItem()) > 0),
				"conservó el inventario al morir (keepInventory)");
			ctx.waitTicks(20);
			ctx.takeScreenshot("multi_05b_reaparecio");
			paso("Player0 murió, conservó su inventario y reapareció a " + Math.round(alSpawn) + " bloques del spawn de Rojo");

			// ---------------- Rojo gana con la segunda fila; Azul queda segundo con 1 casilla
			int n = sp.getServer().computeOnServer(s -> Juego.INSTANCIA.partida().carton().tamano);
			for (int i = n; i < 2 * n; i++) {
				sp.getServer().runCommand("give Bot1 " + item(sp, i));
				ctx.waitTicks(4);
			}
			sp.getServer().waitFor(s -> fase(s) == Partida.Fase.FINAL, 200);
			comprobar(sp.getServer().computeOnServer(s -> Juego.INSTANCIA.partida().carton().puntos(ColorEquipo.AZUL) == 1),
				"Azul terminó con 1 casilla");
			ctx.waitTicks(20);
			ctx.takeScreenshot("multi_06_final");
			paso("Rojo ganó con una fila completada por Bot1");

			sp.getServer().waitFor(s -> Juego.INSTANCIA.partida() == null, 20 * 30);
			ctx.waitFor(mc -> puntos(p0) > antes[0] && puntos(b2) > antes[2], 200);
			int[] d = {puntos(p0) - antes[0], puntos(b1) - antes[1], puntos(b2) - antes[2]};
			comprobar(d[0] == 3 && d[1] == 3 && d[2] == 2, "puntos de temporada: Player0 +3, Bot1 +3, Bot2 +2 (fue " + d[0] + ", " + d[1] + ", " + d[2] + ")");
			paso("Ranking: Player0 +3, Bot1 +3 (1.º) y Bot2 +2 (2.º)");
			comprobar(sp.getServer().computeOnServer(s -> Lobby.esLobby(bot(s, "Bot1").level()) && Lobby.esLobby(bot(s, "Bot2").level())),
				"los bots volvieron al lobby");
			ctx.waitTicks(20);
			ctx.takeScreenshot("multi_07_lobby");
			paso("Todos de vuelta en el lobby");

			sp.getServer().runCommand("player Bot1 kill");
			sp.getServer().runCommand("player Bot2 kill");
			sp.getServer().runCommand("venatio modo linea");
		}
	}

	// ---------------------------------------------------------------- utilidades

	private static void ponerMarca(TestSingleplayerContext sp, String nombre) {
		sp.getServer().runOnServer(s -> {
			try {
				Method poner = Class.forName("dev.venatio.ayudas.Marcas").getDeclaredMethod("poner", ServerPlayer.class);
				poner.setAccessible(true);
				poner.invoke(null, bot(s, nombre));
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException(e);
			}
		});
	}

	private static String item(TestSingleplayerContext sp, int indice) {
		return sp.getServer().computeOnServer(s -> BuiltInRegistries.ITEM.getKey(casilla(indice).icono.getItem()).toString());
	}

	private static Carton.Casilla casilla(int indice) {
		return Juego.INSTANCIA.partida().carton().casillas.get(indice);
	}

	private static String uuid(TestSingleplayerContext sp, String nombre) {
		return sp.getServer().computeOnServer(s -> bot(s, nombre).getUUID().toString());
	}

	/** Puntos de la temporada en curso según estadisticas.json (0 si todavía no está). */
	private static int puntos(String uuid) {
		try {
			if (!Files.exists(ESTADISTICAS)) return 0;
			JsonObject raiz = JsonParser.parseString(Files.readString(ESTADISTICAS)).getAsJsonObject();
			String temporada = String.valueOf(raiz.get("temporada").getAsInt());
			JsonObject j = raiz.getAsJsonObject("jugadores").getAsJsonObject(uuid);
			if (j == null || !j.has("temporadas") || !j.getAsJsonObject("temporadas").has(temporada)) return 0;
			return j.getAsJsonObject("temporadas").getAsJsonObject(temporada).get("puntos").getAsInt();
		} catch (Exception e) {
			return 0;
		}
	}

	private static ServerPlayer bot(MinecraftServer s, String nombre) {
		return s.getPlayerList().getPlayerByName(nombre);
	}

	private static ServerPlayer jugador(MinecraftServer s) {
		return bot(s, "Player0");
	}

	private static Partida.Fase fase(MinecraftServer s) {
		Partida p = Juego.INSTANCIA.partida();
		return p == null ? null : p.fase();
	}

	private static void comprobar(boolean condicion, String que) {
		if (!condicion) throw new AssertionError("Falló: " + que);
	}

	private static void paso(String texto) {
		Venatio.LOG.info("PRUEBA MULTI ✔ {}", texto);
	}
}
