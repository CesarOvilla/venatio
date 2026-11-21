package dev.venatio.cliente.ruta;

import com.mojang.blaze3d.platform.NativeImage;
import dev.venatio.ruta.DatosRuta;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.material.MapColor;

/**
 * Terreno explorado de una dimensión en una sola textura: un píxel por muestra (4 bloques), transparente donde no hay
 * dato. Si la zona es enorme se reduce a la mitad las veces que haga falta para no pasar de {@link #LADO_MAXIMO}.
 */
final class TexturaTerreno implements AutoCloseable {
	private static final int LADO_MAXIMO = 4096;

	private final Identifier id;
	private final DynamicTexture textura;
	/** Bloque del mundo en la esquina noroeste de la textura. */
	final int x0, z0;
	final int bloquesPorPixel;
	final int ancho, alto;
	/** Píxeles con color (para las pruebas). */
	final int pintados;

	private TexturaTerreno(Identifier id, DynamicTexture textura, int x0, int z0, int bloquesPorPixel, int ancho, int alto, int pintados) {
		this.id = id;
		this.textura = textura;
		this.x0 = x0;
		this.z0 = z0;
		this.bloquesPorPixel = bloquesPorPixel;
		this.ancho = ancho;
		this.alto = alto;
		this.pintados = pintados;
	}

	static TexturaTerreno crear(DatosRuta.Terreno t, int numero) {
		int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (long c : t.chunks()) {
			int cx = ChunkPos.getX(c), cz = ChunkPos.getZ(c);
			minX = Math.min(minX, cx);
			maxX = Math.max(maxX, cx);
			minZ = Math.min(minZ, cz);
			maxZ = Math.max(maxZ, cz);
		}
		int m = DatosRuta.MUESTRAS_POR_LADO;
		int muestrasX = (maxX - minX + 1) * m, muestrasZ = (maxZ - minZ + 1) * m;
		int reduccion = 1;
		while (muestrasX / reduccion > LADO_MAXIMO || muestrasZ / reduccion > LADO_MAXIMO) reduccion *= 2;
		int ancho = Math.max(1, (muestrasX + reduccion - 1) / reduccion), alto = Math.max(1, (muestrasZ + reduccion - 1) / reduccion);

		NativeImage imagen = new NativeImage(ancho, alto, true);
		byte[] colores = t.colores();
		int pintados = 0;
		for (int k = 0; k < t.chunks().length; k++) {
			int bx = (ChunkPos.getX(t.chunks()[k]) - minX) * m, bz = (ChunkPos.getZ(t.chunks()[k]) - minZ) * m;
			for (int j = 0; j < m; j++) {
				for (int i = 0; i < m; i++) {
					byte b = colores[k * DatosRuta.MUESTRAS_POR_CHUNK + j * m + i];
					if (b == 0) continue;
					int argb = MapColor.getColorFromPackedId(b);
					if (argb == 0) continue;
					imagen.setPixel((bx + i) / reduccion, (bz + j) / reduccion, argb);
					pintados++;
				}
			}
		}
		DynamicTexture textura = new DynamicTexture(() -> "Venatio ruta " + t.dim(), imagen);
		Identifier id = Identifier.fromNamespaceAndPath("venatio", "ruta/terreno_" + numero);
		Minecraft.getInstance().getTextureManager().register(id, textura);
		return new TexturaTerreno(id, textura, minX * 16, minZ * 16, DatosRuta.BLOQUES_POR_MUESTRA * reduccion, ancho, alto, pintados);
	}

	/** Dibuja la textura con su esquina en (x, y) de pantalla y pixelesPorBloque de escala. */
	void dibujar(GuiGraphicsExtractor g, float x, float y, float pixelesPorBloque) {
		float s = pixelesPorBloque * bloquesPorPixel;
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(s, s);
		g.blit(textura.getTextureView(), textura.getSampler(), 0, 0, ancho, alto, 0f, 1f, 0f, 1f);
		g.pose().popMatrix();
	}

	@Override
	public void close() {
		Minecraft.getInstance().getTextureManager().release(id);
	}
}
