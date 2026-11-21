package dev.venatio.cliente;

import com.mojang.blaze3d.platform.InputConstants;
import dev.venatio.Venatio;
import dev.venatio.juego.ColorEquipo;
import dev.venatio.red.PaquetesRuta;
import dev.venatio.red.PaquetesRuta.AccionEspectador;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.UUID;

/**
 * Cliente del modo espectador: HUD con a quién se mira y teclas para cambiar de jugador (← →), de equipo (↑ ↓) y
 * dejar la vista libre (Espacio). El servidor decide a quién se sigue y pone la cámara (setCamera vanilla).
 */
public final class EspectadorCliente {
	private static final int ALTO = 44;

	private static boolean activo, libre;
	private static UUID jugador;
	private static String nombre = "";
	private static int equipo = -1;
	private static KeyMapping anterior, siguiente, equipoAnterior, equipoSiguiente;
	private static boolean saltoAntes;

	private EspectadorCliente() {}

	static void registrar() {
		anterior = VenatioCliente.tecla("espectador_anterior", InputConstants.KEY_LEFT);
		siguiente = VenatioCliente.tecla("espectador_siguiente", InputConstants.KEY_RIGHT);
		equipoAnterior = VenatioCliente.tecla("espectador_equipo_anterior", InputConstants.KEY_UP);
		equipoSiguiente = VenatioCliente.tecla("espectador_equipo_siguiente", InputConstants.KEY_DOWN);
		ClientPlayNetworking.registerGlobalReceiver(PaquetesRuta.EstadoEspectador.TYPE, (e, ctx) -> recibir(e));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(() -> recibir(PaquetesRuta.EstadoEspectador.INACTIVO)));
		ClientTickEvents.END_CLIENT_TICK.register(EspectadorCliente::alTick);
		HudElementRegistry.attachElementAfter(VanillaHudElements.SPECTATOR_TOOLTIP,
			Identifier.fromNamespaceAndPath(Venatio.MOD_ID, "espectador"), EspectadorCliente::dibujar);
	}

	private static void recibir(PaquetesRuta.EstadoEspectador e) {
		activo = e.activo();
		libre = e.libre();
		jugador = e.jugador();
		nombre = e.nombre();
		equipo = e.equipo();
	}

	// ---------------------------------------------------------------- consultas

	/** ¿Este cliente está mirando la partida como espectador? */
	public static boolean activo() {
		return activo;
	}

	public static boolean libre() {
		return libre;
	}

	/** Nombre de a quién sigue (o siguió por última vez). */
	public static String nombre() {
		return nombre;
	}

	/** Equipo cuyo cartón se ve en el HUD y al abrir la B: el del jugador que se mira; si no, el mío. */
	static int equipoVisto(int miEquipo) {
		return activo && equipo >= 0 ? equipo : miEquipo;
	}

	// ---------------------------------------------------------------- teclas

	private static void alTick(Minecraft mc) {
		boolean salto = mc.options.keyJump.isDown();
		boolean pulsoSalto = salto && !saltoAntes;
		saltoAntes = salto;
		boolean libreDeMenus = mc.player != null && mc.gui.screen() == null;
		pulsar(siguiente, AccionEspectador.SIGUIENTE, libreDeMenus);
		pulsar(anterior, AccionEspectador.ANTERIOR, libreDeMenus);
		pulsar(equipoSiguiente, AccionEspectador.EQUIPO_SIGUIENTE, libreDeMenus);
		pulsar(equipoAnterior, AccionEspectador.EQUIPO_ANTERIOR, libreDeMenus);
		if (activo && !libre && pulsoSalto && libreDeMenus) enviar(AccionEspectador.LIBRE);
	}

	/** Consume los clics de la tecla (siempre, para que no se acumulen fuera del modo espectador) y avisa al servidor. */
	private static void pulsar(KeyMapping tecla, int accion, boolean libreDeMenus) {
		boolean pulsada = false;
		while (tecla.consumeClick()) pulsada = true;
		if (pulsada && activo && libreDeMenus) enviar(accion);
	}

	private static void enviar(int accion) {
		if (ClientPlayNetworking.canSend(AccionEspectador.TYPE)) ClientPlayNetworking.send(new AccionEspectador(accion));
	}

	// ---------------------------------------------------------------- HUD

	/** Nombre corto de una tecla: las flechas por defecto se muestran como flechas. */
	private static Component tecla(KeyMapping k, String flecha) {
		return k.isDefault() ? Component.literal(flecha) : k.getTranslatedKeyMessage();
	}

	private static void dibujar(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!activo || mc.player == null) return;
		Font font = mc.font;
		ColorEquipo c = ColorEquipo.porOrdinal(equipo);
		Component titulo = libre ? Component.translatable("venatio.espectador.hud.libre")
			: Component.translatable("venatio.espectador.hud.mirando", Component.literal(nombre).withColor(c == null ? Arte.CREMA : c.rgb),
				c == null ? Component.literal("?") : c.nombre());
		Component teclas = libre
			? Component.translatable("venatio.espectador.hud.teclas_libre", tecla(anterior, "←"), tecla(siguiente, "→"))
			: Component.translatable("venatio.espectador.hud.teclas", tecla(anterior, "←"), tecla(siguiente, "→"),
				tecla(equipoAnterior, "↑"), tecla(equipoSiguiente, "↓"), mc.options.keyJump.isDefault()
					? Component.translatable("venatio.espectador.hud.espacio") : mc.options.keyJump.getTranslatedKeyMessage());
		Component extra = Component.translatable("venatio.espectador.hud.extra",
			VenatioCliente.teclaCarton().getTranslatedKeyMessage(), VenatioCliente.teclaRuta().getTranslatedKeyMessage());

		boolean conCara = !libre && jugador != null;
		int sangria = conCara ? 22 : 0;
		int ancho = Math.max(sangria + font.width(titulo), Math.max(font.width(teclas), font.width(extra))) + 12;
		// Abajo a la derecha: abajo a la izquierda está el chat, y en modo espectador no hay barra de items.
		int x = g.guiWidth() - ancho - 4;
		int y = g.guiHeight() - ALTO - 4;
		Arte.fila(g, x, y, ancho, ALTO, false);
		if (conCara) {
			int borde = 0xFF000000 | (c == null ? 0x808080 : c.rgb);
			g.fill(x + 5, y + 3, x + 5 + 14, y + 3 + 14, borde);
			PlayerFaceExtractor.extractRenderState(g, piel(mc, jugador), x + 6, y + 4, 12);
		}
		g.text(font, titulo, x + 6 + sangria, y + 7, Arte.CREMA, true);
		g.text(font, teclas, x + 6, y + 21, Arte.GRIS, true);
		g.text(font, extra, x + 6, y + 32, Arte.GRIS_OSCURO, true);
	}

	static PlayerSkin piel(Minecraft mc, UUID uuid) {
		PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(uuid);
		return info != null ? info.getSkin() : DefaultPlayerSkin.get(uuid);
	}
}
