package dev.venatio.prueba;

import com.mojang.blaze3d.platform.InputConstants;
import dev.venatio.Venatio;
import dev.venatio.ayudas.TipoAyuda;
import dev.venatio.carton.Carton;
import dev.venatio.cliente.Animaciones;
import dev.venatio.cliente.Arte;
import dev.venatio.cliente.PantallaAnfitrion;
import dev.venatio.cliente.PantallaCarton;
import dev.venatio.cliente.PantallaFinal;
import dev.venatio.cliente.PantallaObjetivos;
import dev.venatio.cliente.PantallaTienda;
import dev.venatio.cliente.Sonidos;
import dev.venatio.cliente.fichas.PantallaFicha;
import dev.venatio.cliente.fichas.PantallaItemsPosibles;
import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Fuegos;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Partida;
import dev.venatio.lobby.ConstructorLobby;
import dev.venatio.mundo.Lobby;
import dev.venatio.red.PaquetesTienda;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Items;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Arte, sonidos y animaciones con un cliente real y un bot rival (Bot1, equipo Azul).
 * Comprueba que cada sprite está en el atlas y que cada sonido existe, se decodifica y suena sin avisos en el log.
 * Para capturar cada animación a la mitad, detiene el reloj de las animaciones ({@link Animaciones#fijarReloj}).
 * Tres partidas cortas: 5 en línea sin fin automático (bingo, victoria), Rastreo carrera (derrota) y Lockout (cancelada).
 * Deja capturas arte_*.png en screenshots/.
 */
public final class PruebaArte implements FabricClientGameTest {
	/** Sonidos que el motor de sonido llegó a tocar durante la prueba, en orden. */
	private static final List<String> oidos = new CopyOnWriteArrayList<>();

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 600);
			ctx.waitTicks(40);
			ctx.runOnClient(mc -> mc.getSoundManager().addListener((sonido, evento, alcance) -> oidos.add(sonido.getIdentifier().toString())));

			recursos(ctx);

			sp.getServer().runCommand("player Bot1 spawn");
			sp.getServer().waitFor(s -> bot(s) != null && Lobby.esLobby(bot(s).level()), 200);
			sp.getServer().runCommand("execute as Player0 run venatio equipo rojo");
			sp.getServer().runCommand("execute as Bot1 run venatio equipo azul");
			sp.getServer().runCommand("venatio tiempo 0");
			ctx.waitTicks(20);
			ctx.takeScreenshot("arte_01_lobby");
			paso("HUD del lobby con el emblema del equipo");

			pantallasDelLobby(ctx, sp);
			partidaDeLinea(ctx, sp);
			partidaDeRastreo(ctx, sp);
			partidaDeLockout(ctx, sp);

			sonidos(ctx);
			sp.getServer().runCommand("player Bot1 kill");
			sp.getServer().runCommand("venatio modo linea");
			sp.getServer().runCommand("venatio opcion fin_automatico si");
			sp.getServer().runCommand("venatio meta 10");
		} finally {
			ctx.runOnClient(mc -> Animaciones.fijarReloj(null));
		}
	}

	// ---------------------------------------------------------------- recursos

	/** Sprites en el atlas de la interfaz, icono del mod y sonidos que el juego resuelve y sabe decodificar. */
	private static void recursos(ClientGameTestContext ctx) {
		List<String> faltan = ctx.computeOnClient(mc -> {
			List<String> lista = new ArrayList<>();
			TextureAtlas atlas = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.GUI);
			for (Identifier id : Arte.todos()) {
				if (atlas.getSprite(id) == atlas.missingSprite()) lista.add("sprite " + id);
			}
			if (mc.getResourceManager().getResource(Identifier.fromNamespaceAndPath("venatio", "icon.png")).isEmpty()) lista.add("icon.png");
			for (SoundEvent evento : Sonidos.TODOS) {
				WeighedSoundEvents variantes = mc.getSoundManager().getSoundEvent(evento.location());
				if (variantes == null) {
					lista.add("evento de sonido " + evento.location());
					continue;
				}
				if (variantes.getSubtitle() == null) lista.add("subtítulo de " + evento.location());
				// Cada variante: que el .ogg exista y que el decodificador del juego lo lea entero.
				Set<Identifier> archivos = new java.util.HashSet<>();
				RandomSource azar = RandomSource.create(7);
				for (int i = 0; i < 40; i++) archivos.add(variantes.getSound(azar).getPath());
				for (Identifier archivo : archivos) {
					var recurso = mc.getResourceManager().getResource(archivo);
					if (recurso.isEmpty()) {
						lista.add("archivo " + archivo);
						continue;
					}
					try (InputStream entrada = recurso.get().open(); JOrbisAudioStream audio = new JOrbisAudioStream(entrada)) {
						int[] muestras = {0};
						while (audio.readChunk(m -> muestras[0]++)) { /* hasta el final */ }
						if (muestras[0] < 1000) lista.add("audio vacío en " + archivo);
					} catch (Exception e) {
						lista.add("no se pudo decodificar " + archivo + ": " + e);
					}
				}
			}
			return lista;
		});
		comprobar(faltan.isEmpty(), "recursos de arte y sonido presentes (faltan: " + faltan + ")");
		paso(Arte.todos().size() + " sprites en el atlas, icono del mod y " + Sonidos.TODOS.size() + " eventos de sonido que el juego resuelve y decodifica");
	}

	/** Todos los sonidos tienen que haber sonado de verdad y el log no puede traer avisos de sonidos o texturas que falten. */
	private static void sonidos(ClientGameTestContext ctx) {
		// Los que no salieron jugando se tocan aquí, para que el motor de sonido los cargue igual.
		for (SoundEvent evento : Sonidos.TODOS) {
			if (!oidos.contains(evento.location().toString())) {
				paso("(tocado a mano, no salió en las partidas: " + evento.location() + ")");
				ctx.runOnClient(mc -> Sonidos.tocar(evento));
				ctx.waitTicks(4);
			}
		}
		ctx.waitTicks(40);
		List<String> sinSonar = new ArrayList<>();
		for (SoundEvent evento : Sonidos.TODOS) if (!oidos.contains(evento.location().toString())) sinSonar.add(evento.location().toString());
		comprobar(sinSonar.isEmpty(), "el motor de sonido tocó todos los sonidos (sin sonar: " + sinSonar + ")");

		Path log = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");
		List<String> avisos = new ArrayList<>();
		List<String> lineas;
		try {
			lineas = Files.readAllLines(log);
		} catch (java.io.IOException e) {
			throw new AssertionError("No se pudo leer " + log, e);
		}
		for (String linea : lineas) {
			String l = linea.toLowerCase(java.util.Locale.ROOT);
			boolean deVenatio = l.contains("venatio:") || l.contains("venatio/");
			boolean aviso = l.contains("unable to play") || l.contains("missing sound") || l.contains("does not exist")
				|| l.contains("missing subtitle") || l.contains("failed to load") || l.contains("unable to load") || l.contains("using missing texture")
				|| l.contains("invalid sounds.json") || l.contains("missing sprite");
			if (deVenatio && aviso) avisos.add(linea);
		}
		comprobar(avisos.isEmpty(), "sin avisos de sonidos o texturas de Venatio en el log (" + avisos + ")");
		paso("Los " + Sonidos.TODOS.size() + " sonidos sonaron y el log no trae avisos de recursos que falten");
	}

	// ---------------------------------------------------------------- pantallas

	private static void pantallasDelLobby(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		ctx.setScreen(PantallaCarton::new);
		ctx.waitTicks(15);
		ctx.takeScreenshot("arte_02_carton_sin_partida");
		ctx.setScreen(() -> null);

		ctx.setScreen(() -> new PantallaItemsPosibles(null, Set.of()));
		ctx.waitFor(mc -> mc.gui.screen() instanceof PantallaItemsPosibles p && p.cargada(), 200);
		ctx.getInput().setCursorPos(0, 0);
		ctx.waitTicks(5);
		ctx.takeScreenshot("arte_03_items_posibles");
		ctx.setScreen(() -> new PantallaFicha(null, Items.CAKE));
		ctx.waitFor(mc -> mc.gui.screen() instanceof PantallaFicha p && p.cargada(), 200);
		ctx.waitTicks(5);
		ctx.takeScreenshot("arte_04_ficha");
		ctx.setScreen(() -> null);

		// Panel del anfitrión: clic derecho en el atril.
		var atril = ConstructorLobby.panelAnfitrion();
		sp.getServer().runOnServer(s -> s.getPlayerList().op(jugador(s).nameAndId()));
		sp.getServer().runCommand("execute as Player0 at @s run tp @s " + (atril.getX() + 0.5) + " " + atril.getY() + " " + (atril.getZ() - 1.5) + " 0 30");
		ctx.waitTicks(20);
		ctx.getInput().lookAt(atril);
		ctx.waitTicks(5);
		ctx.getInput().pressKey(o -> o.keyUse);
		ctx.waitForScreen(PantallaAnfitrion.class);
		ctx.waitTicks(10);
		ctx.takeScreenshot("arte_05_panel_anfitrion");
		ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
		ctx.waitFor(mc -> mc.gui.screen() == null, 40);
		paso("Pantallas con marco propio: cartón sin partida, items posibles, ficha y panel del anfitrión");
	}

	// ---------------------------------------------------------------- partida 1: 5 en línea

	private static void partidaDeLinea(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runCommand("venatio modo linea");
		sp.getServer().runCommand("venatio opcion fin_automatico no");
		int ticAntes = veces(ctx, Sonidos.CUENTA_TIC);
		sp.getServer().runCommand("venatio iniciar");

		// Cuenta atrás: entrada del cartón y números que escalan.
		ctx.waitFor(mc -> Animaciones.finDeCuenta() != Long.MIN_VALUE && mc.level != null && !mc.level.dimension().toString().contains("lobby")
			&& mc.gui.screen() == null && Animaciones.inicioEntrada() != Long.MIN_VALUE, 20 * 180);
		long entrada = ctx.computeOnClient(mc -> Animaciones.inicioEntrada());
		long fin = ctx.computeOnClient(mc -> Animaciones.finDeCuenta());
		reloj(ctx, entrada + 70);
		ctx.takeScreenshot("arte_10_entrada_carton_deslizando");
		reloj(ctx, entrada + 480);
		ctx.takeScreenshot("arte_11_entrada_carton_casillas");
		reloj(ctx, fin - 6900);
		ctx.takeScreenshot("arte_12_cuenta_7_entrando");
		reloj(ctx, fin - 2600);
		ctx.takeScreenshot("arte_13_cuenta_3");
		reloj(ctx, null);
		paso("Cuenta atrás dibujada por el cliente y entrada del cartón");

		sp.getServer().waitFor(s -> fase(s) == Partida.Fase.JUGANDO, 20 * 30);
		ctx.waitFor(mc -> Animaciones.inicioYa() != Long.MIN_VALUE, 100);
		long ya = ctx.computeOnClient(mc -> Animaciones.inicioYa());
		reloj(ctx, ya + 110);
		ctx.takeScreenshot("arte_14_a_cazar_entrando");
		reloj(ctx, ya + 400);
		ctx.takeScreenshot("arte_15_a_cazar");
		reloj(ctx, null);
		comprobar(veces(ctx, Sonidos.CUENTA_TIC) - ticAntes >= 5, "la cuenta atrás sonó cada segundo (" + (veces(ctx, Sonidos.CUENTA_TIC) - ticAntes) + " tics)");
		comprobar(veces(ctx, Sonidos.CUENTA_YA) >= 1, "sonó el ¡ya!");
		paso("¡A cazar! y " + (veces(ctx, Sonidos.CUENTA_TIC) - ticAntes) + " tics de cuenta atrás");
		ctx.waitTicks(40);
		ctx.takeScreenshot("arte_16_hud_jugando");

		// Casilla mía en el HUD.
		long t = congelar(ctx);
		sp.getServer().runCommand("give Player0 " + item(sp, 0));
		ctx.waitFor(mc -> Animaciones.animando(0), 100);
		reloj(ctx, t + 200);
		ctx.takeScreenshot("arte_17_casilla_mia_hud");
		reloj(ctx, null);
		comprobar(veces(ctx, Sonidos.CASILLA) >= 1, "sonó la casilla propia");

		// Casilla de un rival en el HUD.
		t = congelar(ctx);
		sp.getServer().runCommand("give Bot1 " + item(sp, 7));
		ctx.waitFor(mc -> Animaciones.animando(7), 100);
		reloj(ctx, t + 150);
		ctx.takeScreenshot("arte_18_casilla_rival_hud");
		reloj(ctx, null);
		comprobar(veces(ctx, Sonidos.CASILLA_RIVAL) >= 1, "sonó la casilla del rival");
		paso("Casilla propia (pulso y destello) y casilla de rival (aviso discreto) en el HUD");

		// Pantalla B: entrada, casilla mía y de rival.
		int abrirAntes = veces(ctx, Sonidos.CARTON_ABRIR);
		t = congelar(ctx);
		ctx.setScreen(PantallaCarton::new);
		ctx.waitTicks(3);
		reloj(ctx, t + 130);
		ctx.takeScreenshot("arte_19_pantalla_carton_entrando");
		reloj(ctx, null);
		comprobar(veces(ctx, Sonidos.CARTON_ABRIR) == abrirAntes + 1, "abrir el cartón sonó una vez");
		ctx.waitTicks(15);
		ctx.takeScreenshot("arte_20_pantalla_carton");
		t = congelar(ctx);
		sp.getServer().runCommand("give Player0 " + item(sp, 1));
		sp.getServer().runCommand("give Bot1 " + item(sp, 8));
		ctx.waitFor(mc -> Animaciones.animando(1) && Animaciones.animando(8), 100);
		reloj(ctx, t + 220);
		ctx.takeScreenshot("arte_21_pantalla_carton_casillas");
		reloj(ctx, null);
		ctx.getInput().pressKey(InputConstants.KEY_RIGHT);
		ctx.waitTicks(10);
		ctx.takeScreenshot("arte_22_pantalla_carton_del_rival");
		ctx.setScreen(() -> null);
		paso("Pantalla del cartón: entrada, casilla propia, casilla de rival y vista del equipo rival");

		tiendaYAyudas(ctx, sp);

		// Línea completa con el fin automático apagado: banner de bingo y la partida sigue.
		int n = sp.getServer().computeOnServer(s -> Juego.INSTANCIA.partida().carton().tamano);
		for (int i = 2; i < n - 1; i++) sp.getServer().runCommand("give Player0 " + item(sp, i));
		// Al menos n - 1: si el cartón trae diamante, el que se dio para la tienda también marcó su casilla.
		sp.getServer().waitFor(s -> Juego.INSTANCIA.partida().carton().puntos(ColorEquipo.ROJO) >= n - 1, 200);
		ctx.waitTicks(30);
		t = congelar(ctx);
		sp.getServer().runCommand("give Player0 " + item(sp, n - 1));
		ctx.waitFor(mc -> Animaciones.inicioBanner() != Long.MIN_VALUE, 100);
		comprobar(sp.getServer().computeOnServer(s -> fase(s) == Partida.Fase.JUGANDO), "con fin automático apagado la partida sigue tras el bingo");
		reloj(ctx, t + 230);
		ctx.takeScreenshot("arte_30_bingo_banner_entrando");
		reloj(ctx, t + 1600);
		ctx.takeScreenshot("arte_31_bingo_banner_confeti");
		reloj(ctx, null);
		comprobar(veces(ctx, Sonidos.BINGO) >= 1, "sonó la fanfarria de bingo");
		paso("Bingo con la partida en curso: banner, confeti y fanfarria");
		ctx.waitTicks(70);

		// El anfitrión termina: gana Rojo (hizo bingo). Banner, filas escalonadas, confeti y fuegos artificiales.
		// Antes, el jugador sube a lo más alto de su columna (la copa del árbol) para ver el cielo despejado.
		sp.getServer().runOnServer(s -> {
			ServerPlayer p = jugador(s);
			int alto = p.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ());
			p.teleportTo(p.level(), p.getBlockX() + 0.5, alto, p.getBlockZ() + 0.5, Set.of(), p.getYRot(), -30f, true);
		});
		ctx.waitTicks(20);
		float vida = sp.getServer().computeOnServer(s -> jugador(s).getHealth());
		t = congelar(ctx);
		sp.getServer().runCommand("venatio terminar");
		ctx.waitForScreen(PantallaFinal.class);
		ctx.waitTicks(2);
		reloj(ctx, t + 300);
		ctx.takeScreenshot("arte_32_final_entrando");
		reloj(ctx, t + 1700);
		ctx.takeScreenshot("arte_33_final_confeti");
		reloj(ctx, null);
		comprobar(veces(ctx, Sonidos.VICTORIA) >= 1, "sonó la victoria");
		ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
		ctx.waitFor(mc -> mc.gui.screen() == null, 40);
		// Dos participantes (Player0 y Bot1), dos cohetes cada uno por salva, cuatro salvas.
		sp.getServer().waitFor(s -> Fuegos.lanzados() >= 8, 100);
		ctx.waitTicks(26);
		ctx.takeScreenshot("arte_34_fuegos_artificiales");
		sp.getServer().waitFor(s -> Fuegos.lanzados() >= 16, 100);
		ctx.waitTicks(36);
		ctx.takeScreenshot("arte_35_fuegos_artificiales_fin");
		float vidaDespues = sp.getServer().computeOnServer(s -> jugador(s).getHealth());
		comprobar(vidaDespues >= vida, "los fuegos artificiales no dañan al jugador (" + vida + " → " + vidaDespues + ")");
		paso("Victoria: banner, filas escalonadas, confeti y " + sp.getServer().computeOnServer(s -> Fuegos.lanzados()) + " cohetes sin daño");
		volverAlLobby(ctx, sp);
	}

	/** Tienda con marco propio y los sonidos de compra, marca y llegada de la guía (que sustituyen a los vanilla). */
	private static void tiendaYAyudas(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runCommand("give Player0 minecraft:diamond 1");
		ctx.waitTicks(5);
		ctx.getInput().pressKey(tecla(ctx, "key.venatio.ayudas"));
		ctx.waitForScreen(PantallaTienda.class);
		ctx.waitTicks(10);
		ctx.takeScreenshot("arte_23_tienda");
		int compraAntes = veces(ctx, Sonidos.COMPRA);
		ctx.clickScreenButton("Comprar");
		ctx.waitFor(mc -> veces(Sonidos.COMPRA) == compraAntes + 1, 100);
		paso("Comprar el minimapa sonó a monedas");

		ctx.setScreen(() -> new PantallaObjetivos(TipoAyuda.GUIA_BIOMA, new PantallaTienda()));
		ctx.waitTicks(10);
		ctx.takeScreenshot("arte_24_objetivos");
		ctx.setScreen(() -> null);

		int desde = oidos.size();
		int marcaAntes = veces(ctx, Sonidos.MARCA);
		sp.getServer().runCommand("execute as Player0 at @s run tp @s ~ ~ ~ ~ 50");
		ctx.waitTicks(5);
		ctx.getInput().pressKey(tecla(ctx, "key.venatio.marca"));
		ctx.waitFor(mc -> veces(Sonidos.MARCA) == marcaAntes + 1, 100);

		// Guía nueva y llegada: se mandan los paquetes del servidor tal cual, sin esperar a caminar hasta un bioma.
		sp.getServer().runOnServer(s -> {
			ServerPlayer p = jugador(s);
			ServerPlayNetworking.send(p, new PaquetesTienda.Guia(0, Identifier.withDefaultNamespace("plains"),
				p.level().dimension().identifier(), p.getBlockX() + 300, p.getBlockZ(), 64, 120));
		});
		ctx.waitFor(mc -> veces(Sonidos.COMPRA) == compraAntes + 2, 100);
		ctx.waitTicks(10);
		sp.getServer().runOnServer(s -> ServerPlayNetworking.send(jugador(s), new PaquetesTienda.GuiaTerminada(PaquetesTienda.GuiaTerminada.LLEGADA)));
		ctx.waitFor(mc -> veces(Sonidos.GUIA_LLEGADA) >= 1, 100);
		ctx.waitTicks(5);
		List<String> ahora = List.copyOf(oidos.subList(desde, oidos.size()));
		List<String> vanilla = ahora.stream().filter(id -> id.equals("minecraft:block.note_block.bell") || id.equals("minecraft:entity.experience_orb.pickup")).toList();
		comprobar(vanilla.isEmpty(), "los sonidos vanilla de las ayudas quedaron sustituidos (sonaron: " + vanilla + ")");
		comprobar(ahora.contains("venatio:marca") && ahora.contains("venatio:compra") && ahora.contains("venatio:guia_llegada"),
			"sonaron marca, compra y llegada de la guía (" + ahora + ")");
		paso("Marca, guía comprada y llegada de la guía suenan con los sonidos propios en vez de los vanilla");
		sp.getServer().runCommand("execute as Player0 at @s run tp @s ~ ~ ~ ~ 0");
	}

	// ---------------------------------------------------------------- partida 2: Rastreo carrera

	private static void partidaDeRastreo(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runCommand("venatio modo rastreo_carrera");
		sp.getServer().runCommand("venatio opcion fin_automatico si");
		sp.getServer().runCommand("venatio meta 3");
		sp.getServer().runCommand("venatio iniciar");
		sp.getServer().waitFor(s -> fase(s) == Partida.Fase.JUGANDO, 20 * 180);
		ctx.waitTicks(50);
		ctx.takeScreenshot("arte_40_rastreo");

		// Item encontrado: la barra avanza con un destello y el item nuevo entra creciendo.
		long t = congelar(ctx);
		sp.getServer().runCommand("give Player0 " + actual(sp));
		ctx.waitFor(mc -> Animaciones.inicioDestello() != Long.MIN_VALUE, 100);
		ctx.waitTicks(3);
		reloj(ctx, t + 90);
		ctx.waitTicks(2);
		reloj(ctx, t + 180);
		ctx.takeScreenshot("arte_41_rastreo_encontrado");
		reloj(ctx, null);
		comprobar(veces(ctx, Sonidos.RASTREO_ENCONTRADO) >= 1, "sonó el item encontrado");
		ctx.waitTicks(30);
		ctx.takeScreenshot("arte_42_rastreo_uno_de_tres");
		paso("Rastreo: barra de progreso animada y sonido de item encontrado");

		// El rival llega a la meta: derrota.
		for (int i = 0; i < 3; i++) {
			int esperado = i + 1;
			sp.getServer().runCommand("give Bot1 " + actual(sp));
			sp.getServer().waitFor(s -> fase(s) == Partida.Fase.FINAL || Juego.INSTANCIA.partida().carton().puntos(ColorEquipo.AZUL) >= esperado, 200);
		}
		sp.getServer().waitFor(s -> fase(s) == Partida.Fase.FINAL, 200);
		ctx.waitForScreen(PantallaFinal.class);
		ctx.waitTicks(30);
		ctx.takeScreenshot("arte_43_final_derrota");
		comprobar(veces(ctx, Sonidos.DERROTA) >= 1, "sonó la derrota");
		paso("Ganó el rival: banner con su color y sonido de derrota");
		ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
		volverAlLobby(ctx, sp);
	}

	// ---------------------------------------------------------------- partida 3: Lockout

	private static void partidaDeLockout(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runCommand("venatio modo lockout");
		sp.getServer().runCommand("venatio iniciar");
		sp.getServer().waitFor(s -> fase(s) == Partida.Fase.JUGANDO, 20 * 180);
		ctx.waitTicks(30);
		sp.getServer().runCommand("give Bot1 " + item(sp, 0));
		sp.getServer().runCommand("give Bot1 " + item(sp, 6));
		sp.getServer().runCommand("give Player0 " + item(sp, 1));
		sp.getServer().waitFor(s -> Juego.INSTANCIA.partida().carton().puntos(ColorEquipo.AZUL) == 2
			&& Juego.INSTANCIA.partida().carton().puntos(ColorEquipo.ROJO) == 1, 200);
		ctx.waitTicks(30);
		ctx.takeScreenshot("arte_50_lockout_hud");
		ctx.setScreen(PantallaCarton::new);
		ctx.waitTicks(15);
		ctx.takeScreenshot("arte_51_lockout_pantalla");
		ctx.setScreen(() -> null);
		paso("Lockout: casillas del rival bloqueadas con su color y candado");

		// El anfitrión termina sin ganador: banner neutro y campanas.
		int cohetes = sp.getServer().computeOnServer(s -> Fuegos.lanzados());
		sp.getServer().runCommand("venatio terminar");
		ctx.waitForScreen(PantallaFinal.class);
		ctx.waitTicks(30);
		ctx.takeScreenshot("arte_52_final_cancelada");
		comprobar(veces(ctx, Sonidos.FIN) >= 1, "sonó el fin sin ganador");
		ctx.waitTicks(40);
		comprobar(sp.getServer().computeOnServer(s -> Fuegos.lanzados()) == cohetes, "sin ganador no hay fuegos artificiales");
		paso("Partida cancelada: banner neutro, sin confeti ni fuegos");
		ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
		volverAlLobby(ctx, sp);
	}

	// ---------------------------------------------------------------- utilidades

	private static void volverAlLobby(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().waitFor(s -> Juego.INSTANCIA.partida() == null, 20 * 30);
		ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 200);
		ctx.runOnClient(mc -> mc.gui.setScreen(null));
		ctx.waitTicks(30);
	}

	/** Detiene el reloj de las animaciones en el instante actual y lo devuelve. */
	private static long congelar(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> {
			long t = Animaciones.ahora();
			Animaciones.fijarReloj(t);
			return t;
		});
	}

	private static void reloj(ClientGameTestContext ctx, Long ms) {
		ctx.runOnClient(mc -> Animaciones.fijarReloj(ms));
		ctx.waitTicks(2);
	}

	private static int veces(ClientGameTestContext ctx, SoundEvent sonido) {
		return ctx.computeOnClient(mc -> Sonidos.veces(sonido));
	}

	private static int veces(SoundEvent sonido) {
		return Sonidos.veces(sonido);
	}

	private static String item(TestSingleplayerContext sp, int indice) {
		return sp.getServer().computeOnServer(s -> {
			Carton c = Juego.INSTANCIA.partida().carton();
			return BuiltInRegistries.ITEM.getKey(c.casillas.get(indice).icono.getItem()).toString();
		});
	}

	private static String actual(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(s -> BuiltInRegistries.ITEM.getKey(
			Juego.INSTANCIA.partida().rastreo().actual(ColorEquipo.ROJO).icono().getItem()).toString());
	}

	private static KeyMapping tecla(ClientGameTestContext ctx, String nombre) {
		return ctx.computeOnClient(mc -> Arrays.stream(mc.options.keyMappings)
			.filter(k -> k.getName().equals(nombre)).findFirst().orElseThrow());
	}

	private static ServerPlayer bot(MinecraftServer s) {
		return s.getPlayerList().getPlayerByName("Bot1");
	}

	private static ServerPlayer jugador(MinecraftServer s) {
		return s.getPlayerList().getPlayerByName("Player0");
	}

	private static Partida.Fase fase(MinecraftServer s) {
		Partida p = Juego.INSTANCIA.partida();
		return p == null ? null : p.fase();
	}

	private static void comprobar(boolean condicion, String que) {
		if (!condicion) throw new AssertionError("Falló: " + que);
	}

	private static void paso(String texto) {
		Venatio.LOG.info("PRUEBA ARTE ✔ {}", texto);
	}
}
