package dev.venatio.cliente;

import dev.venatio.ayudas.NombresObjetivo;
import dev.venatio.red.PaquetesTienda;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Flecha de la guía arriba al centro (debajo de las barras de jefe): apunta a la zona según hacia dónde mira el jugador.
 * Debajo, el objetivo y la distancia por rangos (cerca / media / lejos), sin coordenadas.
 */
final class HudGuia {
	private static final Identifier FLECHA = Identifier.fromNamespaceAndPath("venatio_ayudas", "textures/gui/flecha.png");
	private static final int TAM = 24;
	private static final int BLANCO = 0xFFFFFFFF;
	private static final int GRIS = 0xFFAAAAAA;
	static final int CERCA = 150, MEDIA = 600;

	private static Field eventosJefe;
	private static boolean sinEventosJefe;

	private HudGuia() {}

	static void dibujar(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		PaquetesTienda.Guia guia = AyudasCliente.guia;
		if (guia == null || mc.player == null || mc.level == null) return;
		Font font = mc.font;
		int cx = g.guiWidth() / 2;
		int y = arribaLibre(mc, g.guiHeight());
		Component nombre = NombresObjetivo.de(guia.tipo(), guia.objetivo());

		if (!AyudasCliente.enMismaDimension(mc, guia.dimension())) {
			Component aviso = Component.translatable("venatio.ayudas.hud.otra_dimension", nombre);
			int medio = font.width(aviso) / 2 + 3;
			g.fill(cx - medio, y, cx + medio, y + font.lineHeight + 3, 0x70000000);
			g.centeredText(font, aviso, cx, y + 2, GRIS);
			return;
		}

		Vec3 pos = mc.player.getPosition(delta.getGameTimeDeltaPartialTick(true));
		double dx = guia.x() + 0.5 - pos.x, dz = guia.z() + 0.5 - pos.z;
		double dist = Math.sqrt(dx * dx + dz * dz);
		boolean enZona = dist <= guia.radio();
		// Yaw de Minecraft: 0 = sur (+Z), 90 = oeste (−X). Positivo = a la derecha de donde mira.
		float rumbo = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float relativo = Mth.wrapDegrees(rumbo - mc.player.getViewYRot(delta.getGameTimeDeltaPartialTick(true)));

		int color = enZona ? 0xFF55FF55 : colorRango(dist);
		float escala = enZona ? 1f + 0.08f * Mth.sin(Util.getMillis() / 150f) : 1f;
		g.pose().pushMatrix();
		g.pose().translate(cx, y + TAM / 2f);
		g.pose().rotate((float) Math.toRadians(relativo));
		g.pose().scale(escala, escala);
		g.blit(RenderPipelines.GUI_TEXTURED, FLECHA, -TAM / 2, -TAM / 2, 0, 0, TAM, TAM, 32, 32, 32, 32, color);
		g.pose().popMatrix();

		int yTexto = y + TAM + 3;
		Component linea = enZona
			? Component.translatable("venatio.ayudas.hud.en_zona", nombre)
			: Component.translatable("venatio.ayudas.hud.guia", nombre, rango(dist).copy().withColor(color));
		Component reloj = Component.literal(EstadoCliente.reloj(AyudasCliente.segundosGuia()));
		fondoTexto(g, font, linea, cx, yTexto);
		g.centeredText(font, linea, cx, yTexto, BLANCO);
		g.centeredText(font, reloj, cx, yTexto + font.lineHeight + 1, GRIS);
	}

	/** Fondo translúcido detrás de las dos líneas para que se lean sobre el cielo o la nieve. */
	private static void fondoTexto(GuiGraphicsExtractor g, Font font, Component linea, int cx, int y) {
		int medio = font.width(linea) / 2 + 3;
		g.fill(cx - medio, y - 2, cx + medio, y + 2 * font.lineHeight + 2, 0x70000000);
	}

	static Component rango(double dist) {
		return Component.translatable(dist < CERCA ? "venatio.ayudas.rango.cerca" : dist < MEDIA ? "venatio.ayudas.rango.media" : "venatio.ayudas.rango.lejos");
	}

	private static int colorRango(double dist) {
		return dist < CERCA ? 0xFF7CFF7C : dist < MEDIA ? 0xFFFFE066 : 0xFFFF8A5C;
	}

	/** Primera altura libre debajo de las barras de jefe (vanilla: 12 + 19 por barra, hasta un tercio de la pantalla). */
	private static int arribaLibre(Minecraft mc, int alto) {
		int barras = barrasDeJefe(mc);
		return barras == 0 ? 4 : Math.min(19 * barras + 2, alto / 3 + 4);
	}

	/** Cuántas barras de jefe hay. BossHealthOverlay no lo expone; se lee su mapa privado y, si falla, se asume que ninguna. */
	private static int barrasDeJefe(Minecraft mc) {
		if (sinEventosJefe) return 0;
		try {
			if (eventosJefe == null) {
				eventosJefe = BossHealthOverlay.class.getDeclaredField("events");
				eventosJefe.setAccessible(true);
			}
			return ((Map<?, ?>) eventosJefe.get(mc.gui.hud.getBossOverlay())).size();
		} catch (ReflectiveOperationException | RuntimeException e) {
			sinEventosJefe = true;
			return 0;
		}
	}
}
