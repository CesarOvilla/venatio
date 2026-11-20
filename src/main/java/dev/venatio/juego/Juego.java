package dev.venatio.juego;

import dev.venatio.Venatio;
import dev.venatio.carton.Carton;
import dev.venatio.carton.Pool;
import dev.venatio.lobby.ConstructorLobby;
import dev.venatio.lobby.TablerosLobby;
import dev.venatio.mundo.Lobby;
import dev.venatio.mundo.MundosPartida;
import dev.venatio.red.Paquetes;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.TeamColor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/** Estado global del juego en el servidor: equipos, configuración y la partida en curso. */
public final class Juego {
	public static final Juego INSTANCIA = new Juego();

	private static final String SIN_MOD =
		"Para jugar en este servidor necesitas el mod Venatio.\nInstálalo con el instalador de Venatio y vuelve a entrar.";
	private static final String VERSION_DISTINTA =
		"Tu Venatio (%s) no coincide con el del servidor (%s).\nVuelve a ejecutar el instalador para actualizarlo.";

	public final Configuracion config = new Configuracion();
	private final Map<UUID, ColorEquipo> equipos = new LinkedHashMap<>();
	private final Map<UUID, String> nombres = new HashMap<>();
	/**
	 * Jugadores recién conectados → tick en que entraron. No se les teletransporta dentro del evento de entrada:
	 * el overworld todavía no termina de registrarlos y les quedaría un registro fantasma de chunk (crash al salir del lobby).
	 */
	private final Map<UUID, Integer> entradasPendientes = new HashMap<>();
	private MinecraftServer server;
	private Pool pool;
	private Partida partida;
	private int ticks;

	private Juego() {}

	public void registrar() {
		ServerLifecycleEvents.SERVER_STARTED.register(this::alArrancar);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			if (partida != null) partida.mundos.borrar();
			partida = null;
		});
		ServerTickEvents.END_SERVER_TICK.register(s -> tick());
		ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> alEntrar(handler.player));
		ServerPlayerEvents.AFTER_RESPAWN.register((viejo, nuevo, vivo) -> alReaparecer(nuevo));
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entidad, fuente, cantidad) -> permitirDano(entidad));
		ServerPlayNetworking.registerGlobalReceiver(Paquetes.Hola.TYPE, (hola, ctx) -> verificarVersion(ctx.player(), hola.version()));
	}

	public MinecraftServer server() {
		return server;
	}

	public Partida partida() {
		return partida;
	}

	String nombre(UUID uuid) {
		return nombres.getOrDefault(uuid, "?");
	}

	// ---------------------------------------------------------------- eventos

	private void alArrancar(MinecraftServer server) {
		this.server = server;
		this.pool = Pool.cargar("/data/venatio/pools/clasico.json");
		Lobby.abrir(server);
		crearEquiposDelMarcador();
		actualizarTableros();
		Venatio.LOG.info("Venatio listo: lobby abierto, pool con {} categorías", pool.categorias().size());
	}

	private void alEntrar(ServerPlayer p) {
		nombres.put(p.getUUID(), p.getGameProfile().name());
		if (!ServerPlayNetworking.canSend(p, Paquetes.Estado.TYPE)) {
			p.connection.disconnect(Component.literal(SIN_MOD));
			return;
		}
		ColorEquipo equipo = equipos.get(p.getUUID());
		if (equipo != null) ponerEnEquipoDelMarcador(p, equipo);
		entradasPendientes.put(p.getUUID(), ticks);
	}

	/** Coloca a quien acaba de entrar: en su partida si participa, si no en el lobby. */
	private void procesarEntradas() {
		var it = entradasPendientes.entrySet().iterator();
		while (it.hasNext()) {
			var entrada = it.next();
			if (ticks - entrada.getValue() < 2) continue;
			it.remove();
			ServerPlayer p = server.getPlayerList().getPlayer(entrada.getKey());
			if (p == null) continue;
			if (partida != null && partida.participa(p)) partida.alReconectar(p);
			else Lobby.enviar(p);
			actualizarTableros();
			enviarEstadoATodos();
		}
	}

	private void verificarVersion(ServerPlayer p, String versionCliente) {
		String propia = Venatio.version();
		if (!propia.equals(versionCliente)) {
			p.connection.disconnect(Component.literal(VERSION_DISTINTA.formatted(versionCliente, propia)));
		}
	}

	private void alReaparecer(ServerPlayer p) {
		if (partida != null && partida.participa(p)) partida.alReaparecer(p);
		else if (!Lobby.esLobby(p.level())) Lobby.enviar(p);
	}

	private boolean permitirDano(LivingEntity entidad) {
		if (!(entidad instanceof ServerPlayer p)) return true;
		if (Lobby.esLobby(p.level())) return false;
		return partida == null || partida.fase() != Partida.Fase.CUENTA_ATRAS || !partida.participa(p);
	}

	private void tick() {
		if (server == null) return;
		ticks++;
		if (!entradasPendientes.isEmpty()) procesarEntradas();
		if (partida != null) partida.tick();
		if (ticks % 10 == 0) tickLobby();
		if (ticks % 20 == 0) enviarEstadoATodos();
	}

	/** Rescata a quien cae al vacío y une al equipo a quien pisa una zona de color. */
	private void tickLobby() {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!Lobby.esLobby(p.level())) continue;
			if (p.getY() < ConstructorLobby.Y_RESCATE) Lobby.volverAlCentro(p);
			if (partida != null && partida.participa(p)) continue;
			ColorEquipo pisado = ConstructorLobby.padBajo(p);
			if (pisado != null && equipos.get(p.getUUID()) != pisado) unir(p, pisado);
		}
	}

	// ---------------------------------------------------------------- equipos

	public Component unir(ServerPlayer p, ColorEquipo color) {
		if (partida != null && partida.participa(p)) return Component.translatable("venatio.error.en_partida");
		equipos.put(p.getUUID(), color);
		ponerEnEquipoDelMarcador(p, color);
		difundir(Component.translatable("venatio.equipo.unido", p.getDisplayName(), color.nombre()));
		actualizarTableros();
		enviarEstadoATodos();
		return null;
	}

	public Component salir(ServerPlayer p) {
		if (partida != null && partida.participa(p)) return Component.translatable("venatio.error.en_partida");
		equipos.remove(p.getUUID());
		server.getScoreboard().removePlayerFromTeam(p.getScoreboardName());
		actualizarTableros();
		enviarEstadoATodos();
		return null;
	}

	/** Reparte a los jugadores del lobby en N equipos al azar, lo más parejo posible. */
	public Component aleatorios(int cantidad) {
		if (partida != null) return Component.translatable("venatio.error.partida_en_curso");
		List<ServerPlayer> jugadores = new ArrayList<>(server.getPlayerList().getPlayers());
		Collections.shuffle(jugadores);
		ColorEquipo[] colores = ColorEquipo.values();
		int n = Math.max(1, Math.min(cantidad, colores.length));
		for (int i = 0; i < jugadores.size(); i++) {
			ServerPlayer p = jugadores.get(i);
			equipos.put(p.getUUID(), colores[i % n]);
			ponerEnEquipoDelMarcador(p, colores[i % n]);
		}
		difundir(Component.translatable("venatio.equipo.aleatorios", n));
		actualizarTableros();
		enviarEstadoATodos();
		return null;
	}

	private void crearEquiposDelMarcador() {
		ServerScoreboard sb = server.getScoreboard();
		for (ColorEquipo c : ColorEquipo.values()) {
			String nombre = "venatio_" + c.id();
			PlayerTeam t = sb.getPlayerTeam(nombre);
			if (t == null) t = sb.addPlayerTeam(nombre);
			t.setDisplayName(c.nombre());
			t.setColor(Optional.of(colorDelMarcador(c)));
			t.setAllowFriendlyFire(false);
			t.setSeeFriendlyInvisibles(true);
		}
	}

	private void ponerEnEquipoDelMarcador(ServerPlayer p, ColorEquipo c) {
		ServerScoreboard sb = server.getScoreboard();
		PlayerTeam t = sb.getPlayerTeam("venatio_" + c.id());
		if (t != null) sb.addPlayerToTeam(p.getScoreboardName(), t);
	}

	private static TeamColor colorDelMarcador(ColorEquipo c) {
		return switch (c) {
			case ROJO -> TeamColor.RED;
			case AZUL -> TeamColor.BLUE;
			case VERDE -> TeamColor.GREEN;
			case AMARILLO -> TeamColor.YELLOW;
			case MORADO -> TeamColor.DARK_PURPLE;
			case NARANJA -> TeamColor.GOLD;
			case CIAN -> TeamColor.AQUA;
			case ROSA -> TeamColor.LIGHT_PURPLE;
		};
	}

	// ---------------------------------------------------------------- partida

	public Component iniciar() {
		if (partida != null) return Component.translatable("venatio.error.partida_en_curso");
		List<ServerPlayer> jugadores = server.getPlayerList().getPlayers();
		if (jugadores.isEmpty()) return Component.translatable("venatio.error.sin_jugadores");

		// Quien no eligió equipo va al equipo con menos gente (entre los ya usados), o a Rojo.
		for (ServerPlayer p : jugadores) {
			if (equipos.containsKey(p.getUUID())) continue;
			ColorEquipo destino = equipoMenosPoblado(jugadores);
			equipos.put(p.getUUID(), destino);
			ponerEnEquipoDelMarcador(p, destino);
		}
		Map<UUID, ColorEquipo> participantes = new LinkedHashMap<>();
		for (ServerPlayer p : jugadores) participantes.put(p.getUUID(), equipos.get(p.getUUID()));

		long semilla = config.semilla != null ? config.semilla : new Random().nextLong();
		Carton carton = Carton.generar(pool, semilla, config.tamano);
		MundosPartida mundos = MundosPartida.crear(server, semilla, config.pvp);
		partida = new Partida(this, config.copia(), semilla, carton, mundos, participantes);
		difundir(Component.translatable("venatio.partida.preparando", config.modo.nombre(), String.valueOf(semilla)));
		enviarEstadoATodos();
		return null;
	}

	private ColorEquipo equipoMenosPoblado(List<ServerPlayer> jugadores) {
		Map<ColorEquipo, Integer> conteo = new EnumMap<>(ColorEquipo.class);
		for (ServerPlayer p : jugadores) {
			ColorEquipo c = equipos.get(p.getUUID());
			if (c != null) conteo.merge(c, 1, Integer::sum);
		}
		return conteo.entrySet().stream().min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(ColorEquipo.ROJO);
	}

	/** El anfitrión corta la partida. Si ya se estaba jugando, se muestra el final; si no, se vuelve directo al lobby. */
	public Component terminar() {
		if (partida == null) return Component.translatable("venatio.error.sin_partida");
		if (partida.fase() == Partida.Fase.JUGANDO) partida.declarar(null, false);
		else if (partida.fase() != Partida.Fase.FINAL) cerrarPartida();
		return null;
	}

	/** Regresa a todos al lobby y borra los mundos de la partida. */
	void cerrarPartida() {
		if (partida == null) return;
		Partida p = partida;
		for (ServerPlayer j : server.getPlayerList().getPlayers()) {
			if (p.participa(j) || MundosPartida.esDePartida(j.level())) Lobby.enviar(j);
		}
		p.mundos.borrar();
		partida = null;
		actualizarTableros();
		enviarEstadoATodos();
	}

	// ---------------------------------------------------------------- red y mensajes

	public void difundir(Component mensaje) {
		server.getPlayerList().broadcastSystemMessage(mensaje, false);
	}

	public void enviarEvento(CustomPacketPayload evento) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(p, evento.type())) ServerPlayNetworking.send(p, evento);
		}
	}

	public void enviarEstadoATodos() {
		if (server == null) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(p, Paquetes.Estado.TYPE)) ServerPlayNetworking.send(p, estadoPara(p));
		}
	}

	private Paquetes.Estado estadoPara(ServerPlayer p) {
		if (partida != null) return partida.estado(partida.equipoDe(p));
		List<Paquetes.EquipoRed> lista = new ArrayList<>();
		for (ColorEquipo c : ColorEquipo.values()) {
			List<String> miembros = new ArrayList<>();
			equipos.forEach((uuid, eq) -> {
				if (eq == c && server.getPlayerList().getPlayer(uuid) != null) miembros.add(nombre(uuid));
			});
			if (!miembros.isEmpty()) lista.add(new Paquetes.EquipoRed(c.ordinal(), 0, miembros));
		}
		ColorEquipo mio = equipos.get(p.getUUID());
		return new Paquetes.Estado(Paquetes.Estado.LOBBY, config.modo.id(), 0, config.limiteSegundos(), config.pvp, 0,
			0, List.of(), lista, mio == null ? -1 : mio.ordinal(), -1);
	}

	/** Refresca los tableros del lobby con la configuración y los equipos actuales. */
	public void actualizarTableros() {
		int limite = config.limiteSegundos();
		List<Component> partidaLineas = List.of(
			Component.translatable("venatio.tablero.modo", config.modo.nombre()),
			limite > 0 ? Component.translatable("venatio.tablero.tiempo", limite / 60) : Component.translatable("venatio.tablero.sin_tiempo"),
			Component.translatable(config.pvp ? "venatio.tablero.pvp_si" : "venatio.tablero.pvp_no"),
			config.semilla != null ? Component.translatable("venatio.tablero.semilla", String.valueOf(config.semilla))
				: Component.translatable("venatio.tablero.semilla_aleatoria"));
		TablerosLobby.actualizar(TablerosLobby.Tablero.PARTIDA, Component.translatable("venatio.tablero.partida"), partidaLineas);

		List<Component> equiposLineas = new ArrayList<>();
		for (ColorEquipo c : ColorEquipo.values()) {
			List<String> miembros = new ArrayList<>();
			equipos.forEach((uuid, eq) -> {
				if (eq == c && server.getPlayerList().getPlayer(uuid) != null) miembros.add(nombre(uuid));
			});
			if (!miembros.isEmpty()) equiposLineas.add(c.nombre().append(Component.literal(": " + String.join(", ", miembros))));
		}
		if (equiposLineas.isEmpty()) equiposLineas.add(Component.translatable("venatio.tablero.sin_equipos"));
		TablerosLobby.actualizar(TablerosLobby.Tablero.EQUIPOS, Component.translatable("venatio.tablero.equipos"), equiposLineas);
	}
}
