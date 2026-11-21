package dev.venatio.panel;

import dev.venatio.juego.Ajustes;
import dev.venatio.juego.Configuracion;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Modo;
import dev.venatio.juego.Opcion;
import dev.venatio.lobby.ConstructorLobby;
import dev.venatio.mundo.Lobby;
import dev.venatio.red.PaquetesStats;
import dev.venatio.red.PaquetesStats.AccionPanel;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Panel del anfitrión: clic derecho en el atril del lobby abre en el cliente la pantalla de configuración.
 * Todos la pueden ver; solo los operadores pueden cambiarla, y cada cambio pasa por Ajustes (lo mismo que los comandos).
 */
public final class PanelAnfitrion {
	private static final Identifier DIMENSION_LOBBY = Identifier.fromNamespaceAndPath("venatio", "lobby");

	private PanelAnfitrion() {}

	public static void registrar() {
		UseBlockCallback.EVENT.register(PanelAnfitrion::alUsarBloque);
		ServerPlayNetworking.registerGlobalReceiver(AccionPanel.TYPE, (accion, ctx) -> recibir(ctx.player(), accion));
		Ajustes.alCambiar(PanelAnfitrion::refrescarTodos);
	}

	private static InteractionResult alUsarBloque(Player jugador, Level nivel, InteractionHand mano, BlockHitResult golpe) {
		if (!golpe.getBlockPos().equals(ConstructorLobby.panelAnfitrion())) return InteractionResult.PASS;
		// En el cliente solo se confirma el clic (mueve el brazo y no usa el item); el servidor decide.
		if (nivel.isClientSide()) return nivel.dimension().identifier().equals(DIMENSION_LOBBY) ? InteractionResult.SUCCESS : InteractionResult.PASS;
		if (!(jugador instanceof ServerPlayer p) || !Lobby.esLobby(nivel)) return InteractionResult.PASS;
		if (mano == InteractionHand.MAIN_HAND) abrir(p);
		return InteractionResult.SUCCESS;
	}

	public static void abrir(ServerPlayer p) {
		enviar(p, true);
	}

	static boolean esOperador(ServerPlayer p) {
		return Commands.LEVEL_GAMEMASTERS.check(p.permissions());
	}

	private static void enviar(ServerPlayer p, boolean abrir) {
		if (!ServerPlayNetworking.canSend(p, PaquetesStats.ConfigPanel.TYPE)) return;
		Juego j = Juego.INSTANCIA;
		Configuracion c = j.config;
		List<PaquetesStats.OpcionRed> opciones = new ArrayList<>();
		for (Opcion o : Opcion.values()) opciones.add(new PaquetesStats.OpcionRed(o.id, o.valor(c)));
		ServerPlayNetworking.send(p, new PaquetesStats.ConfigPanel(abrir, esOperador(p), j.partida() != null, c.modo.id(), c.minutos, c.pvp,
			c.distancia, c.semilla != null, c.semilla == null ? 0 : c.semilla, opciones, Ajustes.MAX_MINUTOS, Ajustes.MAX_DISTANCIA,
			AjustesPanel.paraRed(c)));
	}

	/** Manda la configuración a todos para que el panel abierto de cualquiera se actualice. */
	static void refrescarTodos() {
		MinecraftServer server = Juego.INSTANCIA.server();
		if (server == null) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) enviar(p, false);
	}

	private static void recibir(ServerPlayer p, AccionPanel a) {
		if (!esOperador(p)) {
			p.sendSystemMessage(Component.translatable("venatio.stats.panel.sin_permiso").withStyle(ChatFormatting.RED));
			enviar(p, false);
			return;
		}
		Juego j = Juego.INSTANCIA;
		int numero = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, a.numero()));
		Component error = switch (a.tipo()) {
			case AccionPanel.MODO -> {
				Modo m = modo(a.texto());
				yield m == null ? invalido() : Ajustes.modo(m);
			}
			case AccionPanel.TIEMPO -> Ajustes.tiempo(numero);
			case AccionPanel.PVP -> Ajustes.pvp(a.numero() != 0);
			case AccionPanel.DISTANCIA -> Ajustes.distancia(numero);
			case AccionPanel.OPCION -> {
				Opcion o = opcion(a.texto());
				yield o == null ? invalido() : Ajustes.opcion(o, a.numero() != 0);
			}
			case AccionPanel.SEMILLA -> Ajustes.semilla("aleatoria".equals(a.texto()) ? null : a.numero());
			case AccionPanel.ALEATORIOS -> j.aleatorios(Math.max(1, Math.min(8, numero)));
			case AccionPanel.INICIAR -> j.iniciar();
			case AccionPanel.AJUSTE -> AjustesPanel.aplicar(a.texto());
			default -> invalido();
		};
		if (error != null) {
			p.sendSystemMessage(error.copy().withStyle(ChatFormatting.RED));
			enviar(p, false);
		} else if (a.tipo() == AccionPanel.ALEATORIOS || a.tipo() == AccionPanel.INICIAR) {
			// No pasan por Ajustes; se refresca a mano (p. ej. para que los paneles abiertos vean la partida en curso).
			refrescarTodos();
		}
	}

	private static Modo modo(String id) {
		for (Modo m : Modo.values()) if (m.id().equals(id)) return m;
		return null;
	}

	private static Opcion opcion(String id) {
		for (Opcion o : Opcion.values()) if (o.id.equals(id)) return o;
		return null;
	}

	private static Component invalido() {
		return Component.translatable("venatio.stats.panel.invalido");
	}
}
