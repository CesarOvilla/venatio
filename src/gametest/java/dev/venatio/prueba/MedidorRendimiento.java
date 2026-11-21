package dev.venatio.prueba;

import dev.venatio.juego.Juego;
import dev.venatio.juego.Partida;
import dev.venatio.mundo.Lobby;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Mide el servidor mientras se juega una partida (PruebaRendimiento). Se engancha una sola vez a los eventos de tick y se
 * enciende por escenario. Todo corre en el hilo del servidor.
 * <ul>
 * <li>MSPT: lo que dura tickServer completo, desde el primer oyente de START_SERVER_TICK hasta el último de END_SERVER_TICK
 * (incluye mundos, red, autoguardado y los oyentes de Venatio).</li>
 * <li>Venatio: lo que duran los oyentes de END_SERVER_TICK en la fase por defecto (son los de Venatio: Juego, Localizador,
 * Ayudas, ModuloStats).</li>
 * <li>Faltantes: chunks dentro de la distancia de visión de algún participante que todavía no están cargados (lo que el
 * jugador ve como hueco). Se muestrea una vez por segundo.</li>
 * <li>CPU: del hilo del servidor (% de un núcleo; cerca de 100 = el hilo principal no da más) y de todo el proceso (en
 * núcleos; incluye el cliente de la prueba, que se mantiene liviano). Si los TPS bajan sin que el hilo del servidor
 * esté al 100 %, el freno es otro (por ejemplo el cliente, que avanza a la par del servidor en estas pruebas).</li>
 * </ul>
 */
final class MedidorRendimiento {
	static final Logger LOG = LoggerFactory.getLogger("venatio-rendimiento");
	private static final Identifier ANTES = Identifier.fromNamespaceAndPath("venatio_pruebas", "medir_antes");
	private static final Identifier DESPUES = Identifier.fromNamespaceAndPath("venatio_pruebas", "medir_despues");
	private static final long VENTANA_NS = 10_000_000_000L;

	private static boolean enganchado;
	/** El medidor encendido; null = no se mide. */
	private static MedidorRendimiento activo;
	/** Lo que la prueba quiere correr al principio de cada tick (mover bots); va dentro del tick medido. */
	static Runnable alTick;

	/** Una ventana de 10 s ya cerrada. */
	record Ventana(double segundo, int ticks, double media, double p95, double max, double venatioMedia, double venatioMax,
				   int chunks, int faltantesMedia, int faltantesMax, int generados, long memoriaMb, double cpuServidor, double cpuProceso) {
		String linea() {
			return String.format(Locale.ROOT, "t=%3.0fs · ticks %3d · MSPT media %5.1f p95 %5.1f máx %6.1f · Venatio %.2f/%.1f ms"
					+ " · chunks %5d · faltantes %4d (máx %4d) · generados %4d · memoria %5d MB · CPU hilo %3.0f%% · proceso %.1f núcleos",
				segundo, ticks, media, p95, max, venatioMedia, venatioMax, chunks, faltantesMedia, faltantesMax, generados, memoriaMb,
				cpuServidor, cpuProceso);
		}
	}

	private final String etiqueta;
	private final long inicio = System.nanoTime();
	private long inicioVentana = inicio;
	private long inicioTick, inicioVenatio;
	private final LongArrayList tiemposVentana = new LongArrayList();
	private final LongArrayList venatioVentana = new LongArrayList();
	private final LongArrayList tiemposTotal = new LongArrayList();
	private final LongArrayList venatioTotal = new LongArrayList();
	private final List<Integer> faltantesVentana = new ArrayList<>();
	/** Por muestra, qué parte de la vista de los jugadores falta (0 a 1). */
	private final List<Double> huecoVentana = new ArrayList<>();
	private final List<Double> huecoTotal = new ArrayList<>();
	private int generadosVentana, generadosTotal, ticksDesdeMuestra;
	private long memoriaMaxVentana, memoriaMax;
	final List<Ventana> ventanas = new ArrayList<>();
	private int ultimoFaltantes = -1;
	private static final ThreadMXBean HILOS = ManagementFactory.getThreadMXBean();
	private long cpuHiloInicio = -1, cpuProcesoInicio = cpuProceso();

	private MedidorRendimiento(String etiqueta) {
		this.etiqueta = etiqueta;
	}

	/** Engancha los oyentes (una vez por JVM) y empieza a medir. */
	static MedidorRendimiento empezar(String etiqueta) {
		enganchar();
		activo = new MedidorRendimiento(etiqueta);
		return activo;
	}

	MedidorRendimiento terminar() {
		if (activo == this) activo = null;
		return this;
	}

	private static void enganchar() {
		if (enganchado) return;
		enganchado = true;
		ServerTickEvents.START_SERVER_TICK.addPhaseOrdering(ANTES, Event.DEFAULT_PHASE);
		ServerTickEvents.END_SERVER_TICK.addPhaseOrdering(ANTES, Event.DEFAULT_PHASE);
		ServerTickEvents.END_SERVER_TICK.addPhaseOrdering(Event.DEFAULT_PHASE, DESPUES);
		ServerTickEvents.START_SERVER_TICK.register(ANTES, s -> {
			if (activo != null) activo.inicioTick = System.nanoTime();
			Runnable r = alTick;
			if (r != null) r.run();
		});
		ServerTickEvents.END_SERVER_TICK.register(ANTES, s -> {
			if (activo != null) activo.inicioVenatio = System.nanoTime();
		});
		ServerTickEvents.END_SERVER_TICK.register(DESPUES, s -> {
			if (activo != null) activo.finTick(s);
		});
		ServerChunkEvents.CHUNK_GENERATE.register((nivel, chunk) -> {
			// Mundos de la partida, y también los que Precalentado prepara en el lobby
			if (activo != null && !Lobby.esLobby(nivel) && nivel != nivel.getServer().overworld()) {
				activo.generadosVentana++;
				activo.generadosTotal++;
			}
		});
	}

	private void finTick(MinecraftServer server) {
		long ahora = System.nanoTime();
		if (inicioTick == 0) return; // se encendió a mitad de un tick
		if (cpuHiloInicio < 0) cpuHiloInicio = HILOS.getCurrentThreadCpuTime();
		tiemposVentana.add(ahora - inicioTick);
		venatioVentana.add(ahora - inicioVenatio);
		// Lo de abajo ya no cuenta en el tick medido
		if (++ticksDesdeMuestra >= 20) {
			ticksDesdeMuestra = 0;
			int[] fv = faltantesYVista(server);
			ultimoFaltantes = fv[0];
			faltantesVentana.add(ultimoFaltantes);
			if (fv[1] > 0) huecoVentana.add((double) fv[0] / fv[1]);
			Runtime rt = Runtime.getRuntime();
			long usada = (rt.totalMemory() - rt.freeMemory()) >> 20;
			memoriaMaxVentana = Math.max(memoriaMaxVentana, usada);
		}
		if (ahora - inicioVentana >= VENTANA_NS) cerrarVentana(server, ahora);
	}

	private void cerrarVentana(MinecraftServer server, long ahora) {
		long[] t = tiemposVentana.toLongArray();
		long[] v = venatioVentana.toLongArray();
		tiemposTotal.addAll(tiemposVentana);
		venatioTotal.addAll(venatioVentana);
		int fMax = faltantesVentana.stream().mapToInt(Integer::intValue).max().orElse(0);
		int fMedia = (int) Math.round(faltantesVentana.stream().mapToInt(Integer::intValue).average().orElse(0));
		long cpuHilo = HILOS.getCurrentThreadCpuTime(), cpuProc = cpuProceso();
		double duracion = (ahora - inicioVentana) / 1e9;
		Ventana w = new Ventana((ahora - inicio) / 1e9, t.length, media(t), percentil(t, 0.95), max(t), media(v), max(v),
			chunksCargados(server), fMedia, fMax, generadosVentana, memoriaMaxVentana,
			(cpuHilo - cpuHiloInicio) / 1e9 / duracion * 100, (cpuProc - cpuProcesoInicio) / 1e9 / duracion);
		cpuHiloInicio = cpuHilo;
		cpuProcesoInicio = cpuProc;
		ventanas.add(w);
		LOG.info("RENDIMIENTO [{}] {}", etiqueta, w.linea());
		memoriaMax = Math.max(memoriaMax, memoriaMaxVentana);
		tiemposVentana.clear();
		venatioVentana.clear();
		faltantesVentana.clear();
		huecoTotal.addAll(huecoVentana);
		huecoVentana.clear();
		generadosVentana = 0;
		memoriaMaxVentana = 0;
		inicioVentana = ahora;
	}

	// ---------------------------------------------------------------- resumen

	/** Resumen de todo lo medido (las ventanas cerradas). */
	String resumen() {
		long[] t = tiemposTotal.toLongArray();
		long[] v = venatioTotal.toLongArray();
		double p95Peor = ventanas.stream().mapToDouble(Ventana::p95).max().orElse(0);
		int faltMax = ventanas.stream().mapToInt(Ventana::faltantesMax).max().orElse(0);
		double faltMedia = ventanas.stream().mapToInt(Ventana::faltantesMedia).average().orElse(0);
		double segundos = ventanas.isEmpty() ? 0 : ventanas.get(ventanas.size() - 1).segundo();
		double tps = segundos == 0 ? 0 : t.length / segundos;
		long lentos = Arrays.stream(t).filter(x -> x > 50_000_000L).count();
		return String.format(Locale.ROOT,
			"MSPT media %.1f p95 %.1f máx %.1f (peor p95 de 10 s %.1f) · ticks >50 ms %d de %d · TPS %.1f · Venatio media %.3f máx %.1f ms"
				+ " · faltantes media %.0f máx %d (falta el %.0f %% de la vista) · generados %d (%.0f/s) · memoria máx %d MB · CPU hilo %.0f%% · proceso %.1f núcleos",
			media(t), percentil(t, 0.95), max(t), p95Peor, lentos, t.length, tps, media(v), max(v), faltMedia, faltMax, huecoPct(),
			generadosTotal, segundos == 0 ? 0 : generadosTotal / segundos, memoriaMax, cpuServidor(), cpuProcesoMedio());
	}

	double cpuServidor() {
		return ventanas.stream().mapToDouble(Ventana::cpuServidor).average().orElse(0);
	}

	double cpuProcesoMedio() {
		return ventanas.stream().mapToDouble(Ventana::cpuProceso).average().orElse(0);
	}

	/** CPU acumulado de todo el proceso, en ns (0 si la JVM no lo da). */
	private static long cpuProceso() {
		return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os ? os.getProcessCpuTime() : 0;
	}

	double mediaTotal() {
		return media(tiemposTotal.toLongArray());
	}

	double p95Total() {
		return percentil(tiemposTotal.toLongArray(), 0.95);
	}

	double maxTotal() {
		return max(tiemposTotal.toLongArray());
	}

	double venatioMedia() {
		return media(venatioTotal.toLongArray());
	}

	int faltantesMax() {
		return ventanas.stream().mapToInt(Ventana::faltantesMax).max().orElse(0);
	}

	double faltantesMedia() {
		return ventanas.stream().mapToInt(Ventana::faltantesMedia).average().orElse(0);
	}

	/** Porcentaje medio de la vista de los jugadores que faltaba (chunks sin cargar). */
	double huecoPct() {
		return huecoTotal.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 100;
	}

	int faltantesAlFinal() {
		return ultimoFaltantes;
	}

	int generados() {
		return generadosTotal;
	}

	long memoriaMax() {
		return memoriaMax;
	}

	double tps() {
		double s = ventanas.isEmpty() ? 0 : ventanas.get(ventanas.size() - 1).segundo();
		return s == 0 ? 0 : tiemposTotal.size() / s;
	}

	// ---------------------------------------------------------------- chunks

	/** Chunks dentro de la distancia de visión de los participantes (en mundos de la partida) que aún no están cargados. */
	static int faltantes(MinecraftServer server) {
		return faltantesYVista(server)[0];
	}

	/** {faltantes, chunks de la vista en total}: la vista es la unión de los cuadrados de todos los participantes. */
	static int[] faltantesYVista(MinecraftServer server) {
		Partida p = Juego.INSTANCIA.partida();
		if (p == null) return new int[]{0, 0};
		int radio = server.getPlayerList().getViewDistance();
		int total = 0, vista = 0;
		for (ServerLevel nivel : List.of(p.mundos().mundo(), p.mundos().nether(), p.mundos().end())) {
			LongOpenHashSet vistos = new LongOpenHashSet();
			for (ServerPlayer j : nivel.players()) {
				if (!p.participa(j)) continue;
				ChunkPos c = j.chunkPosition();
				for (int dx = -radio; dx <= radio; dx++) {
					for (int dz = -radio; dz <= radio; dz++) {
						int x = c.x() + dx, z = c.z() + dz;
						if (!vistos.add(ChunkPos.pack(x, z))) continue;
						vista++;
						if (nivel.getChunkSource().getChunkNow(x, z) == null) total++;
					}
				}
			}
		}
		return new int[]{total, vista};
	}

	/** Chunks que tienen en memoria los mundos de la partida (cualquier estado, también a medio generar). */
	static int chunksCargados(MinecraftServer server) {
		Partida p = Juego.INSTANCIA.partida();
		if (p == null) return 0;
		return p.mundos().mundo().getChunkSource().getLoadedChunksCount()
			+ p.mundos().nether().getChunkSource().getLoadedChunksCount()
			+ p.mundos().end().getChunkSource().getLoadedChunksCount();
	}

	// ---------------------------------------------------------------- estadística (en ms)

	static double media(long[] ns) {
		return ns.length == 0 ? 0 : Arrays.stream(ns).average().orElse(0) / 1e6;
	}

	static double max(long[] ns) {
		return ns.length == 0 ? 0 : Arrays.stream(ns).max().orElse(0) / 1e6;
	}

	static double percentil(long[] ns, double q) {
		if (ns.length == 0) return 0;
		long[] c = ns.clone();
		Arrays.sort(c);
		return c[Math.min(c.length - 1, (int) Math.ceil(q * c.length) - 1)] / 1e6;
	}
}
