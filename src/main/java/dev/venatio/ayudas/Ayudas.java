package dev.venatio.ayudas;

import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Partida;
import dev.venatio.mundo.MundosPartida;
import dev.venatio.red.PaquetesAyudas;
import dev.venatio.red.PaquetesTienda;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ayudas con costo (servidor): tienda, guías a bioma / estructura / mob, minimapa y marcas de equipo.
 * Solo funcionan durante la fase JUGANDO, para participantes y dentro de los mundos de la partida.
 * Todo corre en el hilo del servidor salvo la búsqueda de biomas (ver {@link BusquedaBioma}).
 */
public final class Ayudas {
	private static Precios precios;
	private static MinecraftServer server;
	private static long ticks;
	private static Partida partidaVista;
	/** Jugador → tick en que vuelve a estar disponible cada ayuda (índice = ordinal de TipoAyuda). */
	private static final Map<UUID, long[]> enfriamientos = new HashMap<>();
	/** Jugador → tick en que se le acaba el minimapa comprado. */
	private static final Map<UUID, Long> minimapaHasta = new HashMap<>();

	private Ayudas() {}

	public static void registrar() {
		PaquetesTienda.registrar();
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			precios = Precios.cargar();
			reiniciar();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			reiniciar();
			partidaVista = null;
			server = null;
		});
		ServerTickEvents.END_SERVER_TICK.register(s -> tick());
		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> alEntrar(handler.player));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> Guias.alSalir(handler.player));

		ServerPlayNetworking.registerGlobalReceiver(PaquetesAyudas.PedirGuia.TYPE,
			(pedido, ctx) -> Guias.pedir(ctx.player(), pedido.tipo(), pedido.objetivo()));
		ServerPlayNetworking.registerGlobalReceiver(PaquetesTienda.AbrirTienda.TYPE, (x, ctx) -> enviarTienda(ctx.player()));
		ServerPlayNetworking.registerGlobalReceiver(PaquetesTienda.ComprarMinimapa.TYPE, (x, ctx) -> comprarMinimapa(ctx.player()));
		ServerPlayNetworking.registerGlobalReceiver(PaquetesTienda.PonerMarca.TYPE, (x, ctx) -> Marcas.poner(ctx.player()));
	}

	static Precios precios() {
		if (precios == null) precios = Precios.cargar();
		return precios;
	}

	static long ticks() {
		return ticks;
	}

	static @Nullable ServerPlayer jugador(UUID id) {
		return server == null ? null : server.getPlayerList().getPlayer(id);
	}

	static void enviar(ServerPlayer p, CustomPacketPayload paquete) {
		if (ServerPlayNetworking.canSend(p, paquete.type())) ServerPlayNetworking.send(p, paquete);
	}

	static void error(ServerPlayer p, Component mensaje) {
		p.sendSystemMessage(mensaje.copy().withStyle(ChatFormatting.RED));
	}

	// ---------------------------------------------------------------- ciclo

	private static void tick() {
		if (server == null) return;
		ticks++;
		Partida partida = Juego.INSTANCIA.partida();
		if (partida != partidaVista) {
			// Partida nueva (o se cerró la anterior): nada de la anterior sigue vigente; precios recién leídos.
			reiniciar();
			partidaVista = partida;
			if (partida != null) precios = Precios.cargar();
		}
		if (partida == null || partida.fase() != Partida.Fase.JUGANDO) {
			if (Guias.hayEstado() || Marcas.hayEstado() || !minimapaHasta.isEmpty()) reiniciar();
			return;
		}
		Guias.tick(ticks);
		Marcas.tick(ticks);
		if (ticks % 20 == 0) minimapaHasta.values().removeIf(hasta -> hasta <= ticks);
	}

	private static void reiniciar() {
		Guias.reiniciar();
		Marcas.reiniciar();
		for (var e : minimapaHasta.entrySet()) {
			ServerPlayer p = jugador(e.getKey());
			if (p != null && e.getValue() > ticks) enviar(p, new PaquetesAyudas.PermisoMinimapa(0, 0));
		}
		minimapaHasta.clear();
		enfriamientos.clear();
	}

	private static void alEntrar(ServerPlayer p) {
		enviarTienda(p);
		Guias.alEntrar(p);
		Marcas.alEntrar(p);
		Long hasta = minimapaHasta.get(p.getUUID());
		if (hasta != null && hasta > ticks) {
			enviar(p, new PaquetesAyudas.PermisoMinimapa((int) ((hasta - ticks) / 20), precios().minimapaRadio));
		}
	}

	// ---------------------------------------------------------------- reglas comunes

	/** Durante la partida, participante, vivo y en un mundo de la partida. Si no, le explica por qué. */
	static boolean puedeUsar(ServerPlayer p) {
		if (!precios().activas) {
			error(p, Component.translatable("venatio.ayudas.error.apagadas"));
			return false;
		}
		Partida partida = Juego.INSTANCIA.partida();
		if (partida == null || partida.fase() != Partida.Fase.JUGANDO || !partida.participa(p)
			|| p.isSpectator() || !MundosPartida.esDePartida(p.level())) {
			error(p, Component.translatable("venatio.ayudas.error.fuera_de_partida"));
			return false;
		}
		return true;
	}

	static boolean enEnfriamiento(ServerPlayer p, TipoAyuda tipo) {
		long[] e = enfriamientos.get(p.getUUID());
		if (e == null || ticks >= e[tipo.ordinal()]) return false;
		long segundos = (e[tipo.ordinal()] - ticks + 19) / 20;
		error(p, Component.translatable("venatio.ayudas.error.enfriamiento", Component.translatable(tipo.clave()), segundos));
		return true;
	}

	static void ponerEnfriamiento(ServerPlayer p, TipoAyuda tipo) {
		long[] e = enfriamientos.computeIfAbsent(p.getUUID(), k -> new long[TipoAyuda.values().length]);
		e[tipo.ordinal()] = ticks + precios().de(tipo).enfriamiento() * 20L;
	}

	private static int restante(UUID id, TipoAyuda tipo) {
		long[] e = enfriamientos.get(id);
		return e == null ? 0 : (int) Math.max(0, (e[tipo.ordinal()] - ticks + 19) / 20);
	}

	// ---------------------------------------------------------------- tienda y minimapa

	static void enviarTienda(ServerPlayer p) {
		Precios pr = precios();
		List<PaquetesTienda.Oferta> ofertas = new ArrayList<>();
		for (TipoAyuda t : TipoAyuda.values()) {
			Precios.Precio precio = pr.de(t);
			ofertas.add(new PaquetesTienda.Oferta(t.ordinal(), BuiltInRegistries.ITEM.getKey(precio.item()),
				precio.gratis() ? 0 : precio.cantidad(), precio.enfriamiento(), restante(p.getUUID(), t)));
		}
		Long hasta = minimapaHasta.get(p.getUUID());
		int mini = hasta == null ? 0 : (int) Math.max(0, (hasta - ticks) / 20);
		enviar(p, new PaquetesTienda.Tienda(pr.activas, ofertas, pr.minimapaSegundos, pr.minimapaRadio, mini,
			pr.marcaDuracion, pr.marcaMaximo));
	}

	private static void comprarMinimapa(ServerPlayer p) {
		if (!puedeUsar(p) || enEnfriamiento(p, TipoAyuda.MINIMAPA)) return;
		if (!ServerPlayNetworking.canSend(p, PaquetesAyudas.PermisoMinimapa.TYPE)) {
			error(p, Component.translatable("venatio.ayudas.error.sin_minimapa"));
			return;
		}
		Precios pr = precios();
		if (!Cobro.cobrar(p, pr.de(TipoAyuda.MINIMAPA), TipoAyuda.MINIMAPA)) return;
		// Comprar con minimapa activo suma tiempo al que queda.
		long hasta = Math.max(ticks, minimapaHasta.getOrDefault(p.getUUID(), 0L)) + pr.minimapaSegundos * 20L;
		minimapaHasta.put(p.getUUID(), hasta);
		ponerEnfriamiento(p, TipoAyuda.MINIMAPA);
		int segundos = (int) ((hasta - ticks) / 20);
		ServerPlayNetworking.send(p, new PaquetesAyudas.PermisoMinimapa(segundos, pr.minimapaRadio));
		p.sendSystemMessage(Component.translatable("venatio.ayudas.minimapa.comprado",
			String.format("%d:%02d", segundos / 60, segundos % 60), pr.minimapaRadio).withStyle(ChatFormatting.GREEN));
		enviarTienda(p);
	}

	// ---------------------------------------------------------------- consultas (pruebas y otros módulos)

	/** Zona aproximada de la guía activa del jugador (con el desplazamiento que ve el cliente), o null si no tiene. */
	public static @Nullable BlockPos zonaGuia(UUID jugador) {
		return Guias.zonaActiva(jugador);
	}

	public static @Nullable TipoAyuda tipoGuia(UUID jugador) {
		return Guias.tipoActivo(jugador);
	}

	/** true mientras se busca el objetivo de una guía recién pagada. */
	public static boolean buscandoGuia(UUID jugador) {
		return Guias.buscando(jugador);
	}

	public static int marcasDe(ColorEquipo equipo) {
		return Marcas.cuantas(equipo);
	}

	public static int segundosMinimapa(UUID jugador) {
		Long hasta = minimapaHasta.get(jugador);
		return hasta == null ? 0 : (int) Math.max(0, (hasta - ticks) / 20);
	}
}
