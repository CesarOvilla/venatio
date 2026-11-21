package dev.venatio.cliente.ruta;

import com.mojang.blaze3d.platform.NativeImage;
import dev.venatio.ruta.DatosRuta;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.material.MapColor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

/**
 * Terreno explorado de una dimensión en mosaicos de 512 × 512 bloques (128 × 128 píxeles, uno cada 4 bloques),
 * transparentes donde no hay dato. Solo existen los mosaicos con algo: si alguien se fue a 5000 bloques del resto no
 * hay una textura enorme y vacía entre medio, y se puede acercar a cualquier zona con la misma resolución.
 */
final class TexturaTerreno implements AutoCloseable {
	private static final int CHUNKS_POR_MOSAICO = 32;
	private static final int PIXELES = DatosRuta.MUESTRAS_POR_LADO * CHUNKS_POR_MOSAICO;
	private static final int BLOQUES = CHUNKS_POR_MOSAICO * 16;

	private record Mosaico(Identifier id, DynamicTexture textura, int x0, int z0) {}

	private final List<Mosaico> mosaicos;
	/** Píxeles con color (para las pruebas). */
	final int pintados;

	private TexturaTerreno(List<Mosaico> mosaicos, int pintados) {
		this.mosaicos = mosaicos;
		this.pintados = pintados;
	}

	static TexturaTerreno crear(DatosRuta.Terreno t, int numero) {
		Map<Long, NativeImage> imagenes = new HashMap<>();
		int m = t.muestras(), lado = DatosRuta.MUESTRAS_POR_LADO / m;
		byte[] colores = t.colores();
		int pintados = 0;
		for (int k = 0; k < t.chunks().length; k++) {
			int cx = ChunkPos.getX(t.chunks()[k]), cz = ChunkPos.getZ(t.chunks()[k]);
			int mx = Math.floorDiv(cx, CHUNKS_POR_MOSAICO), mz = Math.floorDiv(cz, CHUNKS_POR_MOSAICO);
			NativeImage imagen = imagenes.computeIfAbsent(ChunkPos.pack(mx, mz), c -> new NativeImage(PIXELES, PIXELES, true));
			int px = (cx - mx * CHUNKS_POR_MOSAICO) * DatosRuta.MUESTRAS_POR_LADO, pz = (cz - mz * CHUNKS_POR_MOSAICO) * DatosRuta.MUESTRAS_POR_LADO;
			for (int j = 0; j < m; j++) {
				for (int i = 0; i < m; i++) {
					byte b = colores[k * m * m + j * m + i];
					int argb = b == 0 ? 0 : MapColor.getColorFromPackedId(b);
					if (argb == 0) continue;
					// Con menos resolución (partidas con mucho terreno) cada color cubre varios píxeles.
					for (int dz = 0; dz < lado; dz++) {
						for (int dx = 0; dx < lado; dx++) imagen.setPixel(px + i * lado + dx, pz + j * lado + dz, argb);
					}
					pintados += lado * lado;
				}
			}
		}
		List<Mosaico> mosaicos = new ArrayList<>();
		for (Map.Entry<Long, NativeImage> e : imagenes.entrySet()) {
			Identifier id = Identifier.fromNamespaceAndPath("venatio", "ruta/terreno_" + numero + "_" + mosaicos.size());
			DynamicTexture textura = new DynamicTexture(() -> "Venatio ruta " + t.dim(), e.getValue());
			Minecraft.getInstance().getTextureManager().register(id, textura);
			mosaicos.add(new Mosaico(id, textura, ChunkPos.getX(e.getKey()) * BLOQUES, ChunkPos.getZ(e.getKey()) * BLOQUES));
		}
		return new TexturaTerreno(List.copyOf(mosaicos), pintados);
	}

	int mosaicos() {
		return mosaicos.size();
	}

	/**
	 * Dibuja los mosaicos que caen dentro de [x0, x1) × [y0, y1) de pantalla. sx y sz pasan de coordenadas del mundo a
	 * pantalla; pixelesPorBloque es la escala. Devuelve cuántos dibujó.
	 */
	int dibujar(GuiGraphicsExtractor g, DoubleUnaryOperator sx, DoubleUnaryOperator sz, float pixelesPorBloque, int x0, int y0, int x1, int y1) {
		float lado = BLOQUES * pixelesPorBloque;
		float escala = pixelesPorBloque * BLOQUES / PIXELES;
		int dibujados = 0;
		for (Mosaico mo : mosaicos) {
			float x = (float) sx.applyAsDouble(mo.x0), y = (float) sz.applyAsDouble(mo.z0);
			if (x > x1 || y > y1 || x + lado < x0 || y + lado < y0) continue;
			g.pose().pushMatrix();
			g.pose().translate(x, y);
			g.pose().scale(escala, escala);
			g.blit(mo.textura.getTextureView(), mo.textura.getSampler(), 0, 0, PIXELES, PIXELES, 0f, 1f, 0f, 1f);
			g.pose().popMatrix();
			dibujados++;
		}
		return dibujados;
	}

	@Override
	public void close() {
		for (Mosaico mo : mosaicos) Minecraft.getInstance().getTextureManager().release(mo.id);
	}
}
