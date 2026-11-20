package dev.venatio.ayudas;

import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Partida;
import dev.venatio.red.PaquetesTienda;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Marcas de equipo (tecla N): un punto que ven solo los compañeros, con duración y tope por equipo. */
final class Marcas {
	record Marca(int id, UUID autor, String nombre, ColorEquipo equipo, ResourceKey<Level> dimension, BlockPos pos, long expira) {}

	private static final Map<ColorEquipo, List<Marca>> porEquipo = new EnumMap<>(ColorEquipo.class);
	private static int siguienteId = 1;

	private Marcas() {}

	static void poner(ServerPlayer p) {
		if (!Ayudas.puedeUsar(p) || Ayudas.enEnfriamiento(p, TipoAyuda.MARCA)) return;
		Precios precios = Ayudas.precios();
		if (!Cobro.cobrar(p, precios.de(TipoAyuda.MARCA), TipoAyuda.MARCA)) return;
		ColorEquipo equipo = Juego.INSTANCIA.partida().equipoDe(p);
		if (equipo == null) return;

		// El bloque que mira (hasta el alcance); si no mira nada, donde está parado.
		HitResult golpe = p.pick(precios.marcaAlcance, 1.0f, false);
		BlockPos pos = golpe.getType() == HitResult.Type.BLOCK && golpe instanceof BlockHitResult b ? b.getBlockPos() : p.blockPosition();

		List<Marca> lista = porEquipo.computeIfAbsent(equipo, k -> new ArrayList<>());
		lista.add(new Marca(siguienteId++, p.getUUID(), p.getGameProfile().name(), equipo, p.level().dimension(), pos,
			Ayudas.ticks() + precios.marcaDuracion * 20L));
		while (lista.size() > precios.marcaMaximo) lista.remove(0);
		Ayudas.ponerEnfriamiento(p, TipoAyuda.MARCA);
		Ayudas.enviarTienda(p);
		enviar(equipo);
		for (ServerPlayer c : companeros(equipo)) {
			c.sendOverlayMessage(c == p ? Component.translatable("venatio.ayudas.marca.puesta", precios.marcaDuracion)
				: Component.translatable("venatio.ayudas.marca.companero", p.getDisplayName()));
		}
	}

	static void tick(long ticks) {
		if (ticks % 20 != 0) return;
		for (var e : porEquipo.entrySet()) {
			if (e.getValue().removeIf(m -> m.expira <= ticks)) enviar(e.getKey());
		}
	}

	private static List<ServerPlayer> companeros(ColorEquipo equipo) {
		List<ServerPlayer> lista = new ArrayList<>();
		Partida partida = Juego.INSTANCIA.partida();
		if (partida == null || Juego.INSTANCIA.server() == null) return lista;
		for (ServerPlayer p : Juego.INSTANCIA.server().getPlayerList().getPlayers()) {
			if (partida.equipoDe(p) == equipo) lista.add(p);
		}
		return lista;
	}

	private static void enviar(ColorEquipo equipo) {
		PaquetesTienda.Marcas paquete = paquete(equipo);
		for (ServerPlayer p : companeros(equipo)) Ayudas.enviar(p, paquete);
	}

	private static PaquetesTienda.Marcas paquete(ColorEquipo equipo) {
		List<PaquetesTienda.MarcaRed> red = new ArrayList<>();
		long ahora = Ayudas.ticks();
		for (Marca m : porEquipo.getOrDefault(equipo, List.of())) {
			red.add(new PaquetesTienda.MarcaRed(m.id, m.nombre, m.equipo.ordinal(), m.dimension.identifier(),
				m.pos.getX(), m.pos.getY(), m.pos.getZ(), (int) Math.max(0, (m.expira - ahora) / 20)));
		}
		return new PaquetesTienda.Marcas(red);
	}

	static void alEntrar(ServerPlayer p) {
		Partida partida = Juego.INSTANCIA.partida();
		ColorEquipo equipo = partida == null ? null : partida.equipoDe(p);
		if (equipo != null && porEquipo.containsKey(equipo)) Ayudas.enviar(p, paquete(equipo));
	}

	static boolean hayEstado() {
		return porEquipo.values().stream().anyMatch(l -> !l.isEmpty());
	}

	static void reiniciar() {
		boolean habia = hayEstado();
		porEquipo.clear();
		if (habia && Juego.INSTANCIA.server() != null) {
			PaquetesTienda.Marcas vacio = new PaquetesTienda.Marcas(List.of());
			for (ServerPlayer p : Juego.INSTANCIA.server().getPlayerList().getPlayers()) Ayudas.enviar(p, vacio);
		}
	}

	static int cuantas(ColorEquipo equipo) {
		return porEquipo.getOrDefault(equipo, List.of()).size();
	}
}
