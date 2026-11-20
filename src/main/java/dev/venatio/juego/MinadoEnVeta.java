package dev.venatio.juego;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Minado en veta: agachado, al romper un mineral con pico (o un tronco con hacha) se rompen también
 * los bloques iguales conectados, hasta un límite. Gasta durabilidad y hambre como si se rompieran a mano.
 */
public final class MinadoEnVeta {
	private static final int LIMITE = 64;
	private static boolean enCadena;

	private MinadoEnVeta() {}

	public static void registrar() {
		PlayerBlockBreakEvents.AFTER.register(MinadoEnVeta::alRomper);
	}

	private static void alRomper(Level level, Player jugador, BlockPos origen, BlockState estado, Object blockEntity) {
		if (enCadena || !(jugador instanceof ServerPlayer p) || !p.isShiftKeyDown()) return;
		Partida partida = Juego.INSTANCIA.partida();
		if (partida == null || partida.fase() != Partida.Fase.JUGANDO || !partida.cfg.minado || !partida.participa(p)) return;

		ItemStack herramienta = p.getMainHandItem();
		boolean esMineral = estado.is(ConventionalBlockTags.ORES) && herramienta.is(ItemTags.PICKAXES) && p.hasCorrectToolForDrops(estado);
		boolean esTronco = estado.is(BlockTags.LOGS) && herramienta.is(ItemTags.AXES);
		if (!esMineral && !esTronco) return;

		List<BlockPos> veta = buscarVeta(level, origen, estado.getBlock());
		enCadena = true;
		try {
			for (BlockPos pos : veta) {
				if (p.getMainHandItem() != herramienta || herramienta.isEmpty()) break; // la herramienta se rompió o se cambió
				p.gameMode.destroyBlock(pos);
			}
		} finally {
			enCadena = false;
		}
	}

	/** Bloques iguales conectados (incluye diagonales: ramas de árboles y vetas irregulares), sin el origen. */
	private static List<BlockPos> buscarVeta(Level level, BlockPos origen, Block bloque) {
		List<BlockPos> encontrados = new ArrayList<>();
		Set<BlockPos> vistos = new HashSet<>();
		ArrayDeque<BlockPos> cola = new ArrayDeque<>();
		cola.add(origen);
		vistos.add(origen);
		while (!cola.isEmpty() && encontrados.size() < LIMITE) {
			BlockPos actual = cola.poll();
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						BlockPos vecino = actual.offset(dx, dy, dz);
						if (!vistos.add(vecino) || !level.getBlockState(vecino).is(bloque)) continue;
						encontrados.add(vecino);
						cola.add(vecino);
						if (encontrados.size() >= LIMITE) return encontrados;
					}
				}
			}
		}
		return encontrados;
	}
}
