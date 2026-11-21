package dev.venatio.prueba;

import dev.venatio.Venatio;
import dev.venatio.cliente.AyudasCliente;
import dev.venatio.cliente.localizador.LocalizadorCliente;
import dev.venatio.cliente.localizador.LocalizadorCliente.Dibujado;
import dev.venatio.cliente.localizador.LocalizadorCliente.Tipo;
import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Configuracion;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Opcion;
import dev.venatio.juego.Partida;
import dev.venatio.mundo.Lobby;
import dev.venatio.mundo.MundosPartida;
import dev.venatio.red.PaquetesAyudas;
import dev.venatio.red.PaquetesTienda;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.gamerules.GameRules;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Barra de localización con un cliente real y bots de Carpet: Player0 y Bot1 en Rojo, Bot2 en Azul.
 * Posiciones en la barra según hacia dónde mira, nombre y distancia al centro, punta para quien queda detrás,
 * rivales apagados, rival agachado, marca de equipo, guía, experiencia y fin de partida. Capturas localizador_*.png.
 */
public final class PruebaLocalizador implements FabricClientGameTest {
	/** Píxeles de la barra por grado (vanilla: ±60° en 173 px). */
	private static final double PX_POR_GRADO = 173.0 / 120.0;
	private static final int ALTURA = 200;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 600);
			ctx.waitTicks(40);

			// ---------------------------------------------------------------- lobby
			comprobar(!sp.getServer().computeOnServer(s -> Lobby.nivel().getGameRules().get(GameRules.LOCATOR_BAR)),
				"la barra vanilla está apagada en el lobby");
			sp.getServer().runCommand("player Bot1 spawn");
			sp.getServer().runCommand("player Bot2 spawn");
			sp.getServer().waitFor(s -> bot(s, "Bot1") != null && bot(s, "Bot2") != null
				&& Lobby.esLobby(bot(s, "Bot1").level()) && Lobby.esLobby(bot(s, "Bot2").level()), 200);
			sp.getServer().runCommand("execute as Player0 run venatio equipo rojo");
			sp.getServer().runCommand("execute as Bot1 run venatio equipo rojo");
			sp.getServer().runCommand("execute as Bot2 run venatio equipo azul");
			sp.getServer().runCommand("venatio opcion localizador si");
			sp.getServer().runCommand("venatio opcion localizador_rivales si");
			ctx.waitTicks(30);
			comprobar(ctx.computeOnClient(mc -> !LocalizadorCliente.encendida() && !LocalizadorCliente.barraVisible()),
				"en el lobby no hay barra de localización");
			paso("Lobby sin barra (vanilla apagada y la propia sin datos); Bot1 en Rojo y Bot2 en Azul");

			// ---------------------------------------------------------------- partida
			sp.getServer().runCommand("venatio iniciar");
			sp.getServer().waitFor(s -> fase() == Partida.Fase.JUGANDO, 20 * 180);
			sp.getServer().runCommand("effect give @a minecraft:resistance 1000 4 true");
			sp.getServer().runCommand("effect give @a minecraft:saturation 1000 0 true");
			comprobar(!sp.getServer().computeOnServer(s -> MundosPartida.activa().mundo().getGameRules().get(GameRules.LOCATOR_BAR)),
				"la barra vanilla está apagada en el mundo de la partida");
			ctx.waitFor(mc -> LocalizadorCliente.nombres().containsAll(List.of("Bot1", "Bot2")), 200);
			ctx.waitTicks(20);
			captura(ctx, "localizador_01_inicio");
			paso("Al empezar: Bot1 y Bot2 en la barra");

			// ---------------------------------------------------------------- plataforma de vidrio en lo alto
			BlockPos p = sp.getServer().computeOnServer(s -> bot(s, "Player0").blockPosition());
			int x = p.getX(), z = p.getZ();
			ejecutar(sp, "fill " + (x - 25) + " " + (ALTURA - 1) + " " + (z - 25) + " " + (x + 25) + " " + (ALTURA - 1) + " " + (z + 25) + " minecraft:glass");
			ejecutar(sp, "fill " + (x - 25) + " " + ALTURA + " " + (z - 25) + " " + (x + 25) + " " + (ALTURA + 3) + " " + (z + 25) + " minecraft:air");
			ponerEn(sp, "Player0", x, z, 0, 0);
			ponerEn(sp, "Bot1", x + 10, z + 20, 0, 0);
			ctx.waitTicks(20);

			// ---------------------------------------------------------------- lejos: Bot2 sigue en el punto de Azul
			// A ~400 bloques el cliente no lo tiene cargado: se dibuja con las posiciones de los paquetes.
			// Los puntos de aparición se corren buscando tierra: si Azul quedó a menos de 400, se aleja a Bot2 en la misma dirección.
			sp.getServer().runOnServer(s -> {
				ServerPlayer yo = bot(s, "Player0"), otro = bot(s, "Bot2");
				double dx = otro.getX() - yo.getX(), dz = otro.getZ() - yo.getZ(), d = Math.hypot(dx, dz);
				if (d >= 400) return;
				double f = 420 / Math.max(1, d);
				int lx = Mth.floor(yo.getX() + dx * f), lz = Mth.floor(yo.getZ() + dz * f);
				var nivel = otro.level();
				nivel.getChunk(lx >> 4, lz >> 4);
				int y = nivel.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz);
				otro.teleportTo(nivel, lx + 0.5, y, lz + 0.5, java.util.Set.of(), otro.getYRot(), 0f, true);
			});
			ctx.waitTicks(20);
			UUID bot2 = sp.getServer().computeOnServer(s -> bot(s, "Bot2").getUUID());
			boolean bot2Cargado = ctx.computeOnClient(mc -> mc.level.getPlayerByUUID(bot2) != null);
			float haciaBot2 = sp.getServer().computeOnServer(s -> {
				ServerPlayer yo = bot(s, "Player0"), otro = bot(s, "Bot2");
				return (float) Math.toDegrees(Math.atan2(-(otro.getX() - yo.getX()), otro.getZ() - yo.getZ()));
			});
			ponerEn(sp, "Player0", x, z, haciaBot2, 0);
			ctx.waitTicks(10);
			long metros2 = distancia(sp, "Bot2");
			Dibujado lejano = dibujado(ctx, "Bot2").orElseThrow(() -> new AssertionError("Bot2 no está en la barra"));
			String etiquetaLejano = ctx.computeOnClient(mc -> LocalizadorCliente.etiqueta());
			comprobar(!lejano.punta() && lejano.tamano() == 5 && Math.abs(lejano.x()) < 2,
				"Bot2, a " + metros2 + " bloques, se dibuja de 5 px al centro: " + lejano);
			comprobar(etiquetaLejano != null && etiquetaLejano.startsWith("Bot2 · "), "al mirar a Bot2 se lee su nombre: " + etiquetaLejano);
			captura(ctx, "localizador_01b_lejano");
			paso("Mirando hacia Azul: " + etiquetaLejano + ", de 5 px"
				+ (bot2Cargado ? "" : " (sin cargar en el cliente: posición de los paquetes)"));

			// ---------------------------------------------------------------- posiciones según hacia dónde mira
			ponerEn(sp, "Player0", x, z, 0, 0);
			ponerEn(sp, "Bot2", x - 15, z + 20, 0, 0);
			ctx.waitTicks(50);

			double esperado1 = gradosHacia(10, 20, 0), esperado2 = gradosHacia(-15, 20, 0);
			Dibujado d1 = dibujado(ctx, "Bot1").orElseThrow(() -> new AssertionError("Bot1 no está en la barra"));
			Dibujado d2 = dibujado(ctx, "Bot2").orElseThrow(() -> new AssertionError("Bot2 no está en la barra"));
			comprobar(ctx.computeOnClient(mc -> LocalizadorCliente.barraVisible()), "se ve la barra de localización");
			comprobar(!d1.punta() && d1.rgb() == ColorEquipo.ROJO.rgb && Math.abs(d1.x() - esperado1 * PX_POR_GRADO) < 3,
				"Bot1 rojo a la izquierda en " + Math.round(esperado1 * PX_POR_GRADO) + " px: " + d1);
			comprobar(!d2.punta() && d2.rgb() == ColorEquipo.AZUL.rgb && Math.abs(d2.x() - esperado2 * PX_POR_GRADO) < 3,
				"Bot2 azul a la derecha en " + Math.round(esperado2 * PX_POR_GRADO) + " px: " + d2);
			comprobar(d1.tamano() == 9 && d2.tamano() == 9, "de cerca se dibujan de 9 px");
			captura(ctx, "localizador_02_dos_bots");
			paso("Mirando al sur: Bot1 (Rojo) a " + Math.round(d1.x()) + " px y Bot2 (Azul) a " + Math.round(d2.x()) + " px del centro");

			// ---------------------------------------------------------------- nombre y distancia al mirarlo
			float haciaBot1 = (float) Math.toDegrees(Math.atan2(-10, 20));
			ponerEn(sp, "Player0", x, z, haciaBot1, 0);
			ctx.waitTicks(10);
			String etiqueta = ctx.computeOnClient(mc -> LocalizadorCliente.etiqueta());
			comprobar("Bot1 · 22 m".equals(etiqueta), "al mirar a Bot1 se lee \"Bot1 · 22 m\" (fue " + etiqueta + ")");
			comprobar(Math.abs(dibujado(ctx, "Bot1").orElseThrow().x()) < 2, "Bot1 queda al centro de la barra");
			captura(ctx, "localizador_03_nombre");
			paso("Mirando a Bot1: " + etiqueta);

			// ---------------------------------------------------------------- detrás: punta en el extremo
			ponerEn(sp, "Player0", x, z, 0, 0);
			ponerEn(sp, "Bot2", x - 5, z - 20, 0, 0);
			ctx.waitTicks(20);
			Dibujado detras = dibujado(ctx, "Bot2").orElseThrow(() -> new AssertionError("Bot2 (detrás) no está en la barra"));
			comprobar(detras.punta() && detras.lado() == 1 && detras.rgb() == ColorEquipo.AZUL.rgb,
				"Bot2 detrás, un poco a la derecha (" + Math.round(gradosHacia(-5, -20, 0)) + "°): punta azul en el extremo derecho: " + detras);
			captura(ctx, "localizador_04_detras");
			paso("Bot2 detrás: punta azul en el extremo derecho");

			// ---------------------------------------------------------------- rivales apagados
			ponerEn(sp, "Bot2", x - 15, z + 20, 0, 0);
			ctx.waitFor(mc -> dibujado("Bot2").map(d -> !d.punta()).orElse(false), 60);
			ponerRivales(sp, false);
			ctx.waitFor(mc -> !LocalizadorCliente.nombres().contains("Bot2"), 60);
			ctx.waitTicks(5);
			comprobar(dibujado(ctx, "Bot2").isEmpty() && dibujado(ctx, "Bot1").isPresent(),
				"sin rivales: Bot2 desaparece y Bot1 sigue");
			captura(ctx, "localizador_05_sin_rivales");
			paso("Opción \"Ver rivales en la barra\" en no: solo queda Bot1");
			ponerRivales(sp, true);
			ctx.waitFor(mc -> LocalizadorCliente.nombres().contains("Bot2"), 60);

			// ---------------------------------------------------------------- rival agachado
			sp.getServer().runCommand("player Bot2 sneak");
			sp.getServer().runCommand("player Bot1 sneak");
			ctx.waitFor(mc -> !LocalizadorCliente.nombres().contains("Bot2"), 60);
			ctx.waitTicks(15);
			comprobar(dibujado(ctx, "Bot2").isEmpty(), "Bot2 agachado no aparece para Rojo");
			comprobar(dibujado(ctx, "Bot1").isPresent(), "Bot1 agachado sigue apareciendo para su compañero");
			captura(ctx, "localizador_06_agachados");
			paso("Agachados: Bot2 (rival) se esconde; Bot1 (compañero) sigue en la barra");
			sp.getServer().runCommand("player Bot2 unsneak");
			sp.getServer().runCommand("player Bot1 unsneak");
			ctx.waitFor(mc -> LocalizadorCliente.nombres().contains("Bot2"), 60);

			// ---------------------------------------------------------------- más alto: flecha arriba
			ejecutar(sp, "setblock " + (x + 10) + " " + (ALTURA + 39) + " " + (z + 20) + " minecraft:glass");
			ponerEn(sp, "Bot1", x + 10, ALTURA + 40, z + 20);
			ctx.waitFor(mc -> dibujado("Bot1").map(d -> d.flecha() == 1).orElse(false), 60);
			Dibujado alto = dibujado(ctx, "Bot1").orElseThrow();
			comprobar(alto.flecha() == 1 && dibujado(ctx, "Bot2").map(d -> d.flecha() == 0).orElse(false),
				"Bot1, 40 bloques más arriba, lleva flecha hacia arriba y Bot2 ninguna: " + alto);
			captura(ctx, "localizador_06b_arriba");
			paso("Bot1 en lo alto: flecha hacia arriba");
			ponerEn(sp, "Bot1", x + 10, z + 20, 0, 0);
			ctx.waitFor(mc -> dibujado("Bot1").map(d -> d.flecha() == 0).orElse(false), 60);

			// ---------------------------------------------------------------- marca de equipo
			// Mirando 10 bloques adelante sobre el vidrio: la marca cae en (x, ALTURA - 1, z + 10).
			ponerEn(sp, "Player0", x, z, 0, (float) Math.toDegrees(Math.atan2(1.62, 10)));
			ctx.waitTicks(5);
			ctx.getInput().pressKey(tecla(ctx, "key.venatio.marca"));
			ctx.waitFor(mc -> AyudasCliente.marcasVisibles() == 1, 100);
			ponerEn(sp, "Player0", x, z, 0, 0);
			ctx.waitTicks(10);
			Dibujado marca = ctx.computeOnClient(mc -> LocalizadorCliente.dibujados().stream().filter(d -> d.tipo() == Tipo.MARCA).findFirst())
				.orElseThrow(() -> new AssertionError("La marca no aparece en la barra"));
			String etiquetaMarca = ctx.computeOnClient(mc -> LocalizadorCliente.etiqueta());
			comprobar(!marca.punta() && Math.abs(marca.x()) < 3 && marca.rgb() == ColorEquipo.ROJO.rgb,
				"la marca (rombo rojo) está al centro de la barra: " + marca);
			comprobar(etiquetaMarca != null && etiquetaMarca.startsWith("Marca de Player0"), "la marca muestra quién la puso: " + etiquetaMarca);
			ctx.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
			captura(ctx, "localizador_07_marca");
			paso("Marca de equipo en la barra: " + etiquetaMarca);

			// ---------------------------------------------------------------- guía activa (brújula)
			Identifier dimension = ctx.computeOnClient(mc -> mc.level.dimension().identifier());
			ponerGuia(ctx, new PaquetesTienda.Guia(PaquetesAyudas.PedirGuia.BIOMA, Identifier.withDefaultNamespace("plains"),
				dimension, x - 150, z + 150, 64, 300));
			ctx.waitTicks(5);
			Dibujado guia = ctx.computeOnClient(mc -> LocalizadorCliente.dibujados().stream().filter(d -> d.tipo() == Tipo.GUIA).findFirst())
				.orElseThrow(() -> new AssertionError("La guía no aparece en la barra"));
			comprobar(!guia.punta() && Math.abs(guia.x() - 45 * PX_POR_GRADO) < 3 && guia.tamano() == 7,
				"la guía (brújula) está a 45° a la derecha, a media distancia: " + guia);
			captura(ctx, "localizador_08_guia");
			ponerGuia(ctx, null);
			paso("Guía en la barra con brújula: " + guia);

			// ---------------------------------------------------------------- experiencia
			sp.getServer().runCommand("xp add Player0 3 points");
			ctx.waitTicks(3);
			sp.getServer().runCommand("xp add Player0 7 points");
			ctx.waitFor(mc -> !LocalizadorCliente.barraVisible(), 40);
			ctx.waitTicks(5);
			captura(ctx, "localizador_09_experiencia");
			paso("Al ganar experiencia se ve la barra de experiencia (nivel " + ctx.computeOnClient(mc -> mc.player.experienceLevel) + ")");
			ctx.waitFor(mc -> LocalizadorCliente.barraVisible(), 140);
			ctx.waitTicks(5);
			captura(ctx, "localizador_10_vuelve");
			paso("A los 5 s vuelve la barra de localización, con el número de nivel");

			// ---------------------------------------------------------------- fin de la partida
			sp.getServer().runCommand("venatio terminar");
			ctx.waitFor(mc -> !LocalizadorCliente.encendida() && !LocalizadorCliente.barraVisible(), 100);
			paso("Al terminar la partida se apagó la barra");
			sp.getServer().waitFor(s -> Juego.INSTANCIA.partida() == null, 20 * 30);
			ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 200);
			ctx.waitTicks(40);
			comprobar(ctx.computeOnClient(mc -> !LocalizadorCliente.encendida() && LocalizadorCliente.nombres().isEmpty()
				&& !LocalizadorCliente.barraVisible()), "de vuelta en el lobby no hay barra");
			captura(ctx, "localizador_11_lobby");
			paso("De vuelta en el lobby, sin barra");

			sp.getServer().runCommand("player Bot1 kill");
			sp.getServer().runCommand("player Bot2 kill");
		}
	}

	// ---------------------------------------------------------------- utilidades

	/** Grados en la barra de un punto a (dx, dz) del jugador que mira con ese yaw; positivo = derecha. */
	private static double gradosHacia(double dx, double dz, float yaw) {
		return Mth.wrapDegrees(Math.toDegrees(Math.atan2(-dx, dz)) - yaw);
	}

	/** Lleva a alguien al centro del bloque (x, z) de la plataforma, mirando con ese yaw y pitch. */
	private static void ponerEn(TestSingleplayerContext sp, String quien, int x, int z, float yaw, float pitch) {
		ejecutar(sp, "tp " + quien + " " + (x + 0.5) + " " + ALTURA + " " + (z + 0.5) + " " + yaw + " " + pitch);
	}

	private static void ponerEn(TestSingleplayerContext sp, String quien, int x, int y, int z) {
		ejecutar(sp, "tp " + quien + " " + (x + 0.5) + " " + y + " " + (z + 0.5));
	}

	/** Comando en el mundo de Player0 (un tp desde la consola sin "at" lleva a la dimensión de la consola). */
	private static void ejecutar(TestSingleplayerContext sp, String comando) {
		sp.getServer().runCommand("execute as Player0 at @s run " + comando);
	}

	/** El anfitrión no puede cambiar opciones a media partida; la prueba cambia la configuración copiada de la partida. */
	private static void ponerRivales(TestSingleplayerContext sp, boolean valor) {
		sp.getServer().runOnServer(s -> {
			try {
				Field cfg = Partida.class.getDeclaredField("cfg");
				cfg.setAccessible(true);
				Opcion.LOCALIZADOR_RIVALES.poner((Configuracion) cfg.get(Juego.INSTANCIA.partida()), valor);
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException(e);
			}
		});
	}

	/** Pone (o quita) una guía en el cliente como si la hubiera mandado el servidor. */
	private static void ponerGuia(ClientGameTestContext ctx, PaquetesTienda.Guia guia) {
		ctx.runOnClient(mc -> {
			try {
				Field campo = AyudasCliente.class.getDeclaredField("guia");
				campo.setAccessible(true);
				campo.set(null, guia);
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException(e);
			}
		});
	}

	private static Optional<Dibujado> dibujado(ClientGameTestContext ctx, String nombre) {
		return ctx.computeOnClient(mc -> dibujado(nombre));
	}

	private static Optional<Dibujado> dibujado(String nombre) {
		return LocalizadorCliente.dibujados().stream().filter(d -> d.tipo() == Tipo.JUGADOR && d.nombre().equals(nombre)).findFirst();
	}

	private static long distancia(TestSingleplayerContext sp, String nombre) {
		return sp.getServer().computeOnServer(s -> Math.round(Math.sqrt(bot(s, nombre).distanceToSqr(bot(s, "Player0")))));
	}

	/** Captura sin avisos de logros y recetas. */
	private static void captura(ClientGameTestContext ctx, String nombre) {
		ctx.runOnClient(mc -> mc.gui.toastManager().clear());
		ctx.waitTicks(2);
		ctx.takeScreenshot(nombre);
	}

	private static KeyMapping tecla(ClientGameTestContext ctx, String nombre) {
		return ctx.computeOnClient(mc -> Arrays.stream(mc.options.keyMappings)
			.filter(k -> k.getName().equals(nombre)).findFirst().orElseThrow());
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
		Venatio.LOG.info("PRUEBA LOCALIZADOR ✔ {}", texto);
	}
}
