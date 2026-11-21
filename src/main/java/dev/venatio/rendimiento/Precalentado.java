package dev.venatio.rendimiento;

import dev.venatio.Venatio;
import dev.venatio.juego.Configuracion;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Partida;
import dev.venatio.mundo.Lobby;
import dev.venatio.mundo.MundosPartida;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.scores.PlayerTeam;

import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;

/**
 * Genera el mundo de la siguiente partida mientras todos están en el lobby, para que al empezar el terreno alrededor de
 * cada equipo ya exista y el CPU quede libre para lo que exploran. La vista completa alrededor de 3 puntos de aparición
 * son unos 2000 chunks: decenas de segundos de CPU en un server de 3 núcleos (ver docs/RENDIMIENTO.md).
 * <ul>
 * <li>Elige la semilla por adelantado (la fija del anfitrión, o una al azar) y crea los mundos sin activarlos.</li>
 * <li>Pide generar, sin simular, la distancia de visión del server alrededor de los puntos de aparición que tendrían los
 * equipos armados en el lobby (el mismo cálculo que Partida).</li>
 * <li>Si el anfitrión fija otra semilla (o vuelve a la aleatoria), tira los mundos y prepara otros. Si cambian los
 * equipos, la distancia o el PvP, solo mueve lo que se genera.</li>
 * <li>Al iniciar, Juego toma estos mundos si la semilla coincide. Los tickets se sueltan cuando los jugadores ya
 * llegaron (desde ahí ellos mantienen su zona cargada).</li>
 * </ul>
 * Se apaga con -Dvenatio.precalentar=false.
 */
public final class Precalentado {
	/** Carga y genera, pero no simula (los mobs de esos chunks no gastan CPU en el lobby) ni vence. */
	private static final TicketType TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE,
		Identifier.fromNamespaceAndPath(Venatio.MOD_ID, "precalentado"), new TicketType(0L, TicketType.FLAG_LOADING));
	/** Cada cuántos ticks se revisa si el plan cambió. */
	private static final int CADA = 20;
	/** Tras cerrar una partida, cuánto esperar antes de crear los mundos de la siguiente. */
	private static final int ESPERA = 20 * 3;

	/** Lo que decide dónde y cuánto generar. */
	private record Plan(int equipos, boolean pvp, int distancia, int radio) {}

	private static MinecraftServer server;
	private static int ticks, ultimoTickConPartida;
	/** Mundos creados para la próxima partida (no activos). */
	private static MundosPartida preparados;
	private static long semilla;
	/** La semilla de los preparados era la fija del anfitrión (no una al azar). */
	private static boolean semillaFija;
	private static Plan plan;
	/** Dónde están nuestros tickets, con qué radio y en qué mundo. */
	private static final Set<ChunkPos> centros = new LinkedHashSet<>();
	private static int radioTickets;
	private static ServerLevel conTickets;
	/** Mundos que ya tomó una partida y siguen con nuestros tickets hasta que lleguen los jugadores. */
	private static MundosPartida tomados;

	private Precalentado() {}

	public static boolean activo() {
		return !"false".equals(System.getProperty("venatio.precalentar"));
	}

	public static void registrar() {
		ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			descartar();
			olvidarTickets();
			tomados = null;
			server = null;
		});
		ServerTickEvents.END_SERVER_TICK.register(s -> tick());
	}

	/** La semilla de la próxima partida: la del anfitrión, o la de los mundos ya preparados, o una al azar. */
	public static long semilla(Configuracion cfg) {
		if (cfg.semilla != null) return cfg.semilla;
		return preparados != null && !semillaFija ? semilla : new Random().nextLong();
	}

	/** Los mundos para una partida que empieza: los preparados si son de esa semilla; si no, unos nuevos. */
	public static MundosPartida tomar(MinecraftServer s, long semillaPartida, boolean pvp, boolean conservarInventario) {
		if (preparados == null || semilla != semillaPartida) {
			descartar();
			return MundosPartida.crear(s, semillaPartida, pvp, conservarInventario);
		}
		MundosPartida m = preparados.activar();
		m.ajustarReglas(pvp, conservarInventario);
		Venatio.LOG.info("La partida usa los mundos precalentados ({} zona(s) de radio {})", centros.size(), radioTickets);
		tomados = m;
		preparados = null;
		plan = null;
		return m;
	}

	// ---------------------------------------------------------------- ciclo

	private static void tick() {
		if (server == null || ++ticks % CADA != 0) return;
		Partida partida = Juego.INSTANCIA.partida();
		if (tomados != null && (partida == null || partida.mundos() != tomados || partida.fase() != Partida.Fase.PREPARANDO)) {
			// Los jugadores ya llegaron, o la partida se canceló y sus mundos se borran: nuestros tickets sobran.
			if (partida != null && partida.mundos() == tomados) soltarTickets();
			else olvidarTickets();
			tomados = null;
		}
		if (partida != null) {
			ultimoTickConPartida = ticks;
			return;
		}
		if (!activo() || ticks - ultimoTickConPartida < ESPERA || Lobby.nivel() == null) return;

		Configuracion cfg = Juego.INSTANCIA.config;
		boolean otraSemilla = cfg.semilla != null ? !semillaFija || cfg.semilla != semilla : semillaFija;
		if (preparados != null && otraSemilla) descartar();
		if (preparados == null) {
			semillaFija = cfg.semilla != null;
			semilla = semillaFija ? cfg.semilla : new Random().nextLong();
			preparados = MundosPartida.preparar(server, semilla, cfg.pvp, cfg.conservarInventario);
			plan = null;
		}
		Plan nuevo = new Plan(equiposEnLobby(), cfg.pvp, cfg.distancia, server.getPlayerList().getViewDistance());
		if (nuevo.equals(plan)) return;
		soltarTickets();
		ServerLevel mundo = preparados.mundo();
		for (BlockPos p : Partida.puntosDeAparicion(mundo, semilla, nuevo.equipos(), nuevo.pvp(), nuevo.distancia())) {
			centros.add(ChunkPos.containing(p));
		}
		for (ChunkPos c : centros) mundo.getChunkSource().addTicketWithRadius(TICKET, c, nuevo.radio());
		radioTickets = nuevo.radio();
		conTickets = mundo;
		plan = nuevo;
	}

	/** Equipos distintos entre los jugadores conectados (quien no eligió va a uno ya usado al iniciar; si nadie, a uno). */
	private static int equiposEnLobby() {
		Set<String> equipos = new LinkedHashSet<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			PlayerTeam t = server.getScoreboard().getPlayersTeam(p.getScoreboardName());
			if (t != null && t.getName().startsWith("venatio_")) equipos.add(t.getName());
		}
		return Math.max(1, equipos.size());
	}

	private static void soltarTickets() {
		if (conTickets != null) {
			for (ChunkPos c : centros) conTickets.getChunkSource().removeTicketWithRadius(TICKET, c, radioTickets);
		}
		olvidarTickets();
	}

	/** Para mundos que se borran: no hace falta quitar los tickets uno por uno. */
	private static void olvidarTickets() {
		centros.clear();
		conTickets = null;
	}

	private static void descartar() {
		if (preparados == null) return;
		if (conTickets == preparados.mundo()) olvidarTickets();
		preparados.borrar();
		preparados = null;
		plan = null;
	}
}
