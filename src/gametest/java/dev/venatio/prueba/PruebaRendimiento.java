package dev.venatio.prueba;

import dev.venatio.Venatio;
import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Partida;
import dev.venatio.mundo.Lobby;
import dev.venatio.mundo.MundosPartida;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Medición del servidor con una partida de verdad: Player0 (cliente real, quieto en su punto de aparición, con vista
 * corta para no frenar al servidor) y N bots de Carpet que se alejan cada uno en una dirección distinta a velocidad fija,
 * como amigos explorando a pie o con élitro; igual que el cliente real, no avanzan hacia un chunk que todavía no existe,
 * así que la velocidad lograda dice qué tan rápido se puede explorar.
 * Cada partida genera mundos nuevos (con la misma semilla en todos los escenarios, para comparar), así que casi todo el
 * costo es generar chunks. Cada 10 s registra MSPT media, p95 y máximo, chunks, chunks faltantes y memoria; al final de
 * cada escenario, un resumen en una línea y una fila en build/run/clientGameTest/rendimiento/resultados.csv.
 *
 * <p>Sin propiedades es una corrida corta (un escenario de 30 s) para que la lista completa de pruebas la ejerza. Las
 * mediciones de docs/RENDIMIENTO.md se hacen con propiedades del sistema, que Gradle pasa con -Prend…:
 * <pre>
 *   ORG_GRADLE_PROJECT_mods=a ORG_GRADLE_PROJECT_nucleos=3 ORG_GRADLE_PROJECT_memoria=6G \
 *   ORG_GRADLE_PROJECT_rendVistas=10,12,16 ORG_GRADLE_PROJECT_rendVelocidades=6,25 ORG_GRADLE_PROJECT_rendSegundos=120 \
 *   tools/prueba.sh PruebaRendimiento
 * </pre>
 * Propiedades (venatio.rend.*): vistas, simulacion (lista; "vista" = igual que la vista), velocidades (bloques/s),
 * segundos, bots, equipos, pregen (lista de radios; vacío = el de Venatio), semilla, vistaCliente, lobby (segundos en el
 * lobby antes de cada partida), precalentar (true/false: -Dvenatio.precalentar), calentar (segundos de una partida
 * previa sin medir, para el JIT), dedicado (true: servidor dedicado con server.properties; false: un jugador),
 * chunksPorTick (lo que "acusa" cada bot por tick, como un cliente real), guardar (medir un autoguardado a mitad),
 * sync, compresion y region (sync-chunk-writes, network-compression-threshold y region-file-compression del servidor).
 */
public final class PruebaRendimiento implements FabricClientGameTest {
	private static final Path RESULTADOS = Path.of("rendimiento", "resultados.csv");
	private static final String[] MODS_INTERESANTES = {"lithium", "ferritecore", "krypton", "scalablelux", "c2me", "moonrise",
		"vmp", "servercore", "chunky", "distanthorizons", "spark", "carpet", "fantasy"};

	private record Escenario(int vista, int simulacion, double velocidad, Integer pregen) {
		String etiqueta() {
			return "vista " + vista + " · sim " + simulacion + " · " + Math.round(velocidad) + " b/s"
				+ (pregen == null ? "" : " · pregen " + pregen);
		}
	}

	// ---- parámetros
	private final String conjunto = System.getProperty("venatio.rend.conjunto", "base");
	private final List<Integer> vistas = enteros("vistas", "10");
	private final List<String> simulaciones = textos("simulacion", "6");
	private final List<Double> velocidades = textos("velocidades", "6").stream().map(Double::parseDouble).toList();
	private final List<Integer> pregens = enteros("pregen", "");
	private final int segundos = Integer.getInteger("venatio.rend.segundos", 30);
	private final int bots = Integer.getInteger("venatio.rend.bots", 5);
	private final int equipos = Integer.getInteger("venatio.rend.equipos", 3);
	private final long semilla = Long.getLong("venatio.rend.semilla", 20261120L);
	private final int calentar = Integer.getInteger("venatio.rend.calentar", 0);
	private final boolean dedicado = Boolean.parseBoolean(System.getProperty("venatio.rend.dedicado", "true"));
	private final float chunksPorTick = Float.parseFloat(System.getProperty("venatio.rend.chunksPorTick", "16"));
	private final boolean guardar = Boolean.parseBoolean(System.getProperty("venatio.rend.guardar", "true"));
	/**
	 * Distancia de renderizado del cliente de la prueba. Es baja a propósito: el cliente comparte la JVM y avanza tick a
	 * tick a la par del servidor, así que un cliente cargado (vista 16) frena al servidor y ensucia la medición. Los bots
	 * sí piden la vista completa y reciben todos los chunks.
	 */
	private final int vistaCliente = Integer.getInteger("venatio.rend.vistaCliente", 2);
	/** Segundos en el lobby antes de cada partida (medidos aparte); ahí Precalentado genera la siguiente. */
	private final int lobby = Integer.getInteger("venatio.rend.lobby", 0);

	/** Estado del movimiento de cada bot (solo se toca en el hilo del servidor). */
	private static final class Rumbo {
		double x, y, z;
		/** Bloques que avanzó de verdad (se queda quieto mientras el chunk de enfrente no existe). */
		double recorrido;
		final double angulo;

		Rumbo(double angulo) {
			this.angulo = angulo;
		}
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		String precalentar = System.getProperty("venatio.rend.precalentar");
		if (precalentar != null) System.setProperty("venatio.precalentar", precalentar);
		List<Escenario> escenarios = escenarios();
		MedidorRendimiento.LOG.info("RENDIMIENTO conjunto={} · mods: {} · procesadores {} · memoria máx {} MB · {} escenario(s) de {} s · {} bots en {} equipos · {} · JVM {}",
			conjunto, modsCargados(), Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() >> 20,
			escenarios.size(), segundos, bots, equipos, dedicado ? "servidor dedicado" : "un jugador", banderasJvm()
				+ " · lobby " + lobby + " s · precalentar " + System.getProperty("venatio.precalentar", "true"));
		Escenario primero = escenarios.get(0);
		if (dedicado) {
			Properties props = new Properties();
			props.setProperty("view-distance", String.valueOf(primero.vista()));
			props.setProperty("simulation-distance", String.valueOf(primero.simulacion()));
			props.setProperty("max-players", "20");
			props.setProperty("allow-flight", "true");
			props.setProperty("sync-chunk-writes", System.getProperty("venatio.rend.sync", "false"));
			props.setProperty("network-compression-threshold", System.getProperty("venatio.rend.compresion", "256"));
			props.setProperty("region-file-compression", System.getProperty("venatio.rend.region", "deflate"));
			try (TestDedicatedServerContext server = ctx.worldBuilder().createServer(props);
				 TestDedicatedServerConnection ignored = server.connect()) {
				correr(ctx, server, escenarios);
			}
		} else {
			try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
				correr(ctx, sp.getServer(), escenarios);
			}
		}
	}

	private void correr(ClientGameTestContext ctx, TestServerContext server, List<Escenario> escenarios) {
		ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 1200);
		ctx.runOnClient(mc -> {
			mc.options.framerateLimit().set(30); // el cliente comparte la JVM: que no robe CPU
			// Sin esto, tras 10 min sin teclado ni ratón el cliente baja a 10 FPS y, como avanza a la par del servidor en
			// las pruebas, deja al servidor en 10 TPS (FramerateLimitTracker, LONG_AFK).
			mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
		});
		prepararLobby(ctx, server);
		if (calentar > 0) {
			Escenario c = escenarios.get(0);
			MedidorRendimiento.LOG.info("RENDIMIENTO calentando la JVM con una partida de {} s (no se registra)", calentar);
			medir(ctx, server, c, calentar, false);
		}
		for (Escenario e : escenarios) medir(ctx, server, e, segundos, true);
		for (int i = 1; i <= bots; i++) server.runCommand("player Bot" + i + " kill");
	}

	private void prepararLobby(ClientGameTestContext ctx, TestServerContext server) {
		for (int i = 1; i <= bots; i++) server.runCommand("player Bot" + i + " spawn");
		server.waitFor(s -> {
			for (int i = 1; i <= bots; i++) {
				ServerPlayer b = s.getPlayerList().getPlayerByName("Bot" + i);
				if (b == null || !Lobby.esLobby(b.level())) return false;
			}
			return true;
		}, 600);
		// Equipos parejos: Player0 y los bots en orden, repartidos en `equipos` colores
		int jugadores = bots + 1;
		for (int k = 0; k < jugadores; k++) {
			String nombre = k == 0 ? "Player0" : "Bot" + k;
			ColorEquipo color = ColorEquipo.values()[k * equipos / jugadores];
			server.runCommand("execute as " + nombre + " run venatio equipo " + color.id());
		}
		server.runCommand("venatio modo linea");
		server.runCommand("venatio tiempo 0");
		server.runCommand("venatio semilla " + semilla);
		// Cartón con logros y cantidades: la detección hace todo su trabajo (estadísticas, sumas por equipo)
		server.runCommand("venatio opcion logros si");
		server.runCommand("venatio opcion cantidades si");
		server.runCommand("venatio opcion eventos no");
		ctx.waitTicks(20);
		paso("Lobby con Player0 y " + bots + " bots en " + equipos + " equipos");
	}

	// ---------------------------------------------------------------- un escenario

	private void medir(ClientGameTestContext ctx, TestServerContext server, Escenario e, int duracion, boolean registrar) {
		server.runOnServer(s -> {
			s.getPlayerList().setViewDistance(e.vista());
			s.getPlayerList().setSimulationDistance(e.simulacion());
		});
		ctx.runOnClient(mc -> {
			mc.options.renderDistance().set(Math.min(vistaCliente, e.vista()));
			mc.options.broadcastOptions();
		});
		if (e.pregen() != null) System.setProperty("venatio.pregen", String.valueOf(e.pregen()));
		else System.clearProperty("venatio.pregen");
		String etiqueta = conjunto + " · " + e.etiqueta();
		if (lobby > 0) {
			// Un rato en el lobby antes de iniciar, como entre partidas de verdad (ahí trabaja Precalentado).
			MedidorRendimiento enLobby = server.computeOnServer(s -> MedidorRendimiento.empezar(etiqueta + " · lobby"));
			long l0 = System.nanoTime();
			while ((System.nanoTime() - l0) / 1e9 < lobby) ctx.waitTicks(20);
			server.runOnServer(s -> enLobby.terminar());
			if (registrar) MedidorRendimiento.LOG.info("RESUMEN [{} · lobby {} s] {}", etiqueta, lobby, enLobby.resumen());
		}

		long t0 = System.nanoTime();
		server.runCommand("venatio iniciar");
		server.waitFor(s -> fase() == Partida.Fase.CUENTA_ATRAS || fase() == Partida.Fase.JUGANDO, 20 * 300);
		double preparacion = (System.nanoTime() - t0) / 1e9;
		server.waitFor(s -> fase() == Partida.Fase.JUGANDO, 20 * 60);
		int faltantesInicio = server.computeOnServer(MedidorRendimiento::faltantes);

		// Bots: requieren la vista completa (como un cliente con esa distancia de renderizado), no mueren, llevan un
		// inventario lleno (la detección recorre todo) y salen en direcciones distintas desde su punto de aparición.
		Map<String, Rumbo> rumbos = server.computeOnServer(s -> prepararBots(s, e));
		MedidorRendimiento medidor = server.computeOnServer(s -> MedidorRendimiento.empezar(etiqueta));
		server.runOnServer(s -> MedidorRendimiento.alTick = () -> moverBots(s, rumbos, e.velocidad()));

		long inicio = System.nanoTime();
		double autoguardado = -1;
		while ((System.nanoTime() - inicio) / 1e9 < duracion) {
			ctx.waitTicks(20);
			if (registrar && guardar && autoguardado < 0 && (System.nanoTime() - inicio) / 1e9 >= duracion / 2.0) {
				// Lo mismo que el autoguardado de cada 5 min (MinecraftServer.autoSave); fuera del tick medido.
				autoguardado = server.computeOnServer(s -> {
					long a = System.nanoTime();
					s.saveEverything(true, false, false);
					return (System.nanoTime() - a) / 1e6;
				});
				MedidorRendimiento.LOG.info("RENDIMIENTO [{}] autoguardado: {} ms", etiqueta, String.format(Locale.ROOT, "%.1f", autoguardado));
			}
		}
		server.runOnServer(s -> MedidorRendimiento.alTick = null);
		server.runOnServer(s -> medidor.terminar());
		double segundosMovidos = (System.nanoTime() - inicio) / 1e9;
		double lograda = server.computeOnServer(s -> rumbos.values().stream().mapToDouble(r -> r.recorrido).average().orElse(0)) / segundosMovidos;
		int chunks = server.computeOnServer(MedidorRendimiento::chunksCargados);
		long memoriaConMundos = memoriaTrasGc(server); // lo que de verdad ocupan los chunks cargados

		server.runCommand("venatio terminar");
		server.waitFor(s -> Juego.INSTANCIA.partida() == null, 20 * 60);
		ctx.waitTicks(40);
		long memoriaTrasGc = memoriaTrasGc(server);
		if (!registrar) return;

		String resumen = medidor.resumen();
		MedidorRendimiento.LOG.info("RESUMEN [{}] preparación {} s · faltantes al empezar {} · velocidad lograda {} de {} b/s · {} · chunks al final {} · memoria tras GC con los mundos {} MB y tras borrarlos {} MB · autoguardado {} ms",
			etiqueta, String.format(Locale.ROOT, "%.1f", preparacion), faltantesInicio, f(lograda), f(e.velocidad()), resumen, chunks,
			memoriaConMundos, memoriaTrasGc, String.format(Locale.ROOT, "%.1f", autoguardado));
		anotar(e, preparacion, faltantesInicio, lograda, medidor, chunks, memoriaConMundos, memoriaTrasGc, autoguardado);
		paso("Escenario medido: " + etiqueta);
	}

	private Map<String, Rumbo> prepararBots(MinecraftServer s, Escenario e) {
		Map<String, Rumbo> rumbos = new HashMap<>();
		List<Item> items = BuiltInRegistries.ITEM.stream().filter(i -> i.getDefaultMaxStackSize() > 1).toList();
		Random rnd = new Random(semilla);
		for (int i = 1; i <= bots; i++) {
			ServerPlayer b = s.getPlayerList().getPlayerByName("Bot" + i);
			if (b == null) continue;
			ClientInformation ci = b.clientInformation();
			b.updateOptions(new ClientInformation(ci.language(), e.vista(), ci.chatVisibility(), ci.chatColors(),
				ci.modelCustomisation(), ci.mainHand(), ci.textFilteringEnabled(), ci.allowsListing(), ci.particleStatus()));
			b.getAbilities().invulnerable = true;
			b.onUpdateAbilities();
			b.setNoGravity(true);
			b.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, MobEffectInstance.INFINITE_DURATION, 4, false, false));
			b.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, MobEffectInstance.INFINITE_DURATION, 0, false, false));
			b.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, MobEffectInstance.INFINITE_DURATION, 0, false, false));
			for (int k = 0; k < 30; k++) b.getInventory().add(new ItemStack(items.get(rnd.nextInt(items.size())), 32));
			Rumbo r = new Rumbo(2 * Math.PI * (i - 1) / bots + 0.3);
			r.x = b.getX();
			r.y = b.getY();
			r.z = b.getZ();
			rumbos.put(b.getGameProfile().name(), r);
		}
		return rumbos;
	}

	/**
	 * Corre al principio de cada tick (dentro del tick medido, como los paquetes de movimiento de un jugador real). Como
	 * el cliente de verdad, que no deja avanzar hacia un chunk que todavía no tiene, el bot espera si el chunk de enfrente
	 * no está cargado: así la velocidad lograda dice qué tan rápido se puede explorar de verdad.
	 */
	private void moverBots(MinecraftServer s, Map<String, Rumbo> rumbos, double velocidad) {
		double paso = velocidad / 20.0;
		boolean volando = velocidad > 12;
		for (Map.Entry<String, Rumbo> entrada : rumbos.entrySet()) {
			ServerPlayer b = s.getPlayerList().getPlayerByName(entrada.getKey());
			if (b == null || !MundosPartida.esDePartida(b.level())) continue;
			// Un cliente real confirma los lotes de chunks que recibe; sin esto el servidor deja de mandarle al bot.
			b.connection.chunkSender.onChunkBatchReceivedByClient(chunksPorTick);
			Rumbo r = entrada.getValue();
			double nx = r.x + Math.cos(r.angulo) * paso, nz = r.z + Math.sin(r.angulo) * paso;
			ServerLevel nivel = (ServerLevel) b.level();
			int bx = (int) Math.floor(nx), bz = (int) Math.floor(nz);
			if (nivel.getChunkSource().getChunkNow(bx >> 4, bz >> 4) == null) continue;
			r.x = nx;
			r.z = nz;
			r.recorrido += paso;
			r.y = nivel.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz) + (volando ? 30 : 0);
			float yaw = (float) Math.toDegrees(r.angulo) - 90f;
			b.teleportTo(nivel, r.x, r.y, r.z, Set.of(), yaw, 0f, false);
		}
	}

	// ---------------------------------------------------------------- resultados

	private static long memoriaTrasGc(TestServerContext server) {
		return server.computeOnServer(s -> {
			System.gc();
			Runtime rt = Runtime.getRuntime();
			return (rt.totalMemory() - rt.freeMemory()) >> 20;
		});
	}

	private void anotar(Escenario e, double preparacion, int faltantesInicio, double lograda, MedidorRendimiento m, int chunks, long memoriaConMundos,
						long memoriaTrasGc, double autoguardado) {
		try {
			Files.createDirectories(RESULTADOS.getParent());
			boolean nuevo = !Files.exists(RESULTADOS);
			StringBuilder sb = new StringBuilder();
			if (nuevo) {
				sb.append("fecha,conjunto,procesadores,dedicado,vista,simulacion,velocidad,velocidad_lograda,bots,equipos,pregen,segundos,preparacion_s,"
					+ "faltantes_inicio,mspt_media,mspt_p95,mspt_max,peor_p95_10s,tps,venatio_ms,faltantes_media,faltantes_max,hueco_pct,"
					+ "faltantes_final,generados,generados_por_s,chunks_final,memoria_max_mb,memoria_con_mundos_mb,memoria_tras_gc_mb,autoguardado_ms,cpu_hilo_pct,cpu_proceso_nucleos,jvm,mods\n");
			}
			double peorP95 = m.ventanas.stream().mapToDouble(MedidorRendimiento.Ventana::p95).max().orElse(0);
			double seg = m.ventanas.isEmpty() ? 0 : m.ventanas.get(m.ventanas.size() - 1).segundo();
			sb.append(String.join(",",
				LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), conjunto,
				String.valueOf(Runtime.getRuntime().availableProcessors()), String.valueOf(dedicado), String.valueOf(e.vista()),
				String.valueOf(e.simulacion()), f(e.velocidad()), f(lograda), String.valueOf(bots), String.valueOf(equipos),
				e.pregen() == null ? "" : String.valueOf(e.pregen()), String.valueOf(segundos), f(preparacion),
				String.valueOf(faltantesInicio), f(m.mediaTotal()), f(m.p95Total()), f(m.maxTotal()), f(peorP95), f(m.tps()),
				String.format(Locale.ROOT, "%.3f", m.venatioMedia()), f(m.faltantesMedia()), String.valueOf(m.faltantesMax()), f(m.huecoPct()),
				String.valueOf(m.faltantesAlFinal()), String.valueOf(m.generados()), f(seg == 0 ? 0 : m.generados() / seg),
				String.valueOf(chunks), String.valueOf(m.memoriaMax()), String.valueOf(memoriaConMundos), String.valueOf(memoriaTrasGc), f(autoguardado),
				f(m.cpuServidor()), f(m.cpuProcesoMedio()), "\"" + banderasJvm() + "\"", "\"" + modsCargados() + "\"")).append('\n');
			Files.writeString(RESULTADOS, sb, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException ex) {
			MedidorRendimiento.LOG.warn("No se pudo escribir {}", RESULTADOS, ex);
		}
	}

	/** Las banderas -X/-XX con que corre la JVM (núcleos, memoria, GC). */
	private static String banderasJvm() {
		return java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
			.filter(a -> a.startsWith("-XX:") || a.startsWith("-Xm")).filter(a -> !a.contains("FlightRecording"))
			.collect(Collectors.joining(" "));
	}

	private static String f(double v) {
		return String.format(Locale.ROOT, "%.1f", v);
	}

	private static String modsCargados() {
		List<String> lista = new ArrayList<>();
		for (String id : MODS_INTERESANTES) {
			FabricLoader.getInstance().getModContainer(id).map(ModContainer::getMetadata)
				.ifPresent(m -> lista.add(id + " " + m.getVersion().getFriendlyString()));
		}
		return String.join("; ", lista);
	}

	// ---------------------------------------------------------------- parámetros

	private List<Escenario> escenarios() {
		List<Escenario> lista = new ArrayList<>();
		List<Integer> radios = pregens.isEmpty() ? java.util.Collections.singletonList(null) : pregens;
		for (Integer radio : radios) {
			for (int vista : vistas) {
				for (String sim : simulaciones) {
					int simulacion = sim.equals("vista") ? vista : Math.min(vista, Integer.parseInt(sim));
					for (double v : velocidades) lista.add(new Escenario(vista, simulacion, v, radio));
				}
			}
		}
		return lista;
	}

	private static List<String> textos(String nombre, String porDefecto) {
		String v = System.getProperty("venatio.rend." + nombre, porDefecto).trim();
		if (v.isEmpty()) return List.of();
		return java.util.Arrays.stream(v.split(",")).map(String::trim).filter(x -> !x.isEmpty()).collect(Collectors.toList());
	}

	private static List<Integer> enteros(String nombre, String porDefecto) {
		return textos(nombre, porDefecto).stream().map(Integer::parseInt).toList();
	}

	private static Partida.Fase fase() {
		Partida p = Juego.INSTANCIA.partida();
		return p == null ? null : p.fase();
	}

	private static void paso(String texto) {
		Venatio.LOG.info("PRUEBA RENDIMIENTO ✔ {}", texto);
	}
}
