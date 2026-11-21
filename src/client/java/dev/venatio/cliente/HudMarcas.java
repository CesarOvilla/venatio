package dev.venatio.cliente;

import dev.venatio.juego.ColorEquipo;
import dev.venatio.red.PaquetesTienda;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Marcas del equipo en el HUD: un rombo con el color del equipo sobre el bloque marcado, con quién la puso y la distancia.
 * Si la marca queda fuera de la pantalla (o detrás), se dibuja en el borde con un triángulo que apunta hacia ella.
 */
final class HudMarcas {
	private static final Identifier ROMBO = Identifier.fromNamespaceAndPath("venatio_ayudas", "textures/gui/marca.png");
	private static final Identifier PUNTA = Identifier.fromNamespaceAndPath("venatio_ayudas", "textures/gui/borde.png");
	private static final int MARGEN = 16;
	// Por encima del mensaje de la barra de acción (alto - 68): la punta y su texto no deben encimarse con él.
	private static final int MARGEN_ABAJO = 86;
	private static final int BLANCO = 0xFFFFFFFF;

	private HudMarcas() {}

	static void dibujar(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (AyudasCliente.marcas.isEmpty() || mc.player == null || mc.level == null) return;
		Camera camara = mc.gameRenderer.mainCamera();
		if (!camara.isInitialized()) return;
		Vec3 ojo = camara.position();
		Matrix4f vistaProyeccion = camara.getViewRotationProjectionMatrix(new Matrix4f());
		int ancho = g.guiWidth(), alto = g.guiHeight();
		Font font = mc.font;
		long ahora = Util.getMillis();

		for (AyudasCliente.MarcaCliente marca : AyudasCliente.marcas) {
			PaquetesTienda.MarcaRed m = marca.red();
			if (!AyudasCliente.enMismaDimension(mc, m.dimension())) continue;
			double px = m.x() + 0.5, py = m.y() + 1.25, pz = m.z() + 0.5;
			Vector4f v = vistaProyeccion.transform(new Vector4f((float) (px - ojo.x), (float) (py - ojo.y), (float) (pz - ojo.z), 1f));
			int metros = (int) Math.round(Math.sqrt(mc.player.distanceToSqr(px, py, pz)));
			ColorEquipo equipo = ColorEquipo.porOrdinal(m.color());
			int color = 0xFF000000 | (equipo == null ? 0xFFFFFF : equipo.rgb);
			// Parpadea los últimos 10 segundos.
			long queda = marca.expira() - ahora;
			if (queda < 10_000 && (ahora / 250) % 2 == 0) color = (color & 0x00FFFFFF) | 0x80000000;

			boolean delante = v.w > 0.05f;
			float nx = delante ? v.x / v.w : 0, ny = delante ? v.y / v.w : 0;
			if (delante && Math.abs(nx) <= 1 && Math.abs(ny) <= 1) {
				int sx = (int) ((nx + 1) / 2 * ancho), sy = (int) ((1 - ny) / 2 * alto);
				g.blit(RenderPipelines.GUI_TEXTURED, ROMBO, sx - 6, sy - 12, 0, 0, 12, 12, 16, 16, 16, 16, color);
				etiqueta(g, font, Component.translatable("venatio.ayudas.hud.marca", m.autor(), metros), sx, sy + 2, ancho);
			} else {
				dibujarEnBorde(g, font, v, ancho, alto, color, Component.translatable("venatio.ayudas.hud.marca", m.autor(), metros));
			}
		}
	}

	/** La marca no se ve: punta en el borde de la pantalla en la dirección en que está, con la distancia. */
	private static void dibujarEnBorde(GuiGraphicsExtractor g, Font font, Vector4f v, int ancho, int alto, int color, Component texto) {
		// x e y del espacio de recorte, sin dividir entre w: conservan el lado (izquierda/derecha, arriba/abajo)
		// también cuando la marca está detrás de la cámara.
		float dx = v.x * ancho / 2f, dy = -v.y * alto / 2f;
		// Detrás: abajo, del lado hacia el que conviene girar (y sin tapar la flecha de la guía, que va arriba).
		if (v.w <= 0.05f) dy = Math.abs(dy) + Math.abs(dx) * 0.6f + 1f;
		float largo = Mth.sqrt(dx * dx + dy * dy);
		if (largo < 1e-3f) {
			dx = 0;
			dy = 1;
			largo = 1;
		}
		dx /= largo;
		dy /= largo;
		// Rectángulo donde van las puntas; abajo deja libre la barra rápida, la vida y la comida.
		float cx = ancho / 2f, cy = alto / 2f;
		float izq = MARGEN, der = ancho - MARGEN, arriba = MARGEN, abajo = alto - MARGEN_ABAJO;
		float t = Float.MAX_VALUE;
		if (dx > 1e-4f) t = Math.min(t, (der - cx) / dx);
		if (dx < -1e-4f) t = Math.min(t, (izq - cx) / dx);
		if (dy > 1e-4f) t = Math.min(t, (abajo - cy) / dy);
		if (dy < -1e-4f) t = Math.min(t, (arriba - cy) / dy);
		float ex = cx + dx * t, ey = cy + dy * t;

		g.pose().pushMatrix();
		g.pose().translate(ex, ey);
		g.pose().rotate((float) Math.atan2(dx, -dy));
		g.blit(RenderPipelines.GUI_TEXTURED, PUNTA, -6, -6, 0, 0, 12, 12, 16, 16, 16, 16, color);
		g.pose().popMatrix();

		// El texto, hacia dentro de la pantalla respecto de la punta, sin taparla.
		int tx = (int) (ex - dx * (font.width(texto) / 2f + 12)), ty = (int) (ey - dy * (font.lineHeight / 2f + 10)) - font.lineHeight / 2;
		etiqueta(g, font, texto, tx, Mth.clamp(ty, 2, alto - font.lineHeight - 2), ancho);
	}

	/** Texto centrado con fondo translúcido, sin salirse de la pantalla. */
	private static void etiqueta(GuiGraphicsExtractor g, Font font, Component texto, int x, int y, int ancho) {
		int medio = font.width(texto) / 2;
		x = Mth.clamp(x, medio + 3, ancho - medio - 3);
		g.fill(x - medio - 2, y - 1, x + medio + 2, y + font.lineHeight, 0x60000000);
		g.centeredText(font, texto, x, y, BLANCO);
	}
}
