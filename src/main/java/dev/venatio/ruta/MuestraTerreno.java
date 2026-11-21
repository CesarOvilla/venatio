package dev.venatio.ruta;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;

/**
 * Colores de mapa de un chunk a baja resolución (una muestra cada 4 bloques), con el mismo criterio que los mapas
 * vanilla y el minimapa: bloque visible desde arriba, agua según su profundidad y relieve según el desnivel hacia el
 * norte. En dimensiones con techo (Nether) no sirve mirar desde arriba: se busca el suelo a la altura del jugador.
 * Solo lee chunks ya cargados; no genera ni carga nada.
 */
final class MuestraTerreno {
	private static final int PROFUNDIDAD_MAXIMA_AGUA = 12;
	/** Con techo: cuántos bloques se baja buscando suelo desde la altura del jugador. */
	private static final int BAJADA_MAXIMA = 40;
	private static final int M = DatosRuta.MUESTRAS_POR_LADO, PASO = DatosRuta.BLOQUES_POR_MUESTRA;

	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	private final BlockPos.MutableBlockPos abajo = new BlockPos.MutableBlockPos();
	private final int[] alturaNorte = new int[M];
	// Resultado de columna()
	private MapColor color;
	private int altura;
	private int agua;
	private boolean pared;

	/**
	 * Llena destino (16 bytes) con los colores del chunk. refY: altura del jugador que lo tenía cerca (solo con techo).
	 * Devuelve false si el chunk no tiene nada que dibujar.
	 */
	boolean muestrear(ServerLevel nivel, LevelChunk chunk, boolean techo, int refY, byte[] destino) {
		int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
		// Alturas de la última fila del chunk de al norte, para el relieve de la primera fila.
		LevelChunk norte = nivel.getChunkSource().getChunkNow(chunk.getPos().x(), chunk.getPos().z() - 1);
		for (int i = 0; i < M; i++) {
			if (norte != null) {
				columna(nivel, norte, bx + i * PASO + PASO / 2, bz - PASO / 2, techo, refY);
				alturaNorte[i] = altura;
			} else {
				alturaNorte[i] = Integer.MIN_VALUE;
			}
		}
		boolean algo = false;
		for (int j = 0; j < M; j++) {
			for (int i = 0; i < M; i++) {
				int x = bx + i * PASO + PASO / 2, z = bz + j * PASO + PASO / 2;
				columna(nivel, chunk, x, z, techo, refY);
				int previa = alturaNorte[i] == Integer.MIN_VALUE ? altura : alturaNorte[i];
				alturaNorte[i] = altura;
				MapColor.Brightness brillo = pared ? MapColor.Brightness.LOWEST : brillo(color, altura - previa, agua, (i + j) & 1);
				byte b = color == MapColor.NONE ? 0 : color.getPackedId(brillo);
				destino[j * M + i] = b;
				if (b != 0) algo = true;
			}
		}
		return algo;
	}

	/** Mismo criterio que MapItem a escala 1:4: el agua según la profundidad; la tierra, con el desnivel hacia el norte. */
	private static MapColor.Brightness brillo(MapColor color, int desnivel, int agua, int damero) {
		if (color == MapColor.WATER) {
			double d = agua * 0.1 + damero * 0.2;
			return d < 0.5 ? MapColor.Brightness.HIGH : d > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
		}
		double d = desnivel * 4.0 / 6.0 + (damero - 0.5) * 0.4;
		return d > 0.6 ? MapColor.Brightness.HIGH : d < -0.6 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
	}

	private void columna(ServerLevel nivel, LevelChunk chunk, int x, int z, boolean techo, int refY) {
		agua = 0;
		pared = false;
		if (techo) columnaConTecho(nivel, chunk, x, z, refY);
		else columnaDesdeArriba(nivel, chunk, x, z);
	}

	/** Bloque visible desde arriba (superficie). */
	private void columnaDesdeArriba(ServerLevel nivel, LevelChunk chunk, int x, int z) {
		int minY = nivel.getMinY();
		int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1;
		if (y <= minY) {
			color = MapColor.NONE;
			altura = minY;
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
				agua++;
			} while (fondo > minY && !debajo.getFluidState().isEmpty() && agua < PROFUNDIDAD_MAXIMA_AGUA);
			if (!estado.isFaceSturdy(nivel, pos, Direction.UP)) estado = fluido.createLegacyBlock();
		}
		color = estado.getMapColor(nivel, pos);
		altura = y;
	}

	/**
	 * Nether: desde un poco arriba del jugador se baja hasta encontrar aire y luego el primer bloque con color (el
	 * suelo de la cueva en la que anda). Si todo es roca, es pared: se pinta oscura.
	 */
	private void columnaConTecho(ServerLevel nivel, LevelChunk chunk, int x, int z, int refY) {
		int minY = nivel.getMinY();
		int maxY = minY + nivel.dimensionType().logicalHeight() - 6;
		int y = Math.clamp(refY + 3, minY + 1, maxY);
		boolean vioAire = false;
		for (int k = 0; k < BAJADA_MAXIMA && y > minY; k++, y--) {
			pos.set(x, y, z);
			BlockState estado = chunk.getBlockState(pos);
			MapColor c = estado.getMapColor(nivel, pos);
			if (c == MapColor.NONE) {
				vioAire = true;
				continue;
			}
			if (vioAire) {
				color = c;
				altura = y;
				return;
			}
		}
		pared = true;
		pos.set(x, Math.clamp(refY, minY, maxY), z);
		color = chunk.getBlockState(pos).getMapColor(nivel, pos);
		altura = refY;
	}
}
