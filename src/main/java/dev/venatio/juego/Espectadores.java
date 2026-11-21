package dev.venatio.juego;

import dev.venatio.mundo.Lobby;
import dev.venatio.mundo.MundosPartida;
import dev.venatio.red.PaquetesRuta;
import dev.venatio.red.PaquetesRuta.AccionEspectador;
import dev.venatio.red.PaquetesRuta.EstadoEspectador;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Modo espectador: quien no tiene equipo al iniciar, quien entra a media partida sin equipo y quien usa /venatio mirar
 * mira la partida en modo espectador dentro de sus mundos. No es participante: no cuenta para casillas, lockout,
 * localizador, estadísticas ni victoria. Sigue a un jugador en primera persona con la cámara de espectador vanilla
 * (setCamera) y cambia de jugador o de equipo con las teclas de su HUD; Espacio (o Shift, como en vanilla) deja la
 * vista libre. Al terminar vuelve al lobby con los demás.
 */
public final class Espectadores {
	/** Ticks después de enganchar la cámara en que se vuelve a mandar: si el cliente aún no tenía al jugador cargado, la ignoró. */
	private static final int[] REENVIOS = {10, 30, 70};
	private static final int CADA_REVISION = 5;
	private static final int CADA_ESTADO = 20;

	private static final class Seguimiento {
		UUID seguido;
		boolean libre;
		/** Última cámara que puso el servidor, para distinguir "se soltó él" de "el seguido murió o cambió de mundo". */
		Entity camara;
		int enganche = Integer.MIN_VALUE;
	}

	private static final Map<UUID, Seguimiento> lista = new LinkedHashMap<>();
	private static int ticks;

	private Espectadores() {}

	public static void registrar() {
		ServerTickEvents.END_SERVER_TICK.register(Espectadores::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> lista.clear());
		ServerPlayNetworking.registerGlobalReceiver(AccionEspectador.TYPE, (a, ctx) -> accion(ctx.player(), a.tipo()));
	}

	public static boolean es(ServerPlayer p) {
		return lista.containsKey(p.getUUID());
	}

	/** UUID de a quién sigue el espectador (null en vista libre o si no es espectador). */
	public static UUID siguiendoA(ServerPlayer p) {
		Seguimiento s = lista.get(p.getUUID());
		return s == null || s.libre ? null : s.seguido;
	}

	// ---------------------------------------------------------------- entrar y salir

	/** Juego.iniciar: quienes no tienen equipo miran. Entran a los mundos cuando empieza la cuenta atrás. */
	static void alIniciar(List<ServerPlayer> sinEquipo) {
		lista.clear();
		for (ServerPlayer p : sinEquipo) {
			lista.put(p.getUUID(), new Seguimiento());
			p.sendSystemMessage(Component.translatable("venatio.espectador.sin_equipo"));
		}
	}

	/**
	 * Juego.procesarEntradas: entra al server alguien que no participa. Si ya miraba, o no tiene equipo, mira.
	 * Devuelve true si quedó dentro de la partida (si no, va al lobby).
	 */
	static boolean alEntrar(Partida partida, ServerPlayer p, boolean sinEquipo) {
		if (partida == null || partida.fase() == Partida.Fase.FINAL) {
			lista.remove(p.getUUID());
			return false;
		}
		if (!lista.containsKey(p.getUUID()) && !sinEquipo) return false;
		Seguimiento s = lista.computeIfAbsent(p.getUUID(), u -> new Seguimiento());
		if (partida.fase() == Partida.Fase.PREPARANDO) return false; // entra con la cuenta atrás
		entrar(partida, p, s);
		return true;
	}

	/** /venatio mirar: desde el lobby, durante una partida. */
	public static Component mirar(ServerPlayer p) {
		Partida partida = Juego.INSTANCIA.partida();
		if (partida == null) return Component.translatable("venatio.error.sin_partida");
		if (partida.participa(p)) return Component.translatable("venatio.espectador.error.participas");
		if (partida.fase() == Partida.Fase.FINAL) return Component.translatable("venatio.espectador.error.terminando");
		if (lista.containsKey(p.getUUID()) && (MundosPartida.esDePartida(p.level()) || partida.fase() == Partida.Fase.PREPARANDO)) {
			return Component.translatable("venatio.espectador.error.ya_miras");
		}
		Seguimiento s = lista.computeIfAbsent(p.getUUID(), u -> new Seguimiento());
		if (partida.fase() == Partida.Fase.PREPARANDO) {
			p.sendSystemMessage(Component.translatable("venatio.espectador.esperando"));
			return null;
		}
		entrar(partida, p, s);
		return null;
	}

	/** /venatio mirar salir: deja de mirar y vuelve al lobby. */
	public static Component dejar(ServerPlayer p) {
		if (lista.remove(p.getUUID()) == null) return Component.translatable("venatio.espectador.error.no_miras");
		enviar(p, EstadoEspectador.INACTIVO);
		if (MundosPartida.esDePartida(p.level()) || p.isSpectator()) Lobby.enviar(p);
		return null;
	}

	/** Juego.cerrarPartida, antes de llevar a todos al lobby. */
	static void alCerrar(MinecraftServer server) {
		for (UUID u : lista.keySet()) {
			ServerPlayer p = server.getPlayerList().getPlayer(u);
			if (p == null) continue;
			enviar(p, EstadoEspectador.INACTIVO);
			// Quien se salió de los mundos de la partida en modo espectador no lo recoge cerrarPartida.
			if (p.isSpectator() && !MundosPartida.esDePartida(p.level())) Lobby.enviar(p);
		}
		lista.clear();
	}

	/** Lo mete en los mundos de la partida en modo espectador, siguiendo a alguien si hay a quién. */
	private static void entrar(Partida partida, ServerPlayer p, Seguimiento s) {
		Jugadores.reiniciar(p, GameType.SPECTATOR);
		// La visión nocturna de los jugadores no viaja a quien mira con su cámara: se le da la suya.
		if (partida.cfg.vision) p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
		ServerPlayer t = objetivo(partida, s.seguido);
		if (t == null) {
			List<ServerPlayer> c = candidatos(partida);
			t = c.isEmpty() ? null : c.getFirst();
		}
		if (t != null) {
			s.seguido = t.getUUID();
			enganchar(p, t, s);
		} else {
			// Nadie a quién seguir: vista libre sobre el punto de aparición del primer equipo.
			s.libre = true;
			s.camara = null;
			BlockPos pos = partida.equipos.isEmpty() ? null : partida.spawnDe(partida.equipos.getFirst());
			if (pos == null) pos = BlockPos.ZERO.above(100);
			p.teleportTo(partida.mundos().mundo(), pos.getX() + 0.5, pos.getY() + 20, pos.getZ() + 0.5, Set.of(), 0f, 45f, true);
		}
		p.sendSystemMessage(Component.translatable("venatio.espectador.entraste"));
		enviarEstado(p, s);
	}

	// ---------------------------------------------------------------- ciclo

	private static void tick(MinecraftServer server) {
		ticks++;
		if (lista.isEmpty()) return;
		Partida partida = Juego.INSTANCIA.partida();
		if (partida == null) {
			lista.clear();
			return;
		}
		if (partida.fase() == Partida.Fase.PREPARANDO) return;
		for (Map.Entry<UUID, Seguimiento> e : lista.entrySet()) {
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null || partida.participa(p)) continue;
			Seguimiento s = e.getValue();
			if (!MundosPartida.esDePartida(p.level())) {
				// Recién empezada la cuenta atrás, o se fue (p. ej. con el menú de espectador vanilla a alguien del lobby).
				if (p.isAlive()) entrar(partida, p, s);
				continue;
			}
			if (ticks % CADA_REVISION == 0) seguir(partida, p, s);
			reenviarCamara(p, s);
			if (ticks % CADA_ESTADO == 0) enviarEstado(p, s);
		}
	}

	/** Mantiene la cámara en el seguido: se vuelve a enganchar si murió y reapareció, si cambió de mundo o si se fue. */
	private static void seguir(Partida partida, ServerPlayer p, Seguimiento s) {
		Entity cam = p.getCamera();
		if (s.libre) {
			// Eligió a alguien con el clic de espectador vanilla: se le sigue igual.
			if (cam != p && cam instanceof ServerPlayer t && partida.participa(t)) {
				s.libre = false;
				s.seguido = t.getUUID();
				s.camara = t;
				enviarEstado(p, s);
			}
			return;
		}
		ServerPlayer t = objetivo(partida, s.seguido);
		if (t == null) {
			// Se desconectó: al siguiente que haya.
			List<ServerPlayer> c = candidatos(partida);
			if (c.isEmpty()) {
				soltar(p, s);
				return;
			}
			t = c.getFirst();
			s.seguido = t.getUUID();
			enganchar(p, t, s);
			enviarEstado(p, s);
			return;
		}
		if (cam == t) {
			if (t.level() != p.level()) enganchar(p, t, s); // cruzó un portal
			return;
		}
		if (cam != p) {
			// Le dio clic a otra entidad con el modo espectador vanilla.
			if (cam instanceof ServerPlayer o && partida.participa(o)) {
				s.seguido = o.getUUID();
				s.camara = o;
			} else {
				s.libre = true;
				s.camara = null;
			}
			enviarEstado(p, s);
			return;
		}
		if (s.camara == t && t.isAlive() && t.level() == p.level()) {
			// Se soltó él mismo (Shift, como en vanilla): vista libre.
			s.libre = true;
			s.camara = null;
			enviarEstado(p, s);
			return;
		}
		if (!t.isAlive()) return; // muerto: se vuelve a enganchar cuando reaparezca
		enganchar(p, t, s);
	}

	private static void enganchar(ServerPlayer p, ServerPlayer t, Seguimiento s) {
		if (p.getCamera() != p) p.setCamera(p);
		if (p.level() != t.level()) {
			p.teleportTo(t.level(), t.getX(), t.getY(), t.getZ(), Set.of(), t.getYRot(), t.getXRot(), false);
		}
		p.setCamera(t);
		s.camara = t;
		s.libre = false;
		s.enganche = ticks;
	}

	private static void soltar(ServerPlayer p, Seguimiento s) {
		p.setCamera(p);
		s.libre = true;
		s.camara = null;
		enviarEstado(p, s);
	}

	private static void reenviarCamara(ServerPlayer p, Seguimiento s) {
		if (s.libre || s.camara == null || p.getCamera() != s.camara) return;
		for (int r : REENVIOS) {
			if (ticks - s.enganche == r) p.connection.send(new ClientboundSetCameraPacket(s.camara));
		}
	}

	// ---------------------------------------------------------------- teclas del espectador

	private static void accion(ServerPlayer p, int tipo) {
		Seguimiento s = lista.get(p.getUUID());
		Partida partida = Juego.INSTANCIA.partida();
		if (s == null || partida == null || partida.participa(p) || !MundosPartida.esDePartida(p.level())) return;
		if (tipo == AccionEspectador.LIBRE) {
			if (!s.libre) soltar(p, s);
			return;
		}
		List<ServerPlayer> c = candidatos(partida);
		if (c.isEmpty()) {
			p.sendOverlayMessage(Component.translatable("venatio.espectador.nadie"));
			return;
		}
		ServerPlayer actual = objetivo(partida, s.seguido);
		int i = actual == null ? -1 : c.indexOf(actual);
		ServerPlayer nuevo = switch (tipo) {
			case AccionEspectador.SIGUIENTE -> c.get(Math.floorMod(i + 1, c.size()));
			case AccionEspectador.ANTERIOR -> c.get(i < 0 ? c.size() - 1 : Math.floorMod(i - 1, c.size()));
			case AccionEspectador.EQUIPO_SIGUIENTE -> deOtroEquipo(partida, c, actual, 1);
			case AccionEspectador.EQUIPO_ANTERIOR -> deOtroEquipo(partida, c, actual, -1);
			default -> null;
		};
		if (nuevo == null) return;
		s.seguido = nuevo.getUUID();
		enganchar(p, nuevo, s);
		enviarEstado(p, s);
	}

	/** El primer jugador del equipo siguiente (o anterior) al del seguido que tenga a alguien jugando. */
	private static ServerPlayer deOtroEquipo(Partida partida, List<ServerPlayer> c, ServerPlayer actual, int paso) {
		List<ColorEquipo> conGente = new ArrayList<>();
		for (ColorEquipo e : partida.equipos) {
			for (ServerPlayer j : c) {
				if (partida.equipoDe(j) == e) {
					conGente.add(e);
					break;
				}
			}
		}
		if (conGente.isEmpty()) return null;
		int i = actual == null ? -1 : conGente.indexOf(partida.equipoDe(actual));
		ColorEquipo destino = conGente.get(i < 0 ? (paso > 0 ? 0 : conGente.size() - 1) : Math.floorMod(i + paso, conGente.size()));
		for (ServerPlayer j : c) if (partida.equipoDe(j) == destino) return j;
		return null;
	}

	/** Participantes en línea dentro de los mundos de la partida, por equipo y nombre. */
	private static List<ServerPlayer> candidatos(Partida partida) {
		List<ServerPlayer> c = new ArrayList<>();
		for (ServerPlayer j : partida.jugadoresEnLinea()) if (MundosPartida.esDePartida(j.level())) c.add(j);
		c.sort(Comparator.<ServerPlayer>comparingInt(j -> partida.equipoDe(j).ordinal()).thenComparing(j -> j.getGameProfile().name()));
		return c;
	}

	private static ServerPlayer objetivo(Partida partida, UUID uuid) {
		if (uuid == null) return null;
		ServerPlayer t = Juego.INSTANCIA.server().getPlayerList().getPlayer(uuid);
		return t != null && partida.participa(t) && MundosPartida.esDePartida(t.level()) ? t : null;
	}

	// ---------------------------------------------------------------- red

	private static void enviarEstado(ServerPlayer p, Seguimiento s) {
		Partida partida = Juego.INSTANCIA.partida();
		UUID u = s.seguido;
		ColorEquipo equipo = null;
		if (u != null && partida != null) equipo = partida.participantes.get(u);
		enviar(p, new EstadoEspectador(true, s.libre, u == null ? new UUID(0, 0) : u,
			u == null ? "" : Juego.INSTANCIA.nombre(u), equipo == null ? -1 : equipo.ordinal()));
	}

	private static void enviar(ServerPlayer p, EstadoEspectador e) {
		if (ServerPlayNetworking.canSend(p, PaquetesRuta.EstadoEspectador.TYPE)) ServerPlayNetworking.send(p, e);
	}
}
