package dev.venatio.localizador;

import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Opcion;
import dev.venatio.juego.Partida;
import dev.venatio.mundo.MundosPartida;
import dev.venatio.red.PaquetesLocalizador;
import dev.venatio.red.PaquetesLocalizador.Localizacion;
import dev.venatio.red.PaquetesLocalizador.PuntoRed;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Barra de localización (servidor). Cada 10 ticks, durante la cuenta atrás y el juego, manda a cada participante con el mod
 * los jugadores que puede ver en su mismo mundo. Los compañeros se ven siempre; los rivales, si el anfitrión lo permite y no
 * se están escondiendo. La barra vanilla está apagada en el lobby y en los mundos de la partida (gamerule locator_bar).
 */
public final class Localizador {
	private static final int CADA = 10;

	private static MinecraftServer server;
	private static int ticks;
	/** Quienes tienen la barra encendida: cuando deja de aplicar (fin de partida, salió del mundo) se les apaga una vez. */
	private static final Set<UUID> conBarra = new HashSet<>();

	private Localizador() {}

	public static void registrar() {
		PaquetesLocalizador.registrar();
		ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			conBarra.clear();
			server = null;
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> conBarra.remove(handler.player.getUUID()));
		ServerTickEvents.END_SERVER_TICK.register(s -> tick());
	}

	private static void tick() {
		if (server == null || ++ticks % CADA != 0) return;
		Partida partida = Juego.INSTANCIA.partida();
		boolean encendida = partida != null && partida.opcion(Opcion.LOCALIZADOR)
			&& (partida.fase() == Partida.Fase.CUENTA_ATRAS || partida.fase() == Partida.Fase.JUGANDO);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!ServerPlayNetworking.canSend(p, Localizacion.TYPE)) continue;
			if (encendida && partida.participa(p) && MundosPartida.esDePartida(p.level())) {
				ServerPlayNetworking.send(p, new Localizacion(true, p.level().dimension().identifier(), visiblesPara(partida, p)));
				conBarra.add(p.getUUID());
			} else if (conBarra.remove(p.getUUID())) {
				ServerPlayNetworking.send(p, Localizacion.APAGADA);
			}
		}
	}

	/** Los jugadores que p ve en la barra: participantes vivos de su mismo mundo, sin espectadores ni él mismo. */
	public static List<PuntoRed> visiblesPara(Partida partida, ServerPlayer p) {
		ColorEquipo mio = partida.equipoDe(p);
		boolean rivales = partida.opcion(Opcion.LOCALIZADOR_RIVALES);
		List<PuntoRed> lista = new ArrayList<>();
		for (ServerPlayer otro : p.level().players()) {
			if (otro == p || otro.isSpectator() || !otro.isAlive() || !partida.participa(otro)) continue;
			ColorEquipo suyo = partida.equipoDe(otro);
			boolean companero = suyo == mio;
			if (!companero && (!rivales || seEsconde(otro))) continue;
			lista.add(new PuntoRed(otro.getUUID(), otro.getGameProfile().name(), suyo.ordinal(),
				(float) otro.getX(), (float) otro.getEyeY(), (float) otro.getZ(), companero));
		}
		return lista;
	}

	/**
	 * Lo que esconde a un jugador de la barra vanilla: agacharse, invisibilidad, o calabaza tallada o cabeza de mob puesta
	 * (las tres dejan en 0 el atributo waypoint_transmit_range). Solo aplica frente a los rivales.
	 */
	public static boolean seEsconde(ServerPlayer p) {
		return p.isCrouching() || p.hasEffect(MobEffects.INVISIBILITY) || !p.isTransmittingWaypoint();
	}
}
