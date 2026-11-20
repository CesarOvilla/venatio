package dev.venatio.cliente.localizador;

import dev.venatio.red.PaquetesLocalizador.Localizacion;
import dev.venatio.red.PaquetesLocalizador.PuntoRed;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Barra de localización (cliente): lo que manda el servidor y el reemplazo de la barra de experiencia en el HUD.
 * Solo se usa en el hilo del cliente.
 */
public final class LocalizadorCliente {
	/**
	 * Lo que se dibujó en el último cuadro, para las pruebas. x: centro respecto del centro de la pantalla; flecha: 1 arriba,
	 * -1 abajo, 0 ninguna; lado: -1 / 1 si es una punta de alguien fuera de la vista.
	 */
	public record Dibujado(Tipo tipo, String nombre, int rgb, float x, int tamano, int flecha, boolean punta, int lado) {}

	public enum Tipo { JUGADOR, MARCA, GUIA }

	/** Un jugador seguido: su posición se interpola entre los dos últimos paquetes (llegan cada 10 ticks). */
	static final class Seguido {
		private static final long INTERVALO = 500;
		final UUID uuid;
		String nombre;
		int color;
		boolean companero;
		private Vec3 antes, ahora;
		private long recibido;

		Seguido(PuntoRed p, long ms) {
			this.uuid = p.uuid();
			this.antes = this.ahora = new Vec3(p.x(), p.y(), p.z());
			actualizar(p, ms);
		}

		void actualizar(PuntoRed p, long ms) {
			antes = posicion(ms);
			ahora = new Vec3(p.x(), p.y(), p.z());
			recibido = ms;
			nombre = p.nombre();
			color = p.color();
			companero = p.companero();
		}

		/** Posición de sus ojos interpolada: avanza de la anterior a la última en lo que tarda en llegar el siguiente paquete. */
		Vec3 posicion(long ms) {
			double t = Mth.clamp((ms - recibido) / (double) INTERVALO, 0, 1);
			return antes.lerp(ahora, t);
		}
	}

	private static boolean activa;
	private static Identifier dimension;
	private static final Map<UUID, Seguido> seguidos = new LinkedHashMap<>();

	// Último cuadro dibujado (para pruebas).
	private static boolean visible;
	private static List<Dibujado> dibujados = List.of();
	private static String etiqueta;

	private LocalizadorCliente() {}

	public static void registrar() {
		ClientPlayNetworking.registerGlobalReceiver(Localizacion.TYPE, (l, ctx) -> recibir(l));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> limpiar());
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> limpiar());

		// Va en el lugar de la barra de experiencia, como la vanilla: fondo (INFO_BAR) y puntos (INFO_BAR_FOREGROUND).
		// El número de nivel (EXPERIENCE_LEVEL) es otro elemento y se sigue dibujando.
		HudElementRegistry.replaceElement(VanillaHudElements.INFO_BAR, original -> (g, delta) -> {
			Minecraft mc = Minecraft.getInstance();
			visible = BarraLocalizador.debeMostrarse(mc);
			if (visible) BarraLocalizador.fondo(g);
			else original.extractRenderState(g, delta);
		});
		HudElementRegistry.replaceElement(VanillaHudElements.INFO_BAR_FOREGROUND, original -> (g, delta) -> {
			if (visible) { // lo decidió el fondo, en este mismo cuadro
				BarraLocalizador.puntos(g, delta);
			} else {
				dibujados = List.of();
				etiqueta = null;
				original.extractRenderState(g, delta);
			}
		});
	}

	private static void recibir(Localizacion l) {
		if (!l.activa()) {
			limpiar();
			return;
		}
		activa = true;
		if (!l.dimension().equals(dimension)) seguidos.clear(); // otro mundo: no interpolar desde posiciones de allá
		dimension = l.dimension();
		long ahora = Util.getMillis();
		Set<UUID> vigentes = new LinkedHashSet<>();
		for (PuntoRed p : l.puntos()) {
			vigentes.add(p.uuid());
			Seguido s = seguidos.get(p.uuid());
			if (s == null) seguidos.put(p.uuid(), new Seguido(p, ahora));
			else s.actualizar(p, ahora);
		}
		seguidos.keySet().retainAll(vigentes);
	}

	private static void limpiar() {
		activa = false;
		dimension = null;
		seguidos.clear();
	}

	// ---------------------------------------------------------------- para BarraLocalizador

	static boolean activa() {
		return activa;
	}

	/** Jugadores seguidos si el cliente está en el mismo mundo que la última lista; si no, ninguno. */
	static Collection<Seguido> seguidosEn(Minecraft mc) {
		if (mc.level == null || dimension == null || !mc.level.dimension().identifier().equals(dimension)) return List.of();
		return seguidos.values();
	}

	static void registrarCuadro(List<Dibujado> lista, String texto) {
		dibujados = lista;
		etiqueta = texto;
	}

	// ---------------------------------------------------------------- para pruebas

	/** La barra está encendida por el servidor (durante la partida). */
	public static boolean encendida() {
		return activa;
	}

	/** Nombres de los jugadores de la última lista recibida. */
	public static Set<String> nombres() {
		Set<String> lista = new LinkedHashSet<>();
		for (Seguido s : seguidos.values()) lista.add(s.nombre);
		return lista;
	}

	/** En el último cuadro se dibujó la barra de localización (y no la de experiencia). */
	public static boolean barraVisible() {
		return visible;
	}

	/** Lo que se dibujó en la barra en el último cuadro. */
	public static List<Dibujado> dibujados() {
		return new ArrayList<>(dibujados);
	}

	/** Texto de nombre y distancia que se mostró encima de la barra en el último cuadro, o null. */
	public static String etiqueta() {
		return etiqueta;
	}
}
