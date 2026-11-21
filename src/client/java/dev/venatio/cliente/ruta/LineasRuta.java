package dev.venatio.cliente.ruta;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;

import java.util.Arrays;

/**
 * Todas las líneas del mapa de la ruta como un solo elemento de la interfaz: cada tramo es un cuadrilátero girado
 * (el fill de la interfaz solo hace rectángulos rectos). Ir en un solo elemento importa: cada fill que se enciman
 * abre una capa nueva al dibujar, y una ruta tiene miles de tramos.
 */
final class LineasRuta {
	private float[] xy = new float[8 * 256];
	private int[] color = new int[256];
	private int n;

	void limpiar() {
		n = 0;
	}

	int cantidad() {
		return n;
	}

	/** Tramo de (ax, ay) a (bx, by) con el grosor dado, en coordenadas de interfaz. */
	void tramo(float ax, float ay, float bx, float by, float grosor, int argb) {
		float dx = bx - ax, dy = by - ay;
		float largo = (float) Math.sqrt(dx * dx + dy * dy);
		if (largo < 0.01f) return;
		float nx = -dy / largo * grosor / 2, ny = dx / largo * grosor / 2;
		// Mismo sentido de giro que los rectángulos de la interfaz.
		agregar(bx + nx, by + ny, bx - nx, by - ny, ax - nx, ay - ny, ax + nx, ay + ny, argb);
	}

	/** Cuadrado centrado en (x, y): tapa las juntas entre tramos. */
	void punto(float x, float y, float lado, int argb) {
		float h = lado / 2;
		agregar(x + h, y + h, x + h, y - h, x - h, y - h, x - h, y + h, argb);
	}

	private void agregar(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, int argb) {
		if (n == color.length) {
			color = Arrays.copyOf(color, n * 2);
			xy = Arrays.copyOf(xy, n * 16);
		}
		int i = n * 8;
		xy[i] = x0;
		xy[i + 1] = y0;
		xy[i + 2] = x1;
		xy[i + 3] = y1;
		xy[i + 4] = x2;
		xy[i + 5] = y2;
		xy[i + 6] = x3;
		xy[i + 7] = y3;
		color[n++] = argb;
	}

	/** Lo que se entrega a la interfaz para este cuadro (copia: el estado de dibujo vive hasta el final del cuadro). */
	GuiElementRenderState estado(ScreenRectangle zona) {
		return new Estado(Arrays.copyOf(xy, n * 8), Arrays.copyOf(color, n), zona);
	}

	private record Estado(float[] xy, int[] color, ScreenRectangle zona) implements GuiElementRenderState {
		@Override
		public void buildVertices(VertexConsumer v) {
			for (int q = 0; q < color.length; q++) {
				int i = q * 8;
				for (int k = 0; k < 8; k += 2) v.addVertex(xy[i + k], xy[i + k + 1], 0f).setColor(color[q]);
			}
		}

		@Override
		public RenderPipeline pipeline() {
			return RenderPipelines.GUI;
		}

		@Override
		public TextureSetup textureSetup() {
			return TextureSetup.noTexture();
		}

		@Override
		public ScreenRectangle scissorArea() {
			return zona;
		}

		@Override
		public ScreenRectangle bounds() {
			return zona;
		}
	}
}
