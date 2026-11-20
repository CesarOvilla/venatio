package dev.venatio.ayudas;

import com.mojang.datafixers.util.Pair;
import dev.venatio.Venatio;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.RandomState;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Bioma más cercano, igual que /locate biome (muestreo en espiral cada 32 bloques y a varias alturas),
 * pero en un hilo de fondo: solo consulta el generador de biomas, que vanilla también usa fuera del hilo principal
 * (posiciones de las fortalezas), así que no toca chunks ni bloquea el servidor.
 */
final class BusquedaBioma implements Busqueda {
	private static final int PASO_HORIZONTAL = 32;
	private static final int PASO_VERTICAL = 64;

	private final CompletableFuture<Pair<BlockPos, Holder<Biome>>> futuro;

	BusquedaBioma(ServerLevel nivel, BlockPos origen, Predicate<Holder<Biome>> buscado, int radio) {
		BiomeSource fuente = nivel.getChunkSource().getGenerator().getBiomeSource();
		RandomState estado = nivel.getChunkSource().randomState();
		this.futuro = CompletableFuture.supplyAsync(
			() -> fuente.findClosestBiome3d(origen, radio, PASO_HORIZONTAL, PASO_VERTICAL, buscado, estado, nivel),
			Util.backgroundExecutor().forName("venatio_guia_bioma"));
	}

	@Override
	public boolean avanzar(long limiteNanos) {
		return futuro.isDone();
	}

	@Override
	public @Nullable BlockPos resultado() {
		try {
			Pair<BlockPos, Holder<Biome>> par = futuro.getNow(null);
			return par == null ? null : par.getFirst();
		} catch (Exception e) {
			Venatio.LOG.warn("Falló la búsqueda de bioma", e);
			return null;
		}
	}

	@Override
	public void cancelar() {
		futuro.cancel(false);
	}
}
