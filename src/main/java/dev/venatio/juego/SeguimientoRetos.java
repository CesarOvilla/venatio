package dev.venatio.juego;

import dev.venatio.carton.Reto;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * Detección de las casillas de logro de una partida. Bioma, altura y dimensión salen de la posición; lo demás de las
 * estadísticas del jugador (mobs matados, veces que durmió, peces pescados…) comparando contra el valor que tenían la
 * primera vez que se miró ese logro: en Bingo, al empezar; en Rastreo, cuando el logro pasa a ser el objetivo.
 */
final class SeguimientoRetos {
	/** Logro → cómo leer cada uno de sus contadores en un jugador. */
	private static final Map<String, List<ToIntFunction<ServerPlayer>>> CONTADORES = new HashMap<>();

	private final Partida partida;
	/** Jugador → "logro|contador" → valor la primera vez que se miró. */
	private final Map<UUID, Map<String, Integer>> bases = new HashMap<>();

	SeguimientoRetos(Partida partida) {
		this.partida = partida;
	}

	boolean cumple(ServerPlayer p, Reto r) {
		return switch (r.tipo()) {
			case BIOMA -> {
				var bioma = p.level().getBiome(p.blockPosition());
				for (Identifier id : r.objetivos()) if (bioma.is(id)) yield true;
				yield false;
			}
			case ALTURA -> r.umbral() >= 0 ? p.getY() >= r.umbral() : p.getY() <= r.umbral();
			case NETHER -> p.level() == partida.mundos().nether();
			// La estadística se suma al acostarse; lo de estar dormido cubre la cama que no da estadística.
			case DORMIR -> avance(p, r) >= 1 || p.isSleeping();
			default -> avance(p, r) >= r.umbral();
		};
	}

	/** El logro se cumplió: la próxima vez que salga (Rastreo), esos jugadores empiezan de cero. */
	void olvidar(Reto r, Collection<ServerPlayer> jugadores) {
		for (ServerPlayer p : jugadores) {
			Map<String, Integer> base = bases.get(p.getUUID());
			if (base != null) base.keySet().removeIf(k -> k.startsWith(r.id() + "|"));
		}
	}

	/** Cuánto sumó el jugador en los contadores del logro desde que se miró por primera vez. */
	private int avance(ServerPlayer p, Reto r) {
		Map<String, Integer> base = bases.computeIfAbsent(p.getUUID(), u -> new HashMap<>());
		List<ToIntFunction<ServerPlayer>> contadores = CONTADORES.computeIfAbsent(r.id(), k -> contadores(r));
		int suma = 0;
		for (int i = 0; i < contadores.size(); i++) {
			int v = contadores.get(i).applyAsInt(p);
			suma += v - base.computeIfAbsent(r.id() + "|" + i, k -> v);
		}
		return suma;
	}

	private static List<ToIntFunction<ServerPlayer>> contadores(Reto r) {
		List<ToIntFunction<ServerPlayer>> lista = new ArrayList<>();
		switch (r.tipo()) {
			case MATAR -> {
				for (Identifier id : r.objetivos()) {
					BuiltInRegistries.ENTITY_TYPE.getOptional(id).ifPresent(t -> lista.add(estadistica(Stats.ENTITY_KILLED.get(t))));
				}
			}
			case MINAR -> {
				for (Identifier id : r.objetivos()) {
					BuiltInRegistries.BLOCK.getOptional(id).ifPresent(b -> lista.add(estadistica(Stats.BLOCK_MINED.get(b))));
				}
			}
			case ESTADISTICA, DORMIR -> {
				for (Identifier id : r.objetivos()) {
					// Stats.CUSTOM busca por identidad: hay que usar el Identifier registrado, no uno igual.
					BuiltInRegistries.CUSTOM_STAT.getOptional(id).ifPresent(reg -> lista.add(estadistica(Stats.CUSTOM.get(reg))));
				}
			}
			case COMER -> {
				for (Identifier id : r.objetivos()) {
					BuiltInRegistries.ITEM.getOptional(id).ifPresent(item -> {
						String clave = RegistroAcciones.comida(item);
						lista.add(p -> RegistroAcciones.valor(p.getUUID(), clave));
					});
				}
			}
			case DOMESTICAR -> lista.add(p -> RegistroAcciones.valor(p.getUUID(), RegistroAcciones.DOMESTICAR));
			default -> {}
		}
		return List.copyOf(lista);
	}

	private static ToIntFunction<ServerPlayer> estadistica(Stat<?> stat) {
		return p -> p.getStats().getValue(stat);
	}
}
