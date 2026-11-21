package dev.venatio.juego;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cosas que vanilla no cuenta en las estadísticas y que piden algunas casillas de logro: domesticar un animal y terminar
 * de comer algo (el "usado" de vanilla también suma al plantar bayas). Las anotan los mixins de los disparadores de logros.
 * Son contadores desde que arrancó el servidor; cada partida compara contra el valor del principio.
 */
public final class RegistroAcciones {
	public static final String DOMESTICAR = "domesticar";
	private static final Map<UUID, Map<String, Integer>> cuentas = new ConcurrentHashMap<>();

	private RegistroAcciones() {}

	public static void domestico(ServerPlayer p) {
		sumar(p, DOMESTICAR);
	}

	public static void comio(ServerPlayer p, Item item) {
		sumar(p, comida(item));
	}

	static String comida(Item item) {
		return "comer:" + BuiltInRegistries.ITEM.getKey(item);
	}

	static int valor(UUID jugador, String clave) {
		Map<String, Integer> m = cuentas.get(jugador);
		return m == null ? 0 : m.getOrDefault(clave, 0);
	}

	private static void sumar(ServerPlayer p, String clave) {
		cuentas.computeIfAbsent(p.getUUID(), u -> new ConcurrentHashMap<>()).merge(clave, 1, Integer::sum);
	}
}
