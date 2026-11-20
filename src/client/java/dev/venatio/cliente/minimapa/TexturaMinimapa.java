package dev.venatio.cliente.minimapa;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;

import java.util.Arrays;

/**
 * Terreno del minimapa en una textura de GPU que se repite (toroidal): la columna (x, z) del mundo vive siempre en el
 * píxel (x mod lado, z mod lado). Así, al caminar, solo hay que dibujar los chunks que entran al borde; el resto se
 * queda donde está y el dibujo solo corre las coordenadas de textura.
 * <p>
 * Cada ranura de 16×16 guarda un chunk. Cuando a una ranura le toca otro chunk se limpia y se dibuja de nuevo; los
 * chunks ya dibujados se repasan de pocos en pocos para recoger bloques puestos o rotos. Todo se sube por parches de
 * 16×16, nunca la textura completa.
 */
final class TexturaMinimapa {
	private static final Identifier ID = Identifier.fromNamespaceAndPath("venatio", "minimapa");
	private static final long SIN_CHUNK = Long.MIN_VALUE;
	/** Chunks nuevos que se dibujan por pasada (los más cercanos primero). */
	private static final int NUEVOS_POR_PASADA = 12;
	/** Chunks ya dibujados que se repasan por pasada. */
	private static final int REPASOS_POR_PASADA = 3;
	private static final int PROFUNDIDAD_MAXIMA_AGUA = 12;

	private DynamicTexture textura;
	/** Chunks por lado de la textura. */
	private int n;
	private long[] chunkEnRanura;
	private boolean[] dibujada;
	private int cursorRepaso;
	private long[] pendientes = new long[0];

	private NativeImage parche;
	private NativeImage vacio;
	private final int[] alturaArriba = new int[16];
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	private final BlockPos.MutableBlockPos abajo = new BlockPos.MutableBlockPos();

	// Resultado de columna()
	private MapColor colorColumna;
	private int alturaColumna;
	private int aguaColumna;

	/** Olvida todo lo dibujado (cambio de dimensión, desconexión). */
	void reiniciar() {
		if (textura == null) return;
		Arrays.fill(chunkEnRanura, SIN_CHUNK);
		Arrays.fill(dibujada, false);
		textura.upload(); // sus píxeles en memoria siempre están en cero: deja la textura transparente
	}

	/** Crea (o rehace si cambió el radio) la textura con espacio para todo lo que cabe en el radio. */
	private void asegurar(int radio) {
		int chunks = Mth.ceil(2.0 * radio / 16.0) + 2;
		if (textura != null && chunks == n) return;
		n = chunks;
		textura = new DynamicTexture(() -> "Venatio minimapa", n * 16, n * 16, true);
		textura.upload();
		// Registrarla con el mismo id cierra la anterior.
		Minecraft.getInstance().getTextureManager().register(ID, textura);
		chunkEnRanura = new long[n * n];
		Arrays.fill(chunkEnRanura, SIN_CHUNK);
		dibujada = new boolean[n * n];
		pendientes = new long[n * n];
		cursorRepaso = 0;
		if (parche == null) {
			parche = new NativeImage(16, 16, false);
			vacio = new NativeImage(16, 16, true);
		}
	}

	/** Una pasada: ubica los chunks dentro del radio y dibuja unos cuantos. */
	void actualizar(ClientLevel nivel, double px, double pz, int radio) {
		asegurar(radio);
		int cx0 = Mth.floor(px - radio) >> 4, cx1 = Mth.floor(px + radio) >> 4;
		int cz0 = Mth.floor(pz - radio) >> 4, cz1 = Mth.floor(pz + radio) >> 4;
		int pcx = Mth.floor(px) >> 4, pcz = Mth.floor(pz) >> 4;
		GpuTexture gpu = textura.getTexture();
		CommandEncoder cmd = RenderSystem.getDevice().createCommandEncoder();

		int nPendientes = 0;
		for (int cz = cz0; cz <= cz1; cz++) {
			for (int cx = cx0; cx <= cx1; cx++) {
				int ranura = ranura(cx, cz);
				long clave = ChunkPos.pack(cx, cz);
				if (chunkEnRanura[ranura] != clave) {
					if (dibujada[ranura]) cmd.writeToTexture(gpu, vacio, 0, 0, Math.floorMod(cx, n) * 16, Math.floorMod(cz, n) * 16);
					chunkEnRanura[ranura] = clave;
					dibujada[ranura] = false;
				}
				if (!dibujada[ranura]) {
					// Distancia al cuadrado en los bits altos para ordenar; el índice de la ranura en los bajos.
					long d = (long) (cx - pcx) * (cx - pcx) + (long) (cz - pcz) * (cz - pcz);
					pendientes[nPendientes++] = d << 32 | ranura;
				}
			}
		}

		Arrays.sort(pendientes, 0, nPendientes);
		int hechos = 0;
		for (int i = 0; i < nPendientes && hechos < NUEVOS_POR_PASADA; i++) {
			int ranura = (int) pendientes[i];
			if (dibujarChunk(nivel, cmd, gpu, chunkEnRanura[ranura])) {
				dibujada[ranura] = true;
				hechos++;
			}
		}

		int ancho = cx1 - cx0 + 1, total = ancho * (cz1 - cz0 + 1);
		for (int i = 0, repasados = 0; i < total && repasados < REPASOS_POR_PASADA; i++) {
			cursorRepaso = (cursorRepaso + 1) % total;
			int cx = cx0 + cursorRepaso % ancho, cz = cz0 + cursorRepaso / ancho;
			int ranura = ranura(cx, cz);
			if (dibujada[ranura] && dibujarChunk(nivel, cmd, gpu, chunkEnRanura[ranura])) repasados++;
		}
	}

	/** Dibuja el cuadrado de (px ± radio, pz ± radio) en pantalla, con el norte arriba. */
	void dibujar(GuiGraphicsExtractor g, int x, int y, int lado, double px, double pz, int radio) {
		asegurar(radio);
		float tam = n * 16f;
		g.blit(textura.getTextureView(), textura.getSampler(), x, y, x + lado, y + lado,
			coordenada(px - radio, tam), coordenada(px - radio, tam) + 2f * radio / tam,
			coordenada(pz - radio, tam), coordenada(pz - radio, tam) + 2f * radio / tam);
	}

	/** Coordenada de textura de una posición del mundo; se reduce al rango [0, 1) para no perder precisión lejos del origen. */
	private static float coordenada(double mundo, float tam) {
		double base = Math.floor(mundo);
		return (float) ((Math.floorMod((long) base, (long) tam) + (mundo - base)) / tam);
	}

	private int ranura(int cx, int cz) {
		return Math.floorMod(cx, n) + Math.floorMod(cz, n) * n;
	}

	/** Pinta un chunk como los mapas vanilla (escala 1:1) y lo sube a su ranura. false si el cliente no lo tiene cargado. */
	private boolean dibujarChunk(ClientLevel nivel, CommandEncoder cmd, GpuTexture gpu, long clave) {
		int cx = ChunkPos.getX(clave), cz = ChunkPos.getZ(clave);
		LevelChunk chunk = nivel.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
		if (chunk == null || chunk.isEmpty()) return false;
		int bx = cx << 4, bz = cz << 4;

		// Alturas de la fila de al lado (norte) para sombrear la primera fila; -1 = desconocida.
		LevelChunk norte = nivel.getChunkSource().getChunk(cx, cz - 1, ChunkStatus.FULL, false);
		for (int x = 0; x < 16; x++) {
			if (norte != null && !norte.isEmpty()) {
				columna(nivel, norte, bx + x, bz - 1);
				alturaArriba[x] = alturaColumna;
			} else {
				alturaArriba[x] = Integer.MIN_VALUE;
			}
		}

		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int wx = bx + x, wz = bz + z;
				columna(nivel, chunk, wx, wz);
				int previa = alturaArriba[x] == Integer.MIN_VALUE ? alturaColumna : alturaArriba[x];
				alturaArriba[x] = alturaColumna;
				MapColor.Brightness brillo = brillo(colorColumna, alturaColumna - previa, aguaColumna, (wx + wz) & 1);
				parche.setPixel(x, z, colorColumna.calculateARGBColor(brillo));
			}
		}
		cmd.writeToTexture(gpu, parche, 0, 0, Math.floorMod(cx, n) * 16, Math.floorMod(cz, n) * 16);
		return true;
	}

	/** Mismo criterio que MapItem: el agua se aclara u oscurece con la profundidad; la tierra, con el desnivel hacia el norte. */
	private static MapColor.Brightness brillo(MapColor color, int desnivel, int agua, int damero) {
		if (color == MapColor.WATER) {
			double d = agua * 0.1 + damero * 0.2;
			return d < 0.5 ? MapColor.Brightness.HIGH : d > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
		}
		double d = desnivel * 4.0 / 5.0 + (damero - 0.5) * 0.4;
		return d > 0.6 ? MapColor.Brightness.HIGH : d < -0.6 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
	}

	/** Bloque visible desde arriba en la columna (solo superficie): deja color, altura y profundidad del agua en los campos. */
	private void columna(ClientLevel nivel, LevelChunk chunk, int x, int z) {
		int minY = nivel.getMinY();
		int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1;
		aguaColumna = 0;
		if (y <= minY) {
			colorColumna = MapColor.NONE;
			alturaColumna = minY;
			return;
		}
		BlockState estado;
		do {
			pos.set(x, --y, z);
			estado = chunk.getBlockState(pos);
		} while (estado.getMapColor(nivel, pos) == MapColor.NONE && y > minY);

		FluidState fluido = estado.getFluidState();
		if (y > minY && !fluido.isEmpty()) {
			int fondo = y - 1;
			BlockState debajo;
			do {
				abajo.set(x, fondo--, z);
				debajo = chunk.getBlockState(abajo);
				aguaColumna++;
			} while (fondo > minY && !debajo.getFluidState().isEmpty() && aguaColumna < PROFUNDIDAD_MAXIMA_AGUA);
			if (!estado.isFaceSturdy(nivel, pos, Direction.UP)) estado = fluido.createLegacyBlock();
		}
		colorColumna = estado.getMapColor(nivel, pos);
		alturaColumna = y;
	}
}
