package dev.venatio.juego;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Dónde se puede poner a un jugador o a un mob sin que caiga en lava, en agua, al vacío o dentro de un bloque. */
public final class LugarSeguro {
	/** Adónde manda el teletransporte: casi siempre a suelo firme; a veces al agua, a la lava o desde lo alto. */
	public enum Destino { SUELO, AGUA, LAVA, ALTURA }

	private LugarSeguro() {}

	/**
	 * Un destino del tipo pedido a entre min y max bloques. AGUA: la superficie de un océano, río o lago. LAVA: un
	 * charco de lava en la superficie (son raros: si no hay, null). ALTURA: 16–20 bloques sobre suelo firme. null si
	 * no encontró.
	 */
	static BlockPos arriesgado(ServerLevel nivel, BlockPos centro, int min, int max, RandomSource rnd, Destino tipo) {
		if (tipo == Destino.SUELO) return lejos(nivel, centro, min, max, rnd);
		if (tipo == Destino.ALTURA) {
			BlockPos suelo = lejos(nivel, centro, min, max, rnd);
			if (suelo == null) return null;
			int alto = 16 + rnd.nextInt(5);
			for (int dy = 1; dy <= alto + 1; dy++) {
				if (!nivel.getBlockState(suelo.above(dy)).isAir()) return suelo; // algo encima: mejor en el suelo
			}
			return suelo.above(alto);
		}
		boolean lava = tipo == Destino.LAVA;
		for (int intento = 0, generados = 0; intento < 40 && generados < (lava ? 16 : 10); intento++) {
			double angulo = rnd.nextDouble() * Math.PI * 2, distancia = min + rnd.nextDouble() * (max - min);
			int x = centro.getX() + (int) Math.round(Math.cos(angulo) * distancia);
			int z = centro.getZ() + (int) Math.round(Math.sin(angulo) * distancia);
			if (!lava) {
				Holder<Biome> bioma = nivel.getUncachedNoiseBiome(x >> 2, 16, z >> 2);
				boolean acuatico = bioma.is(BiomeTags.IS_OCEAN) || bioma.is(BiomeTags.IS_RIVER) || bioma.is(BiomeTags.IS_DEEP_OCEAN);
				if (!acuatico && intento < 20) continue; // primero se buscan océanos y ríos (sin generar nada)
			}
			generados++;
			nivel.getChunk(x >> 4, z >> 4);
			int bx = x & ~15, bz = z & ~15;
			for (int i = 0; i < 64; i++) {
				int cx = bx + (i % 8) * 2, cz = bz + (i / 8) * 2;
				double d = Math.hypot(cx - centro.getX(), cz - centro.getZ());
				if (d < min || d > max) continue;
				int y = nivel.getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz) - 1;
				BlockPos sup = new BlockPos(cx, y, cz);
				BlockState b = nivel.getBlockState(sup);
				boolean sirve = lava ? b.is(Blocks.LAVA) : b.is(Blocks.WATER);
				if (sirve && nivel.getBlockState(sup.above()).isAir() && nivel.getBlockState(sup.above(2)).isAir()) return sup;
			}
		}
		return null;
	}

	/**
	 * Un punto en superficie a entre min y max bloques (en horizontal) del centro; null si no encontró ninguno.
	 * Primero descarta océanos y ríos consultando solo el generador; después genera el chunk elegido y prueba varias
	 * columnas dentro de él (en un bosque, debajo de una rama baja no cabe un jugador, pero en la de al lado sí).
	 */
	static BlockPos lejos(ServerLevel nivel, BlockPos centro, int min, int max, RandomSource rnd) {
		int agua = 0, generados = 0;
		for (int intento = 0; intento < 48; intento++) {
			double angulo = rnd.nextDouble() * Math.PI * 2, distancia = min + rnd.nextDouble() * (max - min);
			int x = centro.getX() + (int) Math.round(Math.cos(angulo) * distancia);
			int z = centro.getZ() + (int) Math.round(Math.sin(angulo) * distancia);
			Holder<Biome> bioma = nivel.getUncachedNoiseBiome(x >> 2, 16, z >> 2);
			if (bioma.is(BiomeTags.IS_OCEAN) || bioma.is(BiomeTags.IS_DEEP_OCEAN) || bioma.is(BiomeTags.IS_RIVER)) {
				agua++;
				continue;
			}
			if (generados++ >= 12) break; // generar chunks tarda: con 12 alcanza si el terreno no es todo agua
			nivel.getChunk(x >> 4, z >> 4); // la genera si hace falta (como /spreadplayers)
			for (int r = 0; r <= 6; r += 2) {
				for (int dx = -r; dx <= r; dx += 2) {
					for (int dz = -r; dz <= r; dz += 2) {
						if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
						int cx = x + dx, cz = z + dz;
						if (cx >> 4 != x >> 4 || cz >> 4 != z >> 4) continue; // solo el chunk ya generado
						double d = Math.hypot(cx - centro.getX(), cz - centro.getZ());
						if (d < min || d > max) continue;
						BlockPos pos = new BlockPos(cx, nivel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz), cz);
						if (seguro(nivel, pos)) return pos;
					}
				}
			}
		}
		dev.venatio.Venatio.LOG.info("Teletransporte: sin lugar seguro cerca de {} ({} puntos en agua, {} chunks revisados)", centro, agua, generados);
		return null;
	}

	/**
	 * Un punto seguro a entre min y max bloques del centro y a una altura parecida (sirve bajo tierra), solo en chunks
	 * ya cargados; null si no hay.
	 */
	static BlockPos cerca(ServerLevel nivel, BlockPos centro, int min, int max, RandomSource rnd) {
		for (int intento = 0; intento < 16; intento++) {
			double angulo = rnd.nextDouble() * Math.PI * 2, distancia = min + rnd.nextDouble() * (max - min);
			int x = centro.getX() + (int) Math.round(Math.cos(angulo) * distancia);
			int z = centro.getZ() + (int) Math.round(Math.sin(angulo) * distancia);
			if (!nivel.hasChunk(x >> 4, z >> 4)) continue;
			for (int dy = 0; dy <= 6; dy++) {
				for (int signo : dy == 0 ? new int[]{1} : new int[]{1, -1}) {
					BlockPos pos = new BlockPos(x, centro.getY() + dy * signo, z);
					if (seguro(nivel, pos)) return pos;
				}
			}
		}
		return null;
	}

	/** Suelo firme sin líquido debajo, y los dos bloques del cuerpo libres, sin líquido ni nada que lastime. */
	public static boolean seguro(ServerLevel nivel, BlockPos pies) {
		BlockPos suelo = pies.below(), cabeza = pies.above();
		if (suelo.getY() <= nivel.getMinY() || cabeza.getY() >= nivel.getMaxY()) return false;
		BlockState s = nivel.getBlockState(suelo);
		if (!s.getFluidState().isEmpty() || peligroso(s) || !s.isFaceSturdy(nivel, suelo, Direction.UP)) return false;
		for (BlockPos q : new BlockPos[]{pies, cabeza}) {
			BlockState b = nivel.getBlockState(q);
			if (!b.getFluidState().isEmpty() || peligroso(b) || !b.getCollisionShape(nivel, q).isEmpty()) return false;
		}
		return true;
	}

	private static boolean peligroso(BlockState b) {
		return b.is(BlockTags.FIRE) || b.is(Blocks.MAGMA_BLOCK) || b.is(Blocks.CAMPFIRE) || b.is(Blocks.SOUL_CAMPFIRE)
			|| b.is(Blocks.CACTUS) || b.is(Blocks.SWEET_BERRY_BUSH) || b.is(Blocks.POWDER_SNOW) || b.is(Blocks.COBWEB)
			|| b.is(Blocks.WITHER_ROSE) || b.is(Blocks.POINTED_DRIPSTONE) || b.is(Blocks.LAVA);
	}
}
