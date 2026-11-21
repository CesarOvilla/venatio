package dev.venatio.stats;

import dev.venatio.Venatio;
import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Espectadores;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Modo;
import dev.venatio.juego.Partida;
import dev.venatio.juego.ResumenPartida;
import dev.venatio.lobby.TablerosLobby;
import dev.venatio.panel.PanelAnfitrion;
import dev.venatio.red.PaquetesStats;
import dev.venatio.ruta.GrabadoraRuta;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Módulo de estadísticas, ranking, pantalla final y panel del anfitrión: engancha eventos y comandos,
 * recibe el final de cada partida y mantiene los tableros RANKING y ULTIMA_PARTIDA del lobby.
 */
public final class ModuloStats {
	static final int COLOR_ORO = 0xF2B84B;
	private static final int[] COLOR_PODIO = {0xFFD54A, 0xD8D8D8, 0xD08A4E};
	private static final int FILAS_TABLERO = 10;

	/** Los tableros se rellenan en el primer tick: así da igual el orden de los oyentes de SERVER_STARTED. */
	private static boolean tablerosPendientes;

	private ModuloStats() {}

	public static void registrar() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			Estadisticas.cargar(server);
			tablerosPendientes = true;
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (!tablerosPendientes) return;
			tablerosPendientes = false;
			actualizarTableros();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> Estadisticas.cerrar());
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> ComandosStats.registrar(dispatcher));
		PanelAnfitrion.registrar();
	}

	/** La llama Partida al declarar el final. Un fallo aquí no debe cortar el final de la partida. */
	public static void partidaTerminada(Partida partida) {
		try {
			// La ruta primero: así ya está guardada cuando se dibuja el tablero de la última partida.
			GrabadoraRuta.terminar(partida);
		} catch (RuntimeException e) {
			Venatio.LOG.error("No se pudo armar la ruta de la partida", e);
		}
		try {
			ResumenPartida r = ResumenPartida.de(partida);
			Estadisticas.registrarPartida(r);
			actualizarTableros();
			enviarFinal(r);
		} catch (RuntimeException e) {
			Venatio.LOG.error("No se pudieron registrar las estadísticas de la partida", e);
		}
	}

	// ---------------------------------------------------------------- pantalla final

	private static void enviarFinal(ResumenPartida r) {
		MinecraftServer server = Juego.INSTANCIA.server();
		if (server == null) return;
		List<PaquetesStats.Hito> hitos = new ArrayList<>();
		for (ResumenPartida.Hito h : r.hitos()) {
			hitos.add(new PaquetesStats.Hito(h.casilla(), h.icono(), r.nombre(h.jugador()), h.equipo().ordinal(), h.segundo()));
		}
		List<PaquetesStats.EquipoFinal> equipos = new ArrayList<>();
		for (ResumenPartida.Equipo e : r.equipos()) {
			List<String> miembros = new ArrayList<>();
			for (UUID m : e.miembros()) miembros.add(r.nombre(m));
			int puntos = r.cancelada() ? 0 : Ranking.puntosPorPuesto(e.puesto(), e.casillas());
			equipos.add(new PaquetesStats.EquipoFinal(e.color().ordinal(), e.casillas(), e.puesto(), puntos, miembros));
		}
		ResumenPartida.Destacado d = r.destacado();
		PaquetesStats.FinalPartida paquete = new PaquetesStats.FinalPartida(r.modo().id(), r.ganador() == null ? -1 : r.ganador().ordinal(),
			r.empate(), !r.cancelada(), r.duracion(), r.totalCasillas(), hitos, equipos,
			d == null ? "" : r.nombre(d.jugador()), d == null ? 0 : d.casillas());
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			boolean veFinal = r.participantes().containsKey(p.getUUID()) || Espectadores.es(p);
			if (veFinal && ServerPlayNetworking.canSend(p, PaquetesStats.FinalPartida.TYPE)) {
				ServerPlayNetworking.send(p, paquete);
			}
		}
	}

	// ---------------------------------------------------------------- tableros del lobby

	public static void actualizarTableros() {
		if (!Estadisticas.cargadas()) return;
		tableroRanking();
		tableroUltima();
	}

	private static void tableroRanking() {
		int temporada = Estadisticas.temporada();
		List<Component> lineas = new ArrayList<>();
		List<Ranking.Fila> tabla = Ranking.tabla(temporada);
		for (int i = 0; i < tabla.size() && i < FILAS_TABLERO; i++) {
			Ranking.Fila f = tabla.get(i);
			MutableComponent puesto = Component.literal(f.puesto() + ".º ");
			if (f.puesto() <= 3) puesto.withColor(COLOR_PODIO[f.puesto() - 1]);
			lineas.add(Component.empty().append(puesto)
				.append(Component.translatable("venatio.stats.tablero.fila", f.nombre(), f.puntos())));
		}
		if (lineas.isEmpty()) {
			lineas.add(Component.translatable("venatio.stats.tablero.sin_partidas").withStyle(ChatFormatting.GRAY));
			lineas.add(Component.translatable("venatio.stats.tablero.sin_partidas2").withStyle(ChatFormatting.GRAY));
		}
		TablerosLobby.actualizar(TablerosLobby.Tablero.RANKING, Component.translatable("venatio.stats.tablero.ranking", temporada), lineas);
	}

	private static void tableroUltima() {
		Estadisticas.UltimaPartida u = Estadisticas.datos().ultimaPartida;
		Component titulo = Component.translatable("venatio.stats.tablero.ultima");
		List<Component> lineas = new ArrayList<>();
		if (u == null) {
			lineas.add(Component.translatable("venatio.stats.tablero.ultima.ninguna").withStyle(ChatFormatting.GRAY));
			TablerosLobby.actualizar(TablerosLobby.Tablero.ULTIMA_PARTIDA, titulo, lineas);
			return;
		}
		lineas.add(Component.translatable("venatio.stats.tablero.ultima.modo", Modo.porId(u.modo).nombre(), reloj(u.duracion)));
		ColorEquipo ganador = color(u.ganador);
		lineas.add(ganador != null ? Component.translatable("venatio.stats.resultado.gana", ganador.nombre())
			: Component.translatable(u.empate ? "venatio.stats.resultado.empate" : "venatio.stats.resultado.cancelada"));
		if (u.destacado != null) {
			lineas.add(Component.translatable("venatio.stats.tablero.ultima.destacado", u.destacado, u.destacadoCasillas));
		}
		for (Estadisticas.EquipoUltima e : u.equipos) {
			ColorEquipo c = color(e.color);
			if (c == null) continue;
			lineas.add(Component.translatable(u.cancelada ? "venatio.stats.tablero.ultima.equipo_sin_puntos" : "venatio.stats.tablero.ultima.equipo",
				e.puesto + ".º", c.nombre(), e.casillas, e.puntos));
		}
		// La ruta se puede volver a ver hasta que empiece otra partida (y cabe si sobra una línea en el tablero).
		if (GrabadoraRuta.ultima() != null && lineas.size() < FILAS_TABLERO) {
			lineas.add(Component.translatable("venatio.ruta.tablero").withStyle(ChatFormatting.GRAY));
		}
		TablerosLobby.actualizar(TablerosLobby.Tablero.ULTIMA_PARTIDA, titulo, lineas);
	}

	// ---------------------------------------------------------------- utilidades

	static ColorEquipo color(String id) {
		if (id == null) return null;
		for (ColorEquipo c : ColorEquipo.values()) if (c.id().equals(id.toLowerCase(Locale.ROOT))) return c;
		return null;
	}

	static String reloj(int segundos) {
		int s = Math.max(0, segundos);
		return String.format("%d:%02d", s / 60, s % 60);
	}
}
