package dev.venatio.lobby;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * El lobby completo en memoria: un bloque por celda del volumen y los textos de los letreros.
 * TrazadoLobby lo llena y ConstructorLobby lo vuelca al mundo. Lo que no se pone queda como aire.
 */
final class PlanoLobby {
	static final int MIN = -40, MAX = 40, Y_MIN = 60, Y_MAX = 95;
	private static final int LADO = MAX - MIN + 1, ALTO = Y_MAX - Y_MIN + 1;
	private static final BlockState AIRE = Blocks.AIR.defaultBlockState();

	/** Texto de un letrero; las mismas cuatro líneas van por delante y por detrás. */
	record Letrero(BlockPos pos, List<Component> lineas) {}

	private final BlockState[] bloques = new BlockState[LADO * ALTO * LADO];
	private final List<Letrero> letreros = new ArrayList<>();
	/** Intentos de poner bloques fuera del volumen; debe quedar en 0 (lo revisa la herramienta de verificación). */
	int fuera;

	private static boolean cabe(int x, int y, int z) {
		return x >= MIN && x <= MAX && z >= MIN && z <= MAX && y >= Y_MIN && y <= Y_MAX;
	}

	private static int indice(int x, int y, int z) {
		return ((x - MIN) * LADO + (z - MIN)) * ALTO + (y - Y_MIN);
	}

	BlockState en(int x, int y, int z) {
		if (!cabe(x, y, z)) return AIRE;
		BlockState b = bloques[indice(x, y, z)];
		return b == null ? AIRE : b;
	}

	boolean libre(int x, int y, int z) {
		return en(x, y, z).isAir();
	}

	void poner(int x, int y, int z, BlockState estado) {
		if (!cabe(x, y, z)) {
			fuera++;
			return;
		}
		bloques[indice(x, y, z)] = estado;
	}

	void poner(int x, int y, int z, Block bloque) {
		poner(x, y, z, bloque.defaultBlockState());
	}

	/** Columna vertical de y1 a y2 (incluidos). */
	void columna(int x, int z, int y1, int y2, BlockState estado) {
		for (int y = y1; y <= y2; y++) poner(x, y, z, estado);
	}

	void caja(int x1, int y1, int z1, int x2, int y2, int z2, BlockState estado) {
		for (int x = x1; x <= x2; x++)
			for (int z = z1; z <= z2; z++)
				for (int y = y1; y <= y2; y++) poner(x, y, z, estado);
	}

	void letrero(int x, int y, int z, BlockState estado, List<Component> lineas) {
		poner(x, y, z, estado);
		letreros.add(new Letrero(new BlockPos(x, y, z), lineas));
	}

	List<Letrero> letreros() {
		return letreros;
	}
}
