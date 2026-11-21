package dev.venatio.prueba;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import dev.venatio.Venatio;
import dev.venatio.carton.Carton;
import dev.venatio.cliente.PantallaAnfitrion;
import dev.venatio.cliente.PantallaFinal;
import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Modo;
import dev.venatio.juego.Partida;
import dev.venatio.lobby.ConstructorLobby;
import dev.venatio.mundo.Lobby;
import dev.venatio.red.PaquetesStats;
import dev.venatio.stats.Estadisticas;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Estadísticas, ranking, pantalla final y panel del anfitrión con un cliente real:
 * panel del atril → cambiar modo → partida corta hasta ganar → pantalla final (Esc y B) → tableros del lobby
 * → comandos de chat → JSON escrito → temporada nueva → archivo dañado no tumba el server.
 */
public final class PruebaStats implements FabricClientGameTest {
	private static final Path ARCHIVO = FabricLoader.getInstance().getConfigDir().resolve("venatio").resolve("estadisticas.json");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 600);
			ctx.waitTicks(60);
			String uuid = sp.getServer().computeOnServer(s -> jugador(s).getUUID().toString());
			String nombre = sp.getServer().computeOnServer(s -> jugador(s).getGameProfile().name());
			JsonObject antes = jugadorEnJson(uuid);
			int temporada = sp.getServer().computeOnServer(s -> Estadisticas.temporada());
			paso("Jugador " + nombre + " en el lobby; temporada " + temporada + "; partidas previas " + entero(antes, "partidas"));
			sp.getServer().runCommand("venatio modo linea");
			sp.getServer().runCommand("venatio tiempo 0");

			// ---------------- Panel del anfitrión: clic derecho en el atril
			// El jugador de prueba no es operador: primero se ve en modo lectura y el servidor rechaza los cambios.
			var atril = ConstructorLobby.panelAnfitrion();
			sp.getServer().runCommand("execute as @a at @s run tp @s " + (atril.getX() + 0.5) + " " + atril.getY() + " " + (atril.getZ() - 1.5) + " 0 30");
			ctx.waitTicks(20);
			ctx.getInput().lookAt(atril);
			ctx.waitTicks(5);
			ctx.getInput().pressKey(o -> o.keyUse);
			ctx.waitForScreen(PantallaAnfitrion.class);
			ctx.waitTicks(10);
			ctx.takeScreenshot("stats_01_panel_solo_lectura");
			paso("Clic derecho en el atril abrió el panel del anfitrión (solo lectura, no es operador)");

			ctx.clickScreenButton("venatio.stats.panel.modo");
			ctx.runOnClient(mc -> ClientPlayNetworking.send(new PaquetesStats.AccionPanel(PaquetesStats.AccionPanel.MODO, "apagon", 0)));
			ctx.waitTicks(20);
			comprobar(sp.getServer().computeOnServer(s -> Juego.INSTANCIA.config.modo == Modo.LINEA), "sin ser operador el modo no cambia");
			paso("Sin ser operador el servidor rechazó el cambio de modo");
			ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
			ctx.waitFor(mc -> mc.gui.screen() == null, 40);

			sp.getServer().runOnServer(s -> s.getPlayerList().op(jugador(s).nameAndId()));
			ctx.waitTicks(5);
			ctx.getInput().pressKey(o -> o.keyUse);
			ctx.waitForScreen(PantallaAnfitrion.class);
			ctx.waitTicks(10);
			ctx.takeScreenshot("stats_02_panel_operador");
			paso("Como operador el panel se abre editable");
			ctx.clickScreenButton("venatio.stats.panel.pestana.opciones");
			ctx.waitTicks(3);
			ctx.takeScreenshot("stats_02b_panel_opciones");
			ctx.clickScreenButton("venatio.stats.panel.pestana.variantes");
			ctx.waitTicks(3);
			ctx.takeScreenshot("stats_02c_panel_variantes");
			ctx.clickScreenButton("venatio.stats.panel.pestana.partida");
			ctx.waitTicks(3);
			paso("Pestañas del panel: Opciones y Variantes");
			ctx.clickScreenButton("venatio.ajuste.dificultad");
			sp.getServer().waitFor(s -> Juego.INSTANCIA.config.dificultad == dev.venatio.carton.Dificultad.DIFICIL, 40);
			ctx.waitTicks(3);
			ctx.takeScreenshot("stats_02d_panel_dificultad");
			sp.getServer().runCommand("venatio dificultad normal");
			ctx.waitTicks(3);
			paso("El panel cambia la dificultad del cartón (normal → difícil)");

			ctx.clickScreenButton("venatio.stats.panel.modo");
			sp.getServer().waitFor(s -> Juego.INSTANCIA.config.modo == Modo.APAGON, 100);
			ctx.waitTicks(10);
			ctx.takeScreenshot("stats_03_panel_modo_apagon");
			paso("El panel cambió el modo a Apagón en el servidor");

			ctx.clickScreenButton("venatio.stats.panel.pvp");
			sp.getServer().waitFor(s -> Juego.INSTANCIA.config.pvp, 100);
			ctx.clickScreenButton("venatio.stats.panel.pvp");
			sp.getServer().waitFor(s -> !Juego.INSTANCIA.config.pvp, 100);
			paso("El panel activó y desactivó el PvP");

			ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
			ctx.waitFor(mc -> mc.gui.screen() == null, 40);
			sp.getServer().runCommand("venatio modo linea");
			sp.getServer().waitFor(s -> Juego.INSTANCIA.config.modo == Modo.LINEA, 40);
			paso("Esc cerró el panel; modo de vuelta a 5 en línea");

			// ---------------- Partida corta hasta ganar
			sp.getServer().runCommand("execute as @a run venatio equipo rojo");
			sp.getServer().runCommand("venatio iniciar");
			sp.getServer().waitFor(s -> fase(s) == Partida.Fase.JUGANDO, 20 * 180);
			ctx.waitTicks(40);
			List<String> fila = sp.getServer().computeOnServer(s -> {
				Carton c = Juego.INSTANCIA.partida().carton();
				return c.casillas.subList(0, c.tamano).stream()
					.map(cas -> BuiltInRegistries.ITEM.getKey(cas.icono.getItem()).toString()).toList();
			});
			paso("Primera fila: " + fila);
			// Con pausas, para que la línea de tiempo tenga minutos distintos.
			for (int i = 0; i < fila.size(); i++) {
				sp.getServer().runCommand("give @a " + fila.get(i));
				if (i < fila.size() - 1) {
					int esperadas = i + 1;
					sp.getServer().waitFor(s -> Juego.INSTANCIA.partida().carton().puntos(ColorEquipo.ROJO) >= esperadas, 100);
					ctx.waitTicks(25);
				}
			}
			sp.getServer().waitFor(s -> fase(s) == Partida.Fase.FINAL, 200);
			paso("Victoria con la primera fila");

			ctx.waitForScreen(PantallaFinal.class);
			ctx.waitTicks(15);
			ctx.takeScreenshot("stats_04_pantalla_final");
			paso("La pantalla final se abrió sola al terminar");

			ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
			ctx.waitFor(mc -> mc.gui.screen() == null, 40);
			ctx.getInput().pressKey(InputConstants.KEY_B);
			ctx.waitForScreen(PantallaFinal.class);
			ctx.waitTicks(5);
			ctx.takeScreenshot("stats_05_final_reabierta_con_b");
			paso("Esc la cerró y B la volvió a abrir durante el final");

			sp.getServer().waitFor(s -> Juego.INSTANCIA.partida() == null, 20 * 30);
			ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 200);
			ctx.waitTicks(40);

			// ---------------- Tableros del lobby
			String ranking = tablero(sp, "Ranking");
			String ultima = tablero(sp, "Última partida");
			paso("Tablero RANKING:\n" + ranking);
			paso("Tablero ÚLTIMA PARTIDA:\n" + ultima);
			if (!ranking.contains("Temporada " + temporada) || !ranking.contains(nombre)) throw new AssertionError("El ranking no muestra al jugador");
			if (!ultima.contains("Ganó el equipo") || !ultima.contains("Destacado: " + nombre)) throw new AssertionError("La última partida no se actualizó");
			sp.getServer().runCommand("execute as @a at @s run tp @s 0.5 66 -22.5 180 -16");
			ctx.waitTicks(40);
			ctx.takeScreenshot("stats_06_tablero_ranking");
			sp.getServer().runCommand("execute as @a at @s run tp @s 19.5 65 0.5 -90 -12");
			ctx.waitTicks(40);
			ctx.takeScreenshot("stats_07_tablero_ultima_partida");
			paso("Tableros RANKING y ÚLTIMA PARTIDA actualizados");

			// ---------------- Comandos de chat
			ctx.runOnClient(mc -> mc.player.connection.sendCommand("venatio ranking"));
			ctx.runOnClient(mc -> mc.player.connection.sendCommand("venatio stats"));
			ctx.waitTicks(20);
			ctx.takeScreenshot("stats_08_chat_ranking_y_stats");
			paso("/venatio ranking y /venatio stats respondieron en el chat");

			// ---------------- JSON escrito
			JsonObject despues = esperarJson(ctx, uuid, entero(antes, "partidas") + 1);
			comprobar(entero(despues, "casillas") == entero(antes, "casillas") + fila.size(), "casillas " + despues);
			comprobar(victorias(despues) == victorias(antes) + 1, "victorias en 5 en línea " + despues);
			comprobar(despues.has("bingoMasRapido") && despues.has("casillaMasRapida"), "bingo y casilla más rápida " + despues);
			JsonObject temp = despues.getAsJsonObject("temporadas").getAsJsonObject(String.valueOf(temporada));
			JsonObject tempAntes = antes.has("temporadas") && antes.getAsJsonObject("temporadas").has(String.valueOf(temporada))
				? antes.getAsJsonObject("temporadas").getAsJsonObject(String.valueOf(temporada)) : new JsonObject();
			comprobar(entero(temp, "puntos") == entero(tempAntes, "puntos") + 3, "3 puntos de ranking " + temp);
			JsonObject raiz = leerJson();
			comprobar("rojo".equals(raiz.getAsJsonObject("ultimaPartida").get("ganador").getAsString()), "última partida ganada por rojo");
			paso("estadisticas.json escrito: " + despues);

			// ---------------- Temporada nueva
			sp.getServer().runCommand("venatio temporada nueva");
			ctx.waitTicks(10);
			String nuevo = tablero(sp, "Ranking");
			comprobar(nuevo.contains("Temporada " + (temporada + 1)) && !nuevo.contains(nombre), "el ranking de la temporada nueva empieza vacío: " + nuevo);
			ctx.waitFor(mc -> leerJsonSeguro().map(r -> r.get("temporada").getAsInt() == temporada + 1).orElse(false), 100);
			sp.getServer().runCommand("execute as @a at @s run tp @s 0.5 66 -22.5 180 -16");
			ctx.waitTicks(30);
			ctx.takeScreenshot("stats_09_temporada_nueva");
			paso("Temporada nueva: ranking en cero y guardado");
		}

		// ---------------- Un archivo dañado no tumba el server
		try {
			Files.writeString(ARCHIVO, "{ esto no es json");
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 600);
			int temporada = sp.getServer().computeOnServer(s -> Estadisticas.temporada());
			comprobar(temporada == 1, "con el archivo dañado se empieza de cero");
			try (Stream<Path> archivos = Files.list(ARCHIVO.getParent())) {
				comprobar(archivos.anyMatch(p -> p.getFileName().toString().startsWith("estadisticas.corrupto-")), "el archivo dañado se apartó");
			} catch (IOException e) {
				throw new AssertionError(e);
			}
			paso("El archivo dañado se apartó y el server arrancó con estadísticas nuevas");
		}
	}

	// ---------------------------------------------------------------- utilidades

	/** Texto del tablero (TextDisplay del lobby) cuyo título empieza con el prefijo dado. */
	private static String tablero(TestSingleplayerContext sp, String prefijo) {
		return sp.getServer().computeOnServer(s -> {
			List<String> textos = new ArrayList<>();
			for (Entity e : Lobby.nivel().getAllEntities()) {
				if (e instanceof Display.TextDisplay d && e.entityTags().contains("venatio_tablero")) textos.add(d.getText().getString());
			}
			return textos.stream().filter(t -> t.startsWith(prefijo)).findFirst().orElse("(no está el tablero " + prefijo + ")");
		});
	}

	private static JsonObject esperarJson(ClientGameTestContext ctx, String uuid, int partidas) {
		ctx.waitFor(mc -> entero(jugadorEnJson(uuid), "partidas") >= partidas, 200);
		return jugadorEnJson(uuid);
	}

	private static JsonObject jugadorEnJson(String uuid) {
		return leerJsonSeguro().map(r -> r.has("jugadores") && r.getAsJsonObject("jugadores").has(uuid)
			? r.getAsJsonObject("jugadores").getAsJsonObject(uuid) : new JsonObject()).orElse(new JsonObject());
	}

	private static JsonObject leerJson() {
		return leerJsonSeguro().orElseThrow(() -> new AssertionError("No se pudo leer " + ARCHIVO));
	}

	private static java.util.Optional<JsonObject> leerJsonSeguro() {
		try {
			if (!Files.exists(ARCHIVO)) return java.util.Optional.empty();
			return java.util.Optional.of(JsonParser.parseString(Files.readString(ARCHIVO)).getAsJsonObject());
		} catch (Exception e) {
			return java.util.Optional.empty();
		}
	}

	private static int entero(JsonObject o, String campo) {
		return o != null && o.has(campo) ? o.get(campo).getAsInt() : 0;
	}

	private static int victorias(JsonObject jugador) {
		return jugador.has("victorias") ? entero(jugador.getAsJsonObject("victorias"), "linea") : 0;
	}

	private static void comprobar(boolean condicion, String que) {
		if (!condicion) throw new AssertionError("Falló: " + que);
	}

	private static Partida.Fase fase(MinecraftServer s) {
		Partida p = Juego.INSTANCIA.partida();
		return p == null ? null : p.fase();
	}

	private static ServerPlayer jugador(MinecraftServer s) {
		return s.getPlayerList().getPlayers().get(0);
	}

	private static void paso(String texto) {
		Venatio.LOG.info("PRUEBA STATS ✔ {}", texto);
	}
}
