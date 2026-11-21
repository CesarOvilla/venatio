package dev.venatio.prueba;

import com.mojang.blaze3d.platform.InputConstants;
import dev.venatio.Venatio;
import dev.venatio.carton.Carton;
import dev.venatio.cliente.EspectadorCliente;
import dev.venatio.cliente.PantallaCarton;
import dev.venatio.cliente.PantallaFinal;
import dev.venatio.cliente.localizador.LocalizadorCliente;
import dev.venatio.cliente.ruta.PantallaRuta;
import dev.venatio.cliente.ruta.RutaCliente;
import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Espectadores;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Partida;
import dev.venatio.mundo.Lobby;
import dev.venatio.mundo.MundosPartida;
import dev.venatio.ruta.DatosRuta;
import dev.venatio.ruta.GrabadoraRuta;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Set;
import java.util.UUID;

/**
 * Ruta al final y modo espectador con un cliente real y bots de Carpet. Player0 no tiene equipo: mira la partida
 * siguiendo a Bot1 (Rojo) y a Bot2 (Azul) mientras caminan y se teletransportan por etapas; Bot1 pasa un rato en el
 * Nether. Al terminar, la pantalla final tiene la pestaña Ruta con terreno, líneas e iconos (zoom, arrastre y Nether),
 * y la ruta se puede volver a abrir desde el lobby. También: /venatio mirar desde el lobby, un bot que entra a media
 * partida sin equipo queda de espectador y uno de equipo que muere y vuelve sigue jugando.
 */
public final class PruebaRutaEspectador implements FabricClientGameTest {
	private static final int Y_NETHER = 70;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 600);
			ctx.waitTicks(40);
			sp.getServer().runCommand("venatio modo linea");
			sp.getServer().runCommand("venatio tiempo 0");
			sp.getServer().runCommand("venatio pvp no");
			sp.getServer().runCommand("venatio opcion fin_automatico si");
			sp.getServer().runCommand("venatio distancia 120");

			// ---------------- Equipos: los bots juegan, Player0 no tiene equipo
			sp.getServer().runCommand("player Bot1 spawn");
			sp.getServer().runCommand("player Bot2 spawn");
			sp.getServer().waitFor(s -> bot(s, "Bot1") != null && bot(s, "Bot2") != null
				&& Lobby.esLobby(bot(s, "Bot1").level()) && Lobby.esLobby(bot(s, "Bot2").level()), 200);
			sp.getServer().runCommand("execute as Player0 run venatio equipo salir");
			sp.getServer().runCommand("execute as Bot1 run venatio equipo rojo");
			sp.getServer().runCommand("execute as Bot2 run venatio equipo azul");
			ctx.waitTicks(10);

			sp.getServer().runCommand("venatio iniciar");
			sp.getServer().waitFor(s -> fase() == Partida.Fase.JUGANDO, 20 * 180);
			sp.getServer().runCommand("effect give Bot1 minecraft:resistance 1000 4 true");
			sp.getServer().runCommand("effect give Bot2 minecraft:resistance 1000 4 true");
			UUID bot1 = sp.getServer().computeOnServer(s -> bot(s, "Bot1").getUUID());
			UUID bot2 = sp.getServer().computeOnServer(s -> bot(s, "Bot2").getUUID());

			// ---------------- Player0 mira como espectador, siguiendo a Bot1 (el primero de Rojo)
			comprobar(sp.getServer().computeOnServer(s -> {
				Partida p = Juego.INSTANCIA.partida();
				ServerPlayer yo = bot(s, "Player0");
				return !p.participa(yo) && Espectadores.es(yo) && yo.isSpectator() && MundosPartida.esDePartida(yo.level())
					&& p.participa(bot(s, "Bot1")) && p.participa(bot(s, "Bot2"));
			}), "Player0 sin equipo no participa y está de espectador en el mundo de la partida");
			sp.getServer().waitFor(s -> bot(s, "Player0").getCamera() == bot(s, "Bot1"), 100);
			ctx.waitFor(mc -> EspectadorCliente.activo() && camaraEn(mc, "Bot1"), 200);
			comprobar(ctx.computeOnClient(mc -> !LocalizadorCliente.encendida()), "el espectador no recibe la barra de localización");
			ctx.waitTicks(20);
			ctx.takeScreenshot("ruta_01_espectador_sigue_bot1");
			paso("Player0 (sin equipo) mira la partida en primera persona desde Bot1; no es participante");

			// ---------------- Los bots se mueven: caminan y se teletransportan por etapas
			sp.getServer().runCommand("player Bot1 move forward");
			sp.getServer().runCommand("player Bot2 move forward");
			ctx.waitTicks(60);
			// En zigzag, para que la línea tenga vueltas.
			int[][] etapas1 = {{36, 22}, {10, 40}, {44, -6}, {18, 38}}, etapas2 = {{-30, 34}, {-44, 4}, {-12, 40}, {-40, 20}};
			for (int etapa = 0; etapa < 4; etapa++) {
				mover(sp, "Bot1", etapas1[etapa][0], etapas1[etapa][1]);
				mover(sp, "Bot2", etapas2[etapa][0], etapas2[etapa][1]);
				ctx.waitTicks(50);
			}
			sp.getServer().runCommand("player Bot1 stop");
			sp.getServer().runCommand("player Bot2 stop");
			int puntos1 = sp.getServer().computeOnServer(s -> GrabadoraRuta.puntosDe(bot1));
			int puntos2 = sp.getServer().computeOnServer(s -> GrabadoraRuta.puntosDe(bot2));
			int chunks = sp.getServer().computeOnServer(s -> GrabadoraRuta.chunksMuestreados(DatosRuta.MUNDO));
			comprobar(puntos1 >= 5 && puntos2 >= 5, "se grabó el recorrido de los dos bots (" + puntos1 + " y " + puntos2 + " puntos)");
			comprobar(chunks >= 60, "se muestreó el terreno alrededor de los bots (" + chunks + " chunks)");
			comprobar(sp.getServer().computeOnServer(s -> GrabadoraRuta.puntosDe(bot(s, "Player0").getUUID())) == 0,
				"el espectador no tiene recorrido");
			paso("Ruta grabándose: Bot1 " + puntos1 + " puntos, Bot2 " + puntos2 + " puntos, " + chunks + " chunks de terreno");

			// ---------------- Cambiar de jugador y de equipo, y vista libre
			ctx.getInput().pressKey(InputConstants.KEY_RIGHT);
			sp.getServer().waitFor(s -> bot(s, "Player0").getCamera() == bot(s, "Bot2"), 100);
			ctx.waitFor(mc -> "Bot2".equals(EspectadorCliente.nombre()) && camaraEn(mc, "Bot2"), 200);
			ctx.waitTicks(10);
			ctx.takeScreenshot("ruta_02_espectador_cambia_a_bot2");
			paso("→ cambió a Bot2 (Azul): la cámara y el HUD lo siguen");

			ctx.getInput().pressKey(InputConstants.KEY_UP);
			sp.getServer().waitFor(s -> bot(s, "Player0").getCamera() == bot(s, "Bot1"), 100);
			ctx.waitFor(mc -> camaraEn(mc, "Bot1"), 200);
			paso("↑ cambió de equipo: de vuelta con Bot1 (Rojo)");

			ctx.getInput().holdKeyFor(InputConstants.KEY_SPACE, 3);
			sp.getServer().waitFor(s -> Espectadores.siguiendoA(bot(s, "Player0")) == null && bot(s, "Player0").getCamera() == bot(s, "Player0"), 100);
			ctx.waitFor(mc -> EspectadorCliente.libre() && mc.getCameraEntity() == mc.player, 100);
			ctx.waitTicks(10);
			ctx.takeScreenshot("ruta_03_vista_libre");
			ctx.getInput().pressKey(InputConstants.KEY_RIGHT);
			sp.getServer().waitFor(s -> Espectadores.siguiendoA(bot(s, "Player0")) != null, 100);
			ctx.waitFor(mc -> !EspectadorCliente.libre() && mc.getCameraEntity() != mc.player, 200);
			paso("Espacio dejó la vista libre y → volvió a seguir a alguien");

			// ---------------- El espectador no cuenta en el cartón
			String item0 = item(sp, 0), item3 = item(sp, 3);
			sp.getServer().runCommand("give Player0 " + item3);
			sp.getServer().runCommand("give Bot1 " + item0);
			sp.getServer().waitFor(s -> casilla(0).logros.containsKey(ColorEquipo.ROJO), 100);
			ctx.waitTicks(30);
			comprobar(sp.getServer().computeOnServer(s -> casilla(3).logros.isEmpty()), "el item del espectador no marcó la casilla");
			comprobar(sp.getServer().computeOnServer(s -> GrabadoraRuta.eventos(DatosRuta.CASILLA)) == 1, "la casilla de Bot1 quedó en la ruta");
			ctx.getInput().pressKey(InputConstants.KEY_B);
			ctx.waitForScreen(PantallaCarton.class);
			ctx.waitTicks(15);
			ctx.takeScreenshot("ruta_04_carton_del_espectador");
			ctx.setScreen(() -> null);
			paso("Player0 tiene " + item3 + " y no cuenta; Bot1 marcó " + item0 + " para Rojo. B abre el cartón del equipo que mira");

			// ---------------- Un bot de equipo muere, sale y vuelve: sigue jugando
			// /kill a un bot de Carpet solo lo desconecta; con daño de verdad muere (y Carpet lo saca al morir).
			sp.getServer().runCommand("effect clear Bot2");
			sp.getServer().runCommand("damage Bot2 1000 minecraft:out_of_world");
			sp.getServer().waitFor(s -> bot(s, "Bot2") == null, 100);
			comprobar(sp.getServer().computeOnServer(s -> GrabadoraRuta.eventos(DatosRuta.MUERTE)) >= 1, "la muerte de Bot2 quedó en la ruta");
			sp.getServer().runCommand("player Bot2 spawn");
			sp.getServer().waitFor(s -> {
				ServerPlayer b = bot(s, "Bot2");
				return b != null && b.isAlive() && MundosPartida.esDePartida(b.level());
			}, 200);
			comprobar(sp.getServer().computeOnServer(s -> {
				ServerPlayer b = bot(s, "Bot2");
				return Juego.INSTANCIA.partida().participa(b) && !Espectadores.es(b) && !b.isSpectator();
			}), "Bot2 volvió como participante de Azul, no como espectador");
			sp.getServer().runCommand("effect give Bot2 minecraft:resistance 1000 4 true");
			mover(sp, "Bot2", 20, -40);
			paso("Bot2 murió (queda en la ruta), salió y al volver sigue en Azul");

			// ---------------- Quien entra a media partida sin equipo, mira
			sp.getServer().runCommand("player Bot3 spawn");
			sp.getServer().waitFor(s -> {
				ServerPlayer b = bot(s, "Bot3");
				return b != null && b.isSpectator() && MundosPartida.esDePartida(b.level());
			}, 200);
			comprobar(sp.getServer().computeOnServer(s -> Espectadores.es(bot(s, "Bot3")) && !Juego.INSTANCIA.partida().participa(bot(s, "Bot3"))),
				"Bot3 entró a media partida sin equipo y quedó de espectador");
			paso("Bot3 entró a media partida sin equipo: espectador");

			// ---------------- Bot1 pasa por el Nether; el espectador lo sigue
			sp.getServer().runOnServer(PruebaRutaEspectador::prepararNether);
			BlockPos vuelta = sp.getServer().computeOnServer(s -> bot(s, "Bot1").blockPosition());
			sp.getServer().runOnServer(s -> {
				ServerPlayer b = bot(s, "Bot1");
				b.teleportTo(MundosPartida.activa().nether(), 0.5, Y_NETHER, 0.5, Set.of(), -90f, 0f, true);
			});
			seguirA(ctx, sp, "Bot1");
			sp.getServer().waitFor(s -> bot(s, "Player0").level() == MundosPartida.activa().nether() && bot(s, "Player0").getCamera() == bot(s, "Bot1"), 200);
			for (int x = 10; x <= 60; x += 10) {
				int destino = x;
				sp.getServer().runOnServer(s -> bot(s, "Bot1").teleportTo(MundosPartida.activa().nether(), destino + 0.5, Y_NETHER, 0.5, Set.of(), -90f, 0f, true));
				ctx.waitTicks(45);
			}
			ctx.waitFor(mc -> mc.level.dimension().identifier().getPath().endsWith("_nether") && camaraEn(mc, "Bot1"), 300);
			ctx.waitTicks(20);
			ctx.takeScreenshot("ruta_05_espectador_en_el_nether");
			int puntosNether = sp.getServer().computeOnServer(s -> GrabadoraRuta.chunksMuestreados(DatosRuta.NETHER));
			paso("Bot1 anduvo por el Nether (" + puntosNether + " chunks muestreados) y el espectador lo siguió de dimensión");
			sp.getServer().runOnServer(s -> {
				ServerPlayer b = bot(s, "Bot1");
				ServerLevel mundo = MundosPartida.activa().mundo();
				int y = mundo.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, vuelta.getX(), vuelta.getZ());
				b.teleportTo(mundo, vuelta.getX() + 0.5, y, vuelta.getZ() + 0.5, Set.of(), 0f, 0f, true);
			});
			ctx.waitTicks(45);
			mover(sp, "Bot1", 30, -30);
			ctx.waitTicks(45);
			comprobar(sp.getServer().computeOnServer(s -> GrabadoraRuta.eventos(DatosRuta.PORTAL_SALIDA) >= 2
				&& GrabadoraRuta.eventos(DatosRuta.PORTAL_LLEGADA) >= 2), "ida y vuelta del Nether quedaron en la ruta");

			// ---------------- Ruta en vivo para el espectador (tecla R)
			ctx.getInput().pressKey(InputConstants.KEY_R);
			ctx.waitForScreen(PantallaRuta.class);
			ctx.waitTicks(10);
			comprobar(ctx.computeOnClient(mc -> RutaCliente.ruta() != null && RutaCliente.ruta().enVivo()), "el espectador recibió la ruta en vivo");
			ctx.takeScreenshot("ruta_06_ruta_en_vivo");
			ctx.setScreen(() -> null);
			paso("R abrió la ruta en vivo para el espectador");

			// ---------------- /venatio mirar desde el lobby
			sp.getServer().runCommand("execute as Player0 run venatio mirar salir");
			sp.getServer().waitFor(s -> Lobby.esLobby(bot(s, "Player0").level()) && !Espectadores.es(bot(s, "Player0")), 100);
			ctx.waitFor(mc -> !EspectadorCliente.activo() && mc.level.dimension().toString().contains("venatio:lobby"), 200);
			ctx.waitTicks(20);
			ctx.takeScreenshot("ruta_07_lobby_durante_la_partida");
			sp.getServer().runCommand("execute as Player0 run venatio mirar");
			sp.getServer().waitFor(s -> {
				ServerPlayer yo = bot(s, "Player0");
				return Espectadores.es(yo) && yo.isSpectator() && MundosPartida.esDePartida(yo.level()) && yo.getCamera() != yo;
			}, 200);
			ctx.waitFor(mc -> EspectadorCliente.activo() && !EspectadorCliente.libre() && mc.getCameraEntity() != mc.player, 300);
			ctx.waitTicks(20);
			ctx.takeScreenshot("ruta_08_mirar_desde_el_lobby");
			paso("/venatio mirar salir lo llevó al lobby y /venatio mirar lo regresó a mirar a " + ctx.computeOnClient(mc -> EspectadorCliente.nombre()));

			// ---------------- Rojo gana con la primera fila de Bot1
			int n = sp.getServer().computeOnServer(s -> Juego.INSTANCIA.partida().carton().tamano);
			for (int i = 1; i < n; i++) {
				sp.getServer().runCommand("give Bot1 " + item(sp, i));
				ctx.waitTicks(15);
			}
			sp.getServer().waitFor(s -> fase() == Partida.Fase.FINAL, 200);
			ctx.waitForScreen(PantallaFinal.class);
			ctx.waitFor(mc -> RutaCliente.hayFinal(), 200);
			ctx.waitTicks(30);
			ctx.takeScreenshot("ruta_09_final_del_espectador");

			DatosRuta ruta = sp.getServer().computeOnServer(s -> GrabadoraRuta.ultima());
			comprobar(ruta != null && ruta.jugadores().size() == 2, "la ruta tiene a los dos bots y a nadie más");
			comprobar(ruta.jugadores().stream().noneMatch(j -> j.nombre().equals("Player0") || j.nombre().equals("Bot3")),
				"los espectadores no aparecen en la ruta");
			comprobar(ruta.eventos().stream().filter(e -> e.tipo() == DatosRuta.CASILLA).count() == n, "las " + n + " casillas tienen su lugar");
			comprobar(ruta.tieneDimension(DatosRuta.NETHER), "la ruta incluye el Nether");
			comprobar(ruta.spawns().size() == 2, "las salidas de los dos equipos");
			paso("Pantalla final del espectador; ruta con " + ruta.jugadores().size() + " jugadores, " + ruta.eventos().size() + " eventos y "
				+ ruta.terrenos().stream().mapToInt(DatosRuta.Terreno::cantidad).sum() + " chunks de terreno");

			// ---------------- Pestaña Ruta: terreno, líneas, iconos, zoom, arrastre y Nether
			ctx.clickScreenButton("venatio.ruta.boton");
			ctx.waitForScreen(PantallaRuta.class);
			ctx.waitTicks(10);
			comprobar(ctx.computeOnClient(mc -> {
				PantallaRuta r = ruta(mc);
				return r.dimension() == DatosRuta.MUNDO && r.terrenoVisible() && r.pixelesTerreno() > 500 && r.tramos() > 10
					&& r.iconos(DatosRuta.CASILLA) >= 1 && r.iconos(DatosRuta.MUERTE) >= 1 && r.iconos(10) == 2 && r.iconos(11) == 2;
			}), "el mapa dibuja terreno, líneas, casillas, la muerte, las dos salidas y las dos cabezas: " + resumen(ctx));
			ctx.takeScreenshot("ruta_10_mapa");
			paso("Pestaña Ruta: " + resumen(ctx));

			int[] casillaEnPantalla = ctx.computeOnClient(mc -> ruta(mc).centroDeIcono(DatosRuta.CASILLA));
			cursor(ctx, casillaEnPantalla[0], casillaEnPantalla[1]);
			ctx.waitTicks(3);
			String hover = ctx.computeOnClient(mc -> ruta(mc).textoHover());
			comprobar(hover != null && hover.startsWith("Bot1 consiguió ") && hover.matches(".* · \\d+:\\d\\d"),
				"al pasar el ratón por una casilla: \"" + hover + "\"");
			ctx.takeScreenshot("ruta_11_tooltip_casilla");
			paso("Tooltip: " + hover);

			int[] centro = ctx.computeOnClient(mc -> ruta(mc).centroDelMapa());
			float antes = ctx.computeOnClient(mc -> ruta(mc).escala());
			cursor(ctx, centro[0], centro[1]);
			ctx.getInput().scroll(4);
			ctx.waitTicks(3);
			float despues = ctx.computeOnClient(mc -> ruta(mc).escala());
			comprobar(despues > antes * 2, "la rueda acercó el mapa (" + antes + " → " + despues + " px por bloque)");
			ctx.takeScreenshot("ruta_12_zoom");
			ctx.getInput().holdMouse(0);
			ctx.getInput().moveCursor(-120, -60);
			ctx.getInput().releaseMouse(0);
			ctx.waitTicks(3);
			ctx.takeScreenshot("ruta_13_arrastre");
			paso("Zoom con la rueda (" + String.format("%.2f", antes) + " → " + String.format("%.2f", despues) + " px por bloque) y arrastre");

			ctx.clickScreenButton("venatio.ruta.dim.1");
			ctx.waitTicks(5);
			comprobar(ctx.computeOnClient(mc -> ruta(mc).dimension() == DatosRuta.NETHER && ruta(mc).tramos() > 0
				&& ruta(mc).iconos(DatosRuta.PORTAL_LLEGADA) >= 1), "el selector cambió al Nether con su recorrido: " + resumen(ctx));
			ctx.takeScreenshot("ruta_14_nether");
			paso("Selector de dimensión: Nether con el recorrido de Bot1 y sus portales");
			ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
			ctx.waitForScreen(PantallaFinal.class);
			ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);

			// ---------------- De vuelta en el lobby, la ruta se vuelve a ver (R o /venatio ruta)
			sp.getServer().waitFor(s -> Juego.INSTANCIA.partida() == null, 20 * 30);
			ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby") && mc.gui.screen() == null, 300);
			comprobar(sp.getServer().computeOnServer(s -> !bot(s, "Player0").isSpectator() && !bot(s, "Bot3").isSpectator()
				&& Lobby.esLobby(bot(s, "Bot3").level())), "los espectadores volvieron al lobby como los demás");
			comprobar(ctx.computeOnClient(mc -> !EspectadorCliente.activo()), "el HUD de espectador se apagó");
			ctx.getInput().pressKey(InputConstants.KEY_R);
			ctx.waitForScreen(PantallaRuta.class);
			ctx.waitTicks(10);
			comprobar(ctx.computeOnClient(mc -> ruta(mc).tramos() > 0 && ruta(mc).terrenoVisible()), "desde el lobby se ve la misma ruta");
			ctx.takeScreenshot("ruta_15_desde_el_lobby");
			ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
			ctx.waitFor(mc -> mc.gui.screen() == null, 40);
			sp.getServer().runCommand("execute as Player0 run venatio ruta");
			ctx.waitForScreen(PantallaRuta.class);
			ctx.setScreen(() -> null);
			paso("En el lobby, R y /venatio ruta vuelven a abrir la ruta de la última partida");

			sp.getServer().runCommand("player Bot1 kill");
			sp.getServer().runCommand("player Bot2 kill");
			sp.getServer().runCommand("player Bot3 kill");
			sp.getServer().runCommand("venatio distancia 400");
		}
	}

	// ---------------------------------------------------------------- utilidades

	/** Teletransporta al bot (dx, dz) bloques sobre el suelo, mirando hacia donde va. */
	private static void mover(TestSingleplayerContext sp, String nombre, int dx, int dz) {
		sp.getServer().runOnServer(s -> {
			ServerPlayer b = bot(s, nombre);
			ServerLevel nivel = b.level();
			int x = b.getBlockX() + dx, z = b.getBlockZ() + dz;
			int y = nivel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			b.teleportTo(nivel, x + 0.5, y, z + 0.5, Set.of(), yaw, 0f, true);
		});
	}

	/** Un pasillo de aire con piso en el Nether de la partida, a lo largo de x = 0…60. */
	private static void prepararNether(MinecraftServer s) {
		ServerLevel nether = MundosPartida.activa().nether();
		for (int x = -3; x <= 63; x++) {
			for (int z = -2; z <= 2; z++) {
				nether.setBlockAndUpdate(new BlockPos(x, Y_NETHER - 1, z), Blocks.NETHERRACK.defaultBlockState());
				for (int dy = 0; dy < 4; dy++) nether.setBlockAndUpdate(new BlockPos(x, Y_NETHER + dy, z), Blocks.AIR.defaultBlockState());
			}
		}
	}

	private static boolean camaraEn(Minecraft mc, String nombre) {
		Entity c = mc.getCameraEntity();
		return c instanceof Player p && c != mc.player && p.getGameProfile().name().equals(nombre);
	}

	private static PantallaRuta ruta(Minecraft mc) {
		return (PantallaRuta) mc.gui.screen();
	}

	private static String resumen(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> {
			if (!(mc.gui.screen() instanceof PantallaRuta r)) return "(sin pantalla de ruta)";
			return "dimensión " + r.dimension() + ", " + r.tramos() + " trazos, terreno " + (r.terrenoVisible() ? r.pixelesTerreno() + " px" : "no")
				+ ", casillas " + r.iconos(DatosRuta.CASILLA) + ", muertes " + r.iconos(DatosRuta.MUERTE)
				+ ", portales " + (r.iconos(DatosRuta.PORTAL_SALIDA) + r.iconos(DatosRuta.PORTAL_LLEGADA))
				+ ", salidas " + r.iconos(10) + ", cabezas " + r.iconos(11);
		});
	}

	/** Pone el ratón en (x, y) de interfaz. */
	private static void cursor(ClientGameTestContext ctx, int x, int y) {
		double f = ctx.computeOnClient(mc -> (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth());
		ctx.getInput().setCursorPos((x + 0.5) * f, (y + 0.5) * f);
	}

	private static String item(TestSingleplayerContext sp, int indice) {
		return sp.getServer().computeOnServer(s -> BuiltInRegistries.ITEM.getKey(casilla(indice).icono.getItem()).toString());
	}

	private static Carton.Casilla casilla(int indice) {
		return Juego.INSTANCIA.partida().carton().casillas.get(indice);
	}

	private static ServerPlayer bot(MinecraftServer s, String nombre) {
		return s.getPlayerList().getPlayerByName(nombre);
	}

	private static Partida.Fase fase() {
		Partida p = Juego.INSTANCIA.partida();
		return p == null ? null : p.fase();
	}

	private static void comprobar(boolean condicion, String que) {
		if (!condicion) throw new AssertionError("Falló: " + que);
	}

	private static void paso(String texto) {
		Venatio.LOG.info("PRUEBA RUTA ✔ {}", texto);
	}

	/** Pulsa → hasta que el espectador siga a ese bot. */
	private static void seguirA(ClientGameTestContext ctx, TestSingleplayerContext sp, String nombre) {
		for (int i = 0; i < 4; i++) {
			if (sp.getServer().computeOnServer(s -> bot(s, "Player0").getCamera() == bot(s, nombre))) return;
			ctx.getInput().pressKey(InputConstants.KEY_RIGHT);
			ctx.waitTicks(10);
		}
		comprobar(sp.getServer().computeOnServer(s -> bot(s, "Player0").getCamera() == bot(s, nombre)), "el espectador sigue a " + nombre);
	}
}
