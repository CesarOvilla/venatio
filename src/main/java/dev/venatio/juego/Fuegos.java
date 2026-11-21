package dev.venatio.juego;

import dev.venatio.mundo.MundosPartida;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;

/** Fuegos artificiales del final: salvas con el color del equipo ganador, a la vista de cada participante. */
public final class Fuegos {
	/** Ticks de la fase final en los que sale una salva. */
	private static final int[] SALVAS = {4, 22, 40, 60};
	private static final int POR_SALVA = 2;
	private static final int DORADO = 0xF2B84B;
	private static final FireworkExplosion.Shape[] FORMAS = {
		FireworkExplosion.Shape.LARGE_BALL, FireworkExplosion.Shape.STAR, FireworkExplosion.Shape.BURST, FireworkExplosion.Shape.SMALL_BALL
	};

	private static int lanzados;

	private Fuegos() {}

	/** Cohetes lanzados en el final en curso o en el último (para las pruebas). */
	public static int lanzados() {
		return lanzados;
	}

	static void tick(Partida partida, int ticks) {
		if (partida.ganador == null) return;
		for (int k = 0; k < SALVAS.length; k++) {
			if (SALVAS[k] != ticks) continue;
			if (k == 0) lanzados = 0;
			for (ServerPlayer p : partida.jugadoresEnLinea()) {
				if (MundosPartida.esDePartida(p.level()) && !p.isSpectator()) lanzar(p, partida.ganador, k);
			}
		}
	}

	/**
	 * Lanza los cohetes delante del jugador, a 12–20 bloques y desde la superficie: suben más de 9 bloques antes de
	 * estallar, así que nadie queda dentro de los 5 bloques en que un cohete hace daño, y a esa distancia se ven sin mirar al cielo.
	 */
	private static void lanzar(ServerPlayer p, ColorEquipo color, int salva) {
		ServerLevel nivel = p.level();
		RandomSource rnd = nivel.getRandom();
		for (int i = 0; i < POR_SALVA; i++) {
			double rumbo = Math.toRadians(p.getYRot() + (rnd.nextDouble() - 0.5) * 100);
			double distancia = 12 + rnd.nextDouble() * 8;
			double x = p.getX() - Math.sin(rumbo) * distancia, z = p.getZ() + Math.cos(rumbo) * distancia;
			int suelo = nivel.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
			double y = Math.max(suelo, p.getY()) + 1;

			FireworkExplosion estallido = new FireworkExplosion(FORMAS[(salva + i) % FORMAS.length],
				IntList.of(color.rgb, i % 2 == 0 ? color.rgb : DORADO), IntList.of(DORADO), true, i % 2 == 1);
			ItemStack cohete = new ItemStack(Items.FIREWORK_ROCKET);
			cohete.set(DataComponents.FIREWORKS, new Fireworks(1, List.of(estallido)));
			nivel.addFreshEntity(new FireworkRocketEntity(nivel, x, y, z, cohete));
			lanzados++;
		}
	}
}
