package dev.venatio.juego;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** El bloque de la suerte: suelta unas chispas para verlo de cerca. Lo que pasa al romperlo está en {@link BloquesSuerte}. */
public final class BloqueSuerte extends Block {
	public BloqueSuerte(Properties propiedades) {
		super(propiedades);
	}

	@Override
	public void animateTick(BlockState estado, Level nivel, BlockPos pos, RandomSource azar) {
		if (azar.nextInt(3) != 0) return;
		nivel.addParticle(ParticleTypes.END_ROD, pos.getX() + azar.nextDouble(), pos.getY() + 1.05, pos.getZ() + azar.nextDouble(),
			0, 0.02, 0);
	}
}
