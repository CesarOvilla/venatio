package dev.venatio.prueba;

import dev.venatio.Venatio;
import dev.venatio.carton.Carton;
import dev.venatio.carton.Dificultad;
import dev.venatio.carton.Exclusiones;
import dev.venatio.carton.Objetivo;
import dev.venatio.carton.Pool;
import dev.venatio.carton.Reglas;
import dev.venatio.carton.Reto;
import dev.venatio.carton.Retos;
import dev.venatio.cliente.PantallaCarton;
import dev.venatio.cliente.PantallaFinal;
import dev.venatio.cliente.fichas.PantallaFicha;
import dev.venatio.cliente.fichas.PantallaItemsPosibles;
import dev.venatio.info.Fichas;
import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Partida;
import dev.venatio.mundo.Lobby;
import dev.venatio.red.PaquetesFicha.Ficha;
import dev.venatio.red.PaquetesFicha.Fuente;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Cartón ampliado con un cliente real: casillas de logro (bioma, mob, dormir, altura), casillas con cantidad que junta
 * el equipo entre dos jugadores (un bot de Carpet), dificultad del cartón, items quitados del pool (comando y clic
 * derecho en "Items posibles") y la secuencia del Rastreo con logros y cantidades. Capturas carton_amp_* en screenshots/.
 */
public final class PruebaCartonAmpliado implements FabricClientGameTest {
	private static final long SEMILLA = 424242L;
	/** Biomas comunes para el logro de bioma: se usa el primero que haya cerca y que no sea el del spawn. */
	private static final List<String> BIOMAS_CANDIDATOS = List.of("bioma_oceano", "bioma_desierto", "bioma_sabana", "bioma_taiga",
		"bioma_bosque_oscuro", "bioma_abedules", "bioma_pantano", "bioma_nieve", "bioma_jungla", "bioma_playa");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 600);
			idioma(ctx, "es_mx");
			ctx.waitTicks(20);
			// Por si una corrida anterior se cortó a medias: pool completo.
			sp.getServer().runOnServer(s -> Exclusiones.lista().forEach(e -> Exclusiones.cambiar(e, false)));

			catalogo(ctx, sp);
			dificultad(sp);
			exclusiones(ctx, sp);
			partida(ctx, sp);
			rastreo(sp);

			sp.getServer().runCommand("venatio opcion logros no");
			sp.getServer().runCommand("venatio opcion cantidades no");
			sp.getServer().runCommand("venatio dificultad normal");
			sp.getServer().runCommand("venatio semilla aleatoria");
			sp.getServer().runCommand("venatio modo linea");
			sp.getServer().runCommand("venatio pool poner minecraft:emerald");
			sp.getServer().runOnServer(s -> s.getPlayerList().deop(jugador(s).nameAndId()));
			comprobar(sp.getServer().computeOnServer(s -> Exclusiones.lista().isEmpty()), "el pool quedó completo al terminar");
			idioma(ctx, "en_us");
		}
	}

	// ---------------------------------------------------------------- catálogo

	/** Todos los logros apuntan a cosas que existen en 26.3, ninguno es del End, y todos tienen nombre en español. */
	private static void catalogo(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		String error = sp.getServer().computeOnServer(s -> {
			var biomas = s.registryAccess().lookupOrThrow(Registries.BIOME);
			for (Reto r : Retos.TODOS) {
				if (r.itemIcono() == Items.BARRIER) return r.id() + ": icono desconocido " + r.icono();
				if (r.relacionado() != null && !BuiltInRegistries.ITEM.containsKey(r.relacionado())) return r.id() + ": item relacionado desconocido";
				for (Identifier id : r.objetivos()) {
					boolean existe = switch (r.tipo()) {
						case BIOMA -> biomas.containsKey(ResourceKey.create(Registries.BIOME, id)) && !id.getPath().contains("end");
						case MATAR -> BuiltInRegistries.ENTITY_TYPE.containsKey(id);
						case COMER -> BuiltInRegistries.ITEM.containsKey(id);
						case MINAR -> BuiltInRegistries.BLOCK.containsKey(id);
						default -> BuiltInRegistries.CUSTOM_STAT.containsKey(id);
					};
					if (!existe) return r.id() + ": no existe " + id;
				}
			}
			Ficha creeper = Fichas.fichaReto(Retos.porId("matar_creeper"));
			if (creeper.fuentes().stream().noneMatch(f -> f.tipo() == Fuente.MOB && f.id().getPath().equals("creeper") && !f.biomas().isEmpty())) {
				return "la ficha de matar un creeper no dice dónde aparece";
			}
			Ficha dormir = Fichas.fichaReto(Retos.porId("dormir"));
			if (dormir.recetas().isEmpty()) return "la ficha de dormir no trae la receta de la cama";
			Ficha pantano = Fichas.fichaReto(Retos.porId("bioma_pantano"));
			if (pantano.fuentes().stream().filter(f -> f.tipo() == Fuente.BIOMA && f.guia().isPresent()).count() != 2) {
				return "la ficha del pantano no trae los dos pantanos con Guíame";
			}
			return null;
		});
		comprobar(error == null, "catálogo de logros: " + error);
		List<String> sinNombre = ctx.computeOnClient(mc -> Retos.TODOS.stream()
			.filter(r -> !Language.getInstance().has("venatio.reto." + r.id())).map(Reto::id).toList());
		comprobar(sinNombre.isEmpty(), "logros sin nombre: " + sinNombre);
		paso(Retos.TODOS.size() + " logros válidos en 26.3, sin nada del End y con nombre; fichas de creeper, dormir y pantano");
	}

	// ---------------------------------------------------------------- dificultad

	private static void dificultad(TestSingleplayerContext sp) {
		// Con las reglas clásicas, la misma semilla da el mismo cartón que antes de esta versión.
		boolean igual = sp.getServer().computeOnServer(s -> {
			Pool pool = Juego.INSTANCIA.pool();
			for (long semilla = 1; semilla <= 5; semilla++) {
				List<String> nuevo = Carton.generar(pool, semilla, 5).casillas.stream().map(c -> c.objetivo.texto()).toList();
				if (!nuevo.equals(cartonViejo(pool, semilla, 5))) return false;
			}
			return true;
		});
		comprobar(igual, "con reglas clásicas la semilla da el mismo cartón que antes");

		sp.getServer().runCommand("venatio dificultad facil");
		int[] facil = sp.getServer().computeOnServer(s -> niveles(Juego.INSTANCIA.config.dificultad == Dificultad.FACIL));
		sp.getServer().runCommand("venatio dificultad dificil");
		int[] dificil = sp.getServer().computeOnServer(s -> niveles(Juego.INSTANCIA.config.dificultad == Dificultad.DIFICIL));
		comprobar(facil[0] >= 0 && dificil[0] >= 0, "el comando /venatio dificultad no cambió la configuración");
		int total = facil[1] + facil[2] + facil[3];
		comprobar(facil[1] * 100 / total >= 45 && facil[3] * 100 / total <= 20,
			"fácil: casillas de nivel 1/2/3 = " + facil[1] + "/" + facil[2] + "/" + facil[3]);
		comprobar(dificil[3] * 100 / total >= 45 && dificil[1] * 100 / total <= 20,
			"difícil: casillas de nivel 1/2/3 = " + dificil[1] + "/" + dificil[2] + "/" + dificil[3]);

		sp.getServer().runCommand("venatio dificultad mixta");
		boolean tercios = sp.getServer().computeOnServer(s -> {
			for (long semilla = 1; semilla <= 20; semilla++) {
				int[] n = new int[4];
				for (Carton.Casilla c : Carton.generar(Juego.INSTANCIA.pool(), semilla, 5, Juego.INSTANCIA.config.reglas()).casillas) n[c.dificultad]++;
				if (n[1] != 9 || n[2] != 8 || n[3] != 8) return false;
			}
			return true;
		});
		comprobar(tercios, "mixta reparte 9/8/8 en cada cartón de 5×5");
		sp.getServer().runCommand("venatio dificultad normal");
		paso("Dificultad en 20 cartones: fácil " + facil[1] + "/" + facil[2] + "/" + facil[3] + " (niveles 1/2/3), difícil "
			+ dificil[1] + "/" + dificil[2] + "/" + dificil[3] + "; mixta 9/8/8; clásica igual que antes");
	}

	/** Niveles de las casillas de 20 cartones con la configuración actual: [ok, nivel 1, nivel 2, nivel 3]. */
	private static int[] niveles(boolean configurada) {
		int[] n = new int[4];
		n[0] = configurada ? 0 : -1;
		for (long semilla = 1; semilla <= 20; semilla++) {
			for (Carton.Casilla c : Carton.generar(Juego.INSTANCIA.pool(), semilla, 5, Juego.INSTANCIA.config.reglas()).casillas) n[c.dificultad]++;
		}
		return n;
	}

	/** El generador de antes de las reglas, tal cual, para comparar. */
	private static List<String> cartonViejo(Pool pool, long semilla, int tamano) {
		Random rnd = new Random(semilla ^ 0x5DEECE66DL);
		List<Integer> indices = new ArrayList<>();
		for (int i = 0; i < pool.categorias().size(); i++) indices.add(i);
		Collections.shuffle(indices, rnd);
		List<String> lista = new ArrayList<>();
		for (int i = 0; i < tamano * tamano; i++) {
			Map<Integer, String> cat = pool.categorias().get(indices.get(i));
			int d = 1 + rnd.nextInt(3);
			lista.add(cat.getOrDefault(d, cat.values().iterator().next()));
		}
		return lista;
	}

	// ---------------------------------------------------------------- quitar items del pool

	private static void exclusiones(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		// La esmeralda es su categoría entera (en los tres niveles): sin quitarla sale en casi todos los cartones.
		int conEsmeralda = sp.getServer().computeOnServer(s -> cartonesCon(Juego.INSTANCIA.poolDePartida(), "minecraft:emerald"));
		comprobar(conEsmeralda > 0, "sin quitarla, la esmeralda sale en algún cartón");

		sp.getServer().runCommand("venatio pool quitar minecraft:emerald");
		sp.getServer().runCommand("venatio pool quitar minecraft:signs");
		sp.getServer().runCommand("venatio pool lista");
		String error = sp.getServer().computeOnServer(s -> {
			if (!Exclusiones.excluida("minecraft:emerald") || !Exclusiones.excluida("#minecraft:signs")) return "no se quitaron";
			Pool pool = Juego.INSTANCIA.poolDePartida();
			if (pool.categorias().size() != Juego.INSTANCIA.pool().categorias().size() - 1) return "la categoría de la esmeralda no se quitó entera";
			if (pool.entradas().contains("#minecraft:signs") || !pool.entradas().contains("minecraft:armor_stand")) return "el grupo de letreros no se quitó bien";
			int n = cartonesCon(pool, "minecraft:emerald");
			if (n > 0) return "la esmeralda salió en " + n + " de 20 cartones";
			return null;
		});
		comprobar(error == null, "quitar del pool: " + error);
		Path archivo = FabricLoader.getInstance().getConfigDir().resolve("venatio").resolve("pool.json");
		comprobar(leer(archivo).contains("minecraft:emerald"), "se guardó en config/venatio/pool.json");
		paso("Esmeralda quitada: salía en " + conEsmeralda + " de 20 cartones y ahora en 0 (también con logros y cantidades); guardada en pool.json");

		// Clic derecho en "Items posibles" como operador.
		sp.getServer().runOnServer(s -> s.getPlayerList().op(jugador(s).nameAndId()));
		ctx.waitTicks(5);
		ctx.setScreen(() -> new PantallaItemsPosibles(null, Set.of()));
		ctx.waitFor(pantalla(PantallaItemsPosibles.class, p -> p.cargada() && p.indiceDe("minecraft:cocoa_beans") >= 0), 200);
		ctx.waitTicks(10);
		int indice = ctx.computeOnClient(mc -> ((PantallaItemsPosibles) mc.gui.screen()).indiceDe("minecraft:cocoa_beans"));
		int[] centro = ctx.computeOnClient(mc -> ((PantallaItemsPosibles) mc.gui.screen()).centroDe(indice));
		clic(ctx, centro[0], centro[1], 1);
		sp.getServer().waitFor(s -> Exclusiones.excluida("minecraft:cocoa_beans"), 100);
		ctx.waitTicks(10);
		ctx.takeScreenshot("carton_amp_01_items_posibles_quitados");
		paso("Clic derecho de operador en Items posibles quitó los granos de cacao; se ven tachados con la esmeralda y los letreros");
		clic(ctx, centro[0], centro[1], 1);
		sp.getServer().waitFor(s -> !Exclusiones.excluida("minecraft:cocoa_beans"), 100);
		ctx.getInput().setCursorPos(0, 0);
		ctx.setScreen(() -> null);
		sp.getServer().runCommand("venatio pool poner minecraft:signs");
		comprobar(sp.getServer().computeOnServer(s -> !Exclusiones.excluida("#minecraft:signs") && Exclusiones.excluida("minecraft:emerald")),
			"los letreros volvieron y la esmeralda sigue fuera");
		paso("Otro clic derecho volvió a poner el cacao; /venatio pool poner devolvió los letreros");
	}

	/** En cuántos de 20 cartones (semillas 1000 a 1019) sale la entrada, con reglas clásicas o con logros y cantidades. */
	private static int cartonesCon(Pool pool, String entrada) {
		int n = 0;
		for (long semilla = 1000; semilla < 1020; semilla++) {
			Reglas reglas = semilla % 2 == 0 ? Reglas.CLASICAS : new Reglas(Dificultad.NORMAL, true, true);
			if (Carton.generar(pool, semilla, 5, reglas).casillas.stream().anyMatch(c -> c.objetivo.base().equals(entrada))) n++;
		}
		return n;
	}

	// ---------------------------------------------------------------- partida con logros y cantidades

	private static void partida(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runCommand("player Bot1 spawn");
		sp.getServer().waitFor(s -> bot(s, "Bot1") != null && Lobby.esLobby(bot(s, "Bot1").level()), 200);
		sp.getServer().runCommand("execute as Player0 run venatio equipo rojo");
		sp.getServer().runCommand("execute as Bot1 run venatio equipo rojo");
		sp.getServer().runCommand("venatio modo apagon");
		sp.getServer().runCommand("venatio semilla " + SEMILLA);
		sp.getServer().runCommand("venatio opcion logros si");
		sp.getServer().runCommand("venatio opcion cantidades si");
		sp.getServer().runCommand("venatio iniciar");
		sp.getServer().waitFor(s -> fase() == Partida.Fase.JUGANDO, 20 * 180);
		ctx.waitTicks(40);

		int[] cuenta = sp.getServer().computeOnServer(s -> {
			int retos = 0, cantidades = 0, esmeraldas = 0;
			for (Carton.Casilla c : carton().casillas) {
				if (c.objetivo.esReto()) retos++;
				if (c.objetivo.cantidad() > 1) cantidades++;
				if (c.objetivo.base().equals("minecraft:emerald")) esmeraldas++;
			}
			return new int[]{retos, cantidades, esmeraldas};
		});
		comprobar(cuenta[0] >= 6 && cuenta[0] <= 8, "entre 6 y 8 casillas de logro de 25 (hay " + cuenta[0] + ")");
		comprobar(cuenta[1] >= 4, "al menos 4 casillas con cantidad (hay " + cuenta[1] + ")");
		comprobar(cuenta[2] == 0, "la esmeralda quitada no salió en el cartón de la partida");
		paso("Cartón con " + cuenta[0] + " logros y " + cuenta[1] + " casillas con cantidad, sin la esmeralda quitada");

		// Casillas libres para los logros de la prueba y una con cantidad.
		AtomicReference<BlockPos> destino = new AtomicReference<>();
		String bioma = sp.getServer().computeOnServer(s -> elegirBioma(jugador(s), destino));
		comprobar(bioma != null, "hay un bioma de la lista cerca del spawn");
		int[] libres = sp.getServer().computeOnServer(s -> {
			int[] l = new int[5];
			int k = 0;
			for (int i = 0; i < carton().casillas.size() && k < 5; i++) if (carton().casillas.get(i).logros.isEmpty()) l[k++] = i;
			Carton c = carton();
			c.reemplazar(l[0], Objetivo.de(Retos.porId(bioma)));
			c.reemplazar(l[1], Objetivo.de(Retos.porId("matar_creeper")));
			c.reemplazar(l[2], Objetivo.de(Retos.porId("dormir")));
			c.reemplazar(l[3], Objetivo.de(Retos.porId("altura_200")));
			c.reemplazar(l[4], Objetivo.de("minecraft:cobblestone").conCantidad(10));
			Juego.INSTANCIA.enviarEstadoATodos();
			return l;
		});
		int iBioma = libres[0], iCreeper = libres[1], iDormir = libres[2], iAltura = libres[3], iCantidad = libres[4];
		ctx.waitTicks(30);
		ctx.takeScreenshot("carton_amp_02_hud_logros");
		paso("Casillas de prueba: " + bioma + " (" + iBioma + "), matar_creeper (" + iCreeper + "), dormir (" + iDormir + "), altura_200 ("
			+ iAltura + "), 10 de piedra labrada (" + iCantidad + ")");

		// Pantalla B: tooltip de un logro y su ficha.
		ctx.setScreen(PantallaCarton::new);
		ctx.waitTicks(20);
		int[] creeper = ctx.computeOnClient(mc -> ((PantallaCarton) mc.gui.screen()).centroDe(iCreeper));
		apuntar(ctx, creeper[0], creeper[1]);
		ctx.waitTicks(5);
		ctx.takeScreenshot("carton_amp_03_pantalla_b_tooltip_logro");
		clic(ctx, creeper[0], creeper[1], 0);
		ctx.waitFor(pantalla(PantallaFicha.class, p -> p.reto() != null && p.reto().id().equals("matar_creeper") && p.cargada()), 200);
		ctx.getInput().setCursorPos(0, 0);
		ctx.waitTicks(10);
		ctx.takeScreenshot("carton_amp_04_ficha_matar_creeper");
		paso("Pantalla B con tooltip del logro; clic abrió su ficha (dónde aparece el creeper, con Guíame)");
		ctx.clickScreenButton("Volver");
		ctx.waitFor(pantalla(PantallaCarton.class, p -> true), 100);
		int[] celdaBioma = ctx.computeOnClient(mc -> ((PantallaCarton) mc.gui.screen()).centroDe(iBioma));
		clic(ctx, celdaBioma[0], celdaBioma[1], 0);
		ctx.waitFor(pantalla(PantallaFicha.class, p -> p.reto() != null && p.cargada()), 200);
		ctx.getInput().setCursorPos(0, 0);
		ctx.waitTicks(10);
		ctx.takeScreenshot("carton_amp_05_ficha_bioma");
		ctx.setScreen(() -> null);
		paso("Ficha del logro de bioma con sus biomas y Guíame");

		// Altura 200: sobre un bloque de vidrio en y = 204.
		BlockPos spawn = sp.getServer().computeOnServer(s -> jugador(s).blockPosition());
		sp.getServer().runCommand("execute as Player0 at @s run setblock ~ 204 ~ minecraft:glass");
		sp.getServer().runCommand("execute as Player0 at @s run tp @s ~ 205 ~");
		sp.getServer().waitFor(s -> rojo(iAltura), 100);
		paso("Logro de altura: subir a y = 205 marcó la casilla");
		// Con teleportTo en el mismo mundo: el /tp de la consola llevaría al overworld principal.
		sp.getServer().runOnServer(s -> {
			ServerPlayer p = jugador(s);
			p.teleportTo(p.level(), spawn.getX() + 0.5, spawn.getY() + 1, spawn.getZ() + 0.5, Set.of(), p.getYRot(), 0f, true);
		});
		ctx.waitTicks(20);

		// Matar un creeper.
		sp.getServer().runCommand("execute as Player0 at @s run summon minecraft:creeper ~3 ~ ~ {NoAI:1b}");
		ctx.waitTicks(2);
		sp.getServer().runCommand("execute as Player0 at @s run damage @e[type=minecraft:creeper,limit=1,sort=nearest] 1000 minecraft:player_attack by Player0");
		sp.getServer().waitFor(s -> rojo(iCreeper), 100);
		paso("Logro de mob: matar un creeper marcó la casilla");

		// Dormir: una cama a dos bloques; la regla de la prueba deja dormir de día (la estadística es la misma). En creativo
		// para que no lo impidan los monstruos de la cueva donde quedó el spawn.
		sp.getServer().runCommand("gamemode creative Player0");
		sp.getServer().runCommand("execute as Player0 at @s run fill ~1 ~ ~-1 ~2 ~2 ~1 minecraft:air");
		sp.getServer().runCommand("execute as Player0 at @s run setblock ~1 ~ ~ minecraft:red_bed[facing=east,part=foot]");
		sp.getServer().runCommand("execute as Player0 at @s run setblock ~2 ~ ~ minecraft:red_bed[facing=east,part=head]");
		ctx.waitTicks(2);
		String sueno = sp.getServer().computeOnServer(s -> {
			ServerPlayer p = jugador(s);
			BlockPos cabeza = BlockPos.containing(p.getX() + 2, p.getY(), p.getZ());
			BlockState cama = p.level().getBlockState(cabeza);
			if (!(cama.getBlock() instanceof AbstractBedBlock bloque)) return "no hay cama en " + cabeza + ": " + cama;
			BedRule siempre = new BedRule(BedRule.Rule.ALWAYS, BedRule.Rule.NEVER, false, false, Optional.empty());
			return p.startSleepInBed(bloque, cama, siempre, cabeza).left().map(Object::toString).orElse(null);
		});
		comprobar(sueno == null, "se acostó en la cama: " + sueno);
		sp.getServer().waitFor(s -> rojo(iDormir), 100);
		sp.getServer().runOnServer(s -> {
			if (jugador(s).isSleeping()) jugador(s).stopSleepInBed(true, true);
		});
		sp.getServer().runCommand("gamemode survival Player0");
		paso("Logro de dormir: acostarse en la cama marcó la casilla");

		// Bioma: teletransporte al bioma encontrado.
		sp.getServer().runOnServer(s -> {
			ServerPlayer p = jugador(s);
			BlockPos d = destino.get();
			p.teleportTo(p.level(), d.getX() + 0.5, d.getY(), d.getZ() + 0.5, Set.of(), p.getYRot(), 0f, true);
		});
		sp.getServer().waitFor(s -> rojo(iBioma), 200);
		ctx.waitTicks(20);
		ctx.takeScreenshot("carton_amp_06_bioma_marcado");
		paso("Logro de bioma: llegar a " + bioma + " en " + destino.get().toShortString() + " marcó la casilla");

		// Cantidad: 10 de piedra labrada entre Player0 y Bot1.
		sp.getServer().runCommand("give Player0 minecraft:cobblestone 4");
		sp.getServer().runCommand("give Bot1 minecraft:cobblestone 3");
		ctx.waitTicks(40);
		int[] progreso = sp.getServer().computeOnServer(s -> {
			Carton.Casilla c = carton().casillas.get(iCantidad);
			return new int[]{c.logros.containsKey(ColorEquipo.ROJO) ? 1 : 0, c.progreso.getOrDefault(ColorEquipo.ROJO, -1)};
		});
		comprobar(progreso[0] == 0, "con 7 de 10 la casilla no se marca");
		comprobar(progreso[1] == 7, "el progreso de Rojo es 7 (es " + progreso[1] + ")");
		ctx.takeScreenshot("carton_amp_07_hud_progreso");
		ctx.setScreen(PantallaCarton::new);
		ctx.waitTicks(20);
		int[] celda = ctx.computeOnClient(mc -> ((PantallaCarton) mc.gui.screen()).centroDe(iCantidad));
		apuntar(ctx, celda[0], celda[1]);
		ctx.waitTicks(5);
		ctx.takeScreenshot("carton_amp_08_pantalla_b_progreso");
		ctx.getInput().setCursorPos(0, 0);
		ctx.setScreen(() -> null);
		paso("Cantidad: 4 de Player0 + 3 de Bot1 = 7 de 10, sin marcar; barra en el HUD y en la pantalla B");

		sp.getServer().runCommand("give Bot1 minecraft:cobblestone 3");
		sp.getServer().waitFor(s -> rojo(iCantidad), 100);
		String quien = sp.getServer().computeOnServer(s -> {
			var uuid = carton().casillas.get(iCantidad).jugadores.get(ColorEquipo.ROJO);
			ServerPlayer p = s.getPlayerList().getPlayer(uuid);
			return p == null ? "?" : p.getGameProfile().name();
		});
		comprobar(quien.equals("Bot1"), "la casilla se anota a quien aportó más (Bot1, fue " + quien + ")");
		ctx.waitTicks(20);
		ctx.takeScreenshot("carton_amp_09_hud_marcadas");
		paso("Cantidad: con 3 más de Bot1 (10 de 10) se marcó para Rojo, anotada a Bot1");

		// Final: la línea de tiempo muestra los logros y la cantidad con su nombre.
		sp.getServer().runCommand("venatio terminar");
		ctx.waitForScreen(PantallaFinal.class);
		ctx.waitTicks(60);
		ctx.takeScreenshot("carton_amp_10_final");
		paso("Pantalla final con los logros y la cantidad en la línea de tiempo");
		sp.getServer().waitFor(s -> Juego.INSTANCIA.partida() == null, 20 * 30);
		sp.getServer().runCommand("player Bot1 kill");
	}

	/**
	 * Un logro de bioma de la lista que no sea el del spawn, con un punto en superficie de ese bioma a menos de 2,000 bloques.
	 * Busca como /locate biome (muestreo del generador) y confirma generando el chunk.
	 */
	private static String elegirBioma(ServerPlayer p, AtomicReference<BlockPos> destino) {
		ServerLevel nivel = p.level();
		for (String id : BIOMAS_CANDIDATOS) {
			Reto r = Retos.porId(id);
			Predicate<Holder<Biome>> enGrupo = b -> r.objetivos().stream().anyMatch(b::is);
			if (enGrupo.test(nivel.getBiome(p.blockPosition()))) continue;
			int intentos = 0;
			for (int radio = 64; radio <= 2048 && intentos < 4; radio += 64) {
				for (int k = -radio; k < radio && intentos < 4; k += 64) {
					for (int[] d : new int[][]{{k, -radio}, {radio, k}, {-k, radio}, {-radio, -k}}) {
						int x = p.getBlockX() + d[0], z = p.getBlockZ() + d[1];
						if (!enGrupo.test(nivel.getUncachedNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(64), QuartPos.fromBlock(z)))) continue;
						intentos++;
						nivel.getChunk(x >> 4, z >> 4);
						int y = nivel.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
						if (enGrupo.test(nivel.getBiome(new BlockPos(x, y, z)))) {
							destino.set(new BlockPos(x, y, z));
							return id;
						}
						if (intentos >= 4) break;
					}
				}
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- Rastreo

	/** La secuencia del Rastreo con logros y cantidades: ~28 % logros y ~30 % de los items con cantidad. */
	private static void rastreo(TestSingleplayerContext sp) {
		int[] n = sp.getServer().computeOnServer(s -> {
			Pool pool = new Reglas(Dificultad.NORMAL, true, true).paraRastreo(Juego.INSTANCIA.poolDePartida(), 77L);
			int retos = 0, items = 0, cantidad = 0;
			for (String e : pool.entradas()) {
				Objetivo o = Objetivo.de(e);
				if (o.esReto()) retos++;
				else {
					items++;
					if (o.cantidad() > 1) cantidad++;
				}
			}
			return new int[]{retos, items, cantidad};
		});
		int total = n[0] + n[1];
		comprobar(n[0] * 100 / total >= 22 && n[0] * 100 / total <= 34, "Rastreo: " + n[0] + " logros de " + total);
		comprobar(n[2] * 100 / n[1] >= 20 && n[2] * 100 / n[1] <= 40, "Rastreo: " + n[2] + " items con cantidad de " + n[1]);
		paso("Rastreo con logros y cantidades: " + n[0] + " logros y " + n[2] + " con cantidad en una secuencia de " + total);
	}

	// ---------------------------------------------------------------- utilidades

	private static Carton carton() {
		return Juego.INSTANCIA.partida().carton();
	}

	private static boolean rojo(int indice) {
		Partida p = Juego.INSTANCIA.partida();
		return p != null && p.carton().casillas.get(indice).logros.containsKey(ColorEquipo.ROJO);
	}

	private static Partida.Fase fase() {
		Partida p = Juego.INSTANCIA.partida();
		return p == null ? null : p.fase();
	}

	private static ServerPlayer bot(MinecraftServer s, String nombre) {
		return s.getPlayerList().getPlayerByName(nombre);
	}

	private static ServerPlayer jugador(MinecraftServer s) {
		return bot(s, "Player0");
	}

	private static String leer(Path archivo) {
		try {
			return Files.exists(archivo) ? Files.readString(archivo) : "";
		} catch (Exception e) {
			return "";
		}
	}

	@SuppressWarnings("unchecked")
	private static <T extends Screen> Predicate<Minecraft> pantalla(Class<T> tipo, Predicate<T> condicion) {
		return mc -> tipo.isInstance(mc.gui.screen()) && condicion.test((T) mc.gui.screen());
	}

	/** Mueve el ratón a coordenadas de interfaz. */
	private static void apuntar(ClientGameTestContext ctx, int x, int y) {
		double escala = ctx.computeOnClient(mc -> (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth());
		ctx.getInput().setCursorPos(x * escala + escala / 2, y * escala + escala / 2);
	}

	/** Clic (0 izquierdo, 1 derecho) en coordenadas de interfaz. */
	private static void clic(ClientGameTestContext ctx, int x, int y, int boton) {
		apuntar(ctx, x, y);
		ctx.waitTick();
		ctx.getInput().pressMouse(boton);
		ctx.waitTicks(2);
	}

	private static void idioma(ClientGameTestContext ctx, String codigo) {
		CompletableFuture<Void> recarga = ctx.computeOnClient(mc -> {
			mc.options.languageCode = codigo;
			mc.getLanguageManager().setSelected(codigo);
			return mc.reloadResourcePacks();
		});
		ctx.waitFor(mc -> recarga.isDone() && mc.gui.overlay() == null, 20 * 60);
	}

	private static void comprobar(boolean condicion, String que) {
		if (!condicion) throw new AssertionError("Falló: " + que);
	}

	private static void paso(String texto) {
		Venatio.LOG.info("PRUEBA CARTÓN ✔ {}", texto);
	}
}
