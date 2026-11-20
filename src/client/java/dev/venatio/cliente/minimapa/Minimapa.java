package dev.venatio.cliente.minimapa;

import dev.venatio.Venatio;
import dev.venatio.juego.ColorEquipo;
import dev.venatio.red.Paquetes;
import dev.venatio.red.PaquetesAyudas;
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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Holder;
import net.minecraft.data.AtlasIds;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Supplier;

/**
 * Minimapa limitado: solo existe mientras el servidor da permiso (PermisoMinimapa), muestra la superficie que el
 * cliente ya tiene cargada dentro del radio que dice el servidor, sin cuevas ni mobs ni rivales: solo tú y los
 * compañeros de tu equipo. No funciona en el Nether ni en el End. Tecla M para mostrarlo u ocultarlo.
 */
public final class Minimapa {
	/** Lado del mapa en píxeles de interfaz. */
	static final int LADO = 100;
	private static final int MARGEN = 4;
	/** Cada cuántos ticks se dibuja una tanda de chunks en la textura. */
	private static final int TICKS_POR_PASADA = 2;
	private static final int RADIO_MIN = 8, RADIO_MAX = 256;

	private static final int BLANCO = 0xFFFFFFFF;
	private static final int ROJO = 0xFFFF5555;
	private static final int BORDE = 0xFF1E1E1E;
	private static final int MARCO = 0xFF8A7650;
	private static final int FONDO = 0xE0141414;
	private static final int FONDO_AVISO = 0xA0000000;
	private static final Identifier FLECHA = Identifier.withDefaultNamespace("player");

	private static final TexturaMinimapa textura = new TexturaMinimapa();
	private static KeyMapping tecla;
	private static Supplier<Paquetes.Estado> estado = () -> null;

	/** Momento (Util.getMillis) en que vence el permiso; 0 = sin permiso. */
	private static long vigenteHasta;
	private static int radio = 64;
	private static boolean visible;
	private static int faseAnterior = -1;
	private static ClientLevel nivelAnterior;
	private static int ticks;

	private Minimapa() {}

	/** Lo llama VenatioCliente al arrancar: la tecla M ya registrada y de dónde leer el último estado de la partida. */
	public static void registrar(KeyMapping teclaMinimapa, Supplier<Paquetes.Estado> estadoActual) {
		tecla = teclaMinimapa;
		estado = estadoActual;
		ClientPlayNetworking.registerGlobalReceiver(PaquetesAyudas.PermisoMinimapa.TYPE, (p, ctx) -> recibir(p, ctx.client()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> limpiar());
		ClientTickEvents.END_CLIENT_TICK.register(Minimapa::alTick);
		HudElementRegistry.attachElementAfter(VanillaHudElements.MOB_EFFECTS,
			Identifier.fromNamespaceAndPath(Venatio.MOD_ID, "minimapa"), Minimapa::dibujar);
	}

	// ---------------------------------------------------------------- estado

	/** ¿Hay permiso vigente? */
	public static boolean vigente() {
		return vigenteHasta != 0 && Util.getMillis() < vigenteHasta;
	}

	/** ¿Se está mostrando (con permiso y sin ocultar con M)? En el Nether y el End se muestra solo el aviso. */
	public static boolean mostrando() {
		return visible && vigente();
	}

	public static int radio() {
		return radio;
	}

	/** El minimapa solo lee la superficie: no sirve en dimensiones con techo o sin cielo (Nether, End). */
	public static boolean funcionaEn(ClientLevel nivel) {
		Holder<DimensionType> tipo = nivel.dimensionTypeRegistration();
		if (tipo.is(BuiltinDimensionTypes.NETHER) || tipo.is(BuiltinDimensionTypes.END)) return false;
		return tipo.value().hasSkyLight() && !tipo.value().hasCeiling();
	}

	private static int segundosRestantes() {
		return (int) Math.max(0, (vigenteHasta - Util.getMillis() + 999) / 1000);
	}

	private static void recibir(PaquetesAyudas.PermisoMinimapa p, Minecraft mc) {
		if (p.segundos() <= 0) {
			boolean tenia = vigente();
			quitarPermiso();
			if (tenia) aviso(mc, Component.translatable("venatio.minimapa.apagado"));
			return;
		}
		radio = Math.clamp(p.radio(), RADIO_MIN, RADIO_MAX);
		vigenteHasta = Util.getMillis() + p.segundos() * 1000L;
		visible = true;
		aviso(mc, Component.translatable("venatio.minimapa.activado", reloj(p.segundos())));
	}

	private static void quitarPermiso() {
		vigenteHasta = 0;
		visible = false;
	}

	private static void limpiar() {
		quitarPermiso();
		faseAnterior = -1;
		nivelAnterior = null; // la textura se reinicia en el siguiente tick con mundo
	}

	private static void alTick(Minecraft mc) {
		if (mc.player == null || mc.level == null) return;
		while (tecla.consumeClick()) {
			if (vigente()) visible = !visible;
			else aviso(mc, Component.translatable("venatio.minimapa.sin_permiso"));
		}

		// El permiso es de la partida: al volver al lobby se pierde aunque le quede tiempo.
		Paquetes.Estado e = estado.get();
		int fase = e == null ? -1 : e.fase();
		if (fase == Paquetes.Estado.LOBBY && faseAnterior > Paquetes.Estado.LOBBY) quitarPermiso();
		faseAnterior = fase;

		if (vigenteHasta != 0 && !vigente()) {
			quitarPermiso();
			aviso(mc, Component.translatable("venatio.minimapa.expirado"));
		}

		if (mc.level != nivelAnterior) {
			// Otra dimensión (o partida nueva): nada de lo dibujado sirve.
			textura.reiniciar();
			nivelAnterior = mc.level;
		}
		if (mostrando() && funcionaEn(mc.level) && ++ticks % TICKS_POR_PASADA == 0) {
			textura.actualizar(mc.level, mc.player.getX(), mc.player.getZ(), radio);
		}
	}

	private static void aviso(Minecraft mc, Component texto) {
		if (mc.player != null) mc.player.sendOverlayMessage(texto);
	}

	// ---------------------------------------------------------------- dibujo

	private static void dibujar(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!mostrando() || mc.player == null || mc.level == null) return;
		Font font = mc.font;
		int x0 = g.guiWidth() - MARGEN - LADO;
		int y0 = arriba(mc);
		int segundos = segundosRestantes();
		Component tiempo = Component.translatable("venatio.minimapa.restante", reloj(segundos));
		int colorTiempo = segundos <= 10 ? ROJO : BLANCO;

		if (!funcionaEn(mc.level)) {
			List<FormattedCharSequence> lineas = font.split(Component.translatable("venatio.minimapa.dimension"), LADO - 8);
			int alto = (lineas.size() + 1) * (font.lineHeight + 1) + 7;
			g.fill(x0, y0, x0 + LADO, y0 + alto, FONDO_AVISO);
			g.nextStratum();
			int y = y0 + 4;
			for (FormattedCharSequence l : lineas) {
				g.text(font, l, x0 + (LADO - font.width(l)) / 2, y, BLANCO, true);
				y += font.lineHeight + 1;
			}
			g.text(font, tiempo, x0 + (LADO - font.width(tiempo)) / 2, y + 2, colorTiempo, true);
			return;
		}

		float parcial = delta.getGameTimeDeltaPartialTick(false);
		Vec3 yo = mc.player.getPosition(parcial);
		int r = radio;

		g.fill(x0 - 2, y0 - 2, x0 + LADO + 2, y0 + LADO + 2, BORDE);
		g.fill(x0 - 1, y0 - 1, x0 + LADO + 1, y0 + LADO + 1, MARCO);
		g.fill(x0, y0, x0 + LADO, y0 + LADO, FONDO);
		g.nextStratum();
		textura.dibujar(g, x0, y0, LADO, yo.x, yo.z, r);
		g.nextStratum();

		// Compañeros de equipo que el cliente tiene cerca (nunca rivales ni mobs).
		List<String> companeros = companeros();
		if (!companeros.isEmpty()) {
			ColorEquipo c = ColorEquipo.porOrdinal(estado.get().miEquipo());
			int color = 0xFF000000 | (c == null ? 0xFFFFFF : c.rgb);
			float escala = LADO / (2f * r);
			for (AbstractClientPlayer p : mc.level.players()) {
				if (p == mc.player || !companeros.contains(p.getGameProfile().name())) continue;
				Vec3 q = p.getPosition(parcial);
				double dx = q.x - yo.x, dz = q.z - yo.z;
				if (Math.abs(dx) > r || Math.abs(dz) > r) continue;
				int sx = x0 + LADO / 2 + (int) Math.round(dx * escala);
				int sy = y0 + LADO / 2 + (int) Math.round(dz * escala);
				sx = Math.clamp(sx, x0 + 2, x0 + LADO - 3);
				sy = Math.clamp(sy, y0 + 2, y0 + LADO - 3);
				g.fill(sx - 2, sy - 2, sx + 3, sy + 3, 0xFF000000);
				g.fill(sx - 1, sy - 1, sx + 2, sy + 2, color);
			}
		}

		Component norte = Component.translatable("venatio.minimapa.norte");
		g.text(font, norte, x0 + (LADO - font.width(norte)) / 2 + 1, y0 + 2, BLANCO, true);
		g.text(font, tiempo, x0 + LADO - font.width(tiempo), y0 + LADO + 4, colorTiempo, true);

		// Tu flecha al centro, girando con hacia dónde miras (el mapa no gira: el norte siempre arriba).
		TextureAtlasSprite flecha = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.MAP_DECORATIONS).getSprite(FLECHA);
		g.nextStratum();
		g.pose().pushMatrix();
		g.pose().translate(x0 + LADO / 2f, y0 + LADO / 2f);
		g.pose().rotate((float) Math.toRadians(mc.player.getYRot(parcial) + 180f));
		g.pose().translate(-0.5f, 0f); // la flecha del sprite está medio píxel a la derecha de su centro
		g.blitSprite(RenderPipelines.GUI_TEXTURED, flecha, -4, -4, 8, 8);
		g.pose().popMatrix();
	}

	/** Nombres de mi equipo según el último estado; vacío si no estoy en uno. */
	private static List<String> companeros() {
		Paquetes.Estado e = estado.get();
		if (e == null || e.miEquipo() < 0) return List.of();
		for (Paquetes.EquipoRed q : e.equipos()) {
			if (q.color() == e.miEquipo()) return q.miembros();
		}
		return List.of();
	}

	/** Debajo de los iconos de efectos de vanilla (arriba a la derecha): fila de buenos y, si hay, fila de malos. */
	private static int arriba(Minecraft mc) {
		boolean buenos = false, malos = false;
		for (MobEffectInstance i : mc.player.getActiveEffects()) {
			if (!i.showIcon()) continue;
			if (i.getEffect().value().isBeneficial()) buenos = true;
			else malos = true;
		}
		int ocupado = malos ? 1 + 26 + 24 : buenos ? 1 + 24 : 0;
		return ocupado + MARGEN + 2;
	}

	private static String reloj(int segundos) {
		int s = Math.max(0, segundos);
		return String.format("%d:%02d", s / 60, s % 60);
	}
}
