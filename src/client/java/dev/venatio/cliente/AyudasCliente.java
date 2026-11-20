package dev.venatio.cliente;

import dev.venatio.Venatio;
import dev.venatio.red.Paquetes;
import dev.venatio.red.PaquetesTienda;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Cliente del módulo de ayudas: estado recibido del servidor (tienda, guía, marcas), HUD, partículas y teclas H/N. */
public final class AyudasCliente {
	/** Una marca con su vencimiento en el reloj del cliente (ms). */
	record MarcaCliente(PaquetesTienda.MarcaRed red, long expira) {}

	static PaquetesTienda.Tienda tienda;
	private static long tiendaRecibida;
	static PaquetesTienda.Guia guia;
	private static long guiaRecibida;
	static List<MarcaCliente> marcas = List.of();
	private static final Set<Integer> marcasVistas = new HashSet<>();
	private static int ticks;

	private AyudasCliente() {}

	static void registrar() {
		ClientPlayNetworking.registerGlobalReceiver(PaquetesTienda.Tienda.TYPE, (t, ctx) -> {
			tienda = t;
			tiendaRecibida = Util.getMillis();
		});
		ClientPlayNetworking.registerGlobalReceiver(PaquetesTienda.Guia.TYPE, (g, ctx) -> recibirGuia(g, ctx.client()));
		ClientPlayNetworking.registerGlobalReceiver(PaquetesTienda.GuiaTerminada.TYPE, (t, ctx) -> {
			guia = null;
			if (t.motivo() == PaquetesTienda.GuiaTerminada.LLEGADA) sonido(ctx.client(), 1.2f);
		});
		ClientPlayNetworking.registerGlobalReceiver(PaquetesTienda.Marcas.TYPE, (m, ctx) -> recibirMarcas(m, ctx.client()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> limpiar());

		HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS,
			Identifier.fromNamespaceAndPath(Venatio.MOD_ID, "ayudas_marcas"), HudMarcas::dibujar);
		HudElementRegistry.attachElementAfter(VanillaHudElements.BOSS_BAR,
			Identifier.fromNamespaceAndPath(Venatio.MOD_ID, "ayudas_guia"), HudGuia::dibujar);
		ClientTickEvents.END_CLIENT_TICK.register(AyudasCliente::alTick);
	}

	private static void limpiar() {
		tienda = null;
		guia = null;
		marcas = List.of();
		marcasVistas.clear();
	}

	// ---------------------------------------------------------------- teclas

	/** Tecla H. Pide al servidor los enfriamientos al día y abre la tienda. */
	static void abrirTienda(Minecraft mc) {
		ClientPlayNetworking.send(PaquetesTienda.AbrirTienda.INSTANCIA);
		mc.gui.setScreen(new PantallaTienda());
	}

	/** Tecla N. El servidor calcula qué bloque mira el jugador. */
	static void ponerMarca() {
		ClientPlayNetworking.send(PaquetesTienda.PonerMarca.INSTANCIA);
	}

	// ---------------------------------------------------------------- red

	private static void recibirGuia(PaquetesTienda.Guia g, Minecraft mc) {
		boolean nueva = guia == null || guia.tipo() != g.tipo() || !guia.objetivo().equals(g.objetivo());
		guia = g;
		guiaRecibida = Util.getMillis();
		if (nueva) sonido(mc, 0.8f);
	}

	private static void recibirMarcas(PaquetesTienda.Marcas m, Minecraft mc) {
		long ahora = Util.getMillis();
		List<MarcaCliente> nuevas = new ArrayList<>();
		boolean hayNueva = false;
		for (PaquetesTienda.MarcaRed r : m.marcas()) {
			nuevas.add(new MarcaCliente(r, ahora + r.segundos() * 1000L));
			if (marcasVistas.add(r.id())) hayNueva = true;
		}
		marcas = nuevas;
		if (hayNueva) mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, 1.6f));
	}

	private static void sonido(Minecraft mc, float tono) {
		mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, tono));
	}

	// ---------------------------------------------------------------- consultas para pantallas y HUD

	/** Segundos que le faltan a una ayuda para volver a estar disponible (descontando lo que pasó desde que llegó la tienda). */
	static int enfriamiento(PaquetesTienda.Oferta o) {
		long pasados = (Util.getMillis() - tiendaRecibida) / 1000;
		return (int) Math.max(0, o.restante() - pasados);
	}

	static int minimapaRestante() {
		if (tienda == null) return 0;
		long pasados = (Util.getMillis() - tiendaRecibida) / 1000;
		return (int) Math.max(0, tienda.minimapaRestante() - pasados);
	}

	static int segundosGuia() {
		if (guia == null) return 0;
		return (int) Math.max(0, guia.segundos() - (Util.getMillis() - guiaRecibida) / 1000);
	}

	/** Objetivo de la guía que muestra el HUD, o null (para pruebas). */
	public static Identifier objetivoGuia() {
		return guia == null ? null : guia.objetivo();
	}

	/** Marcas del equipo que muestra el HUD (para pruebas). */
	public static int marcasVisibles() {
		return marcas.size();
	}

	/** Marcas vigentes del equipo (las usa la barra de localización). */
	public static List<PaquetesTienda.MarcaRed> marcasActuales() {
		List<PaquetesTienda.MarcaRed> lista = new ArrayList<>(marcas.size());
		for (MarcaCliente m : marcas) lista.add(m.red());
		return lista;
	}

	/** Guía activa, o null (la usa la barra de localización). */
	public static PaquetesTienda.Guia guiaActual() {
		return guia;
	}

	/** Distancia a la guía en rangos (cerca / media / lejos), igual que la flecha: la guía nunca da metros. */
	public static Component rangoGuia(double distancia) {
		return HudGuia.rango(distancia);
	}

	/** Llegó la tienda del servidor (para pruebas). */
	public static boolean hayTienda() {
		return tienda != null;
	}

	static boolean jugando() {
		return EstadoCliente.estado != null && EstadoCliente.estado.fase() == Paquetes.Estado.JUGANDO;
	}

	static int contar(Minecraft mc, Item item) {
		if (mc.player == null) return 0;
		Inventory inv = mc.player.getInventory();
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).is(item)) n += inv.getItem(i).getCount();
		}
		return n;
	}

	static boolean enMismaDimension(Minecraft mc, Identifier dimension) {
		return mc.level != null && mc.level.dimension().identifier().equals(dimension);
	}

	// ---------------------------------------------------------------- tick

	private static void alTick(Minecraft mc) {
		ticks++;
		if (!marcas.isEmpty()) {
			long ahora = Util.getMillis();
			if (marcas.stream().anyMatch(m -> m.expira <= ahora)) {
				marcas = marcas.stream().filter(m -> m.expira > ahora).toList();
			}
		}
		if (ticks % 8 == 0) rastro(mc);
	}

	/** Rastro de partículas (solo del cliente) unos bloques por delante del jugador, hacia la zona de la guía. */
	private static void rastro(Minecraft mc) {
		PaquetesTienda.Guia g = guia;
		if (g == null || mc.player == null || mc.level == null || mc.gui.screen() != null || !enMismaDimension(mc, g.dimension())) return;
		Vec3 pos = mc.player.position();
		double dx = g.x() + 0.5 - pos.x, dz = g.z() + 0.5 - pos.z;
		double dist = Math.sqrt(dx * dx + dz * dz);
		if (dist < g.radio()) return;
		dx /= dist;
		dz /= dist;
		double y = pos.y + 0.9;
		for (int i = 2; i <= 7; i++) {
			double t = i + (ticks % 16) / 8.0;
			mc.level.addParticle(ParticleTypes.END_ROD, pos.x + dx * t, y, pos.z + dz * t, 0, 0.005, 0);
		}
	}
}
