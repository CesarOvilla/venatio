package dev.venatio.ayudas;

import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/** Búsqueda que puede tardar varios ticks. Se avanza desde el hilo del servidor con un límite de tiempo por tick. */
interface Busqueda {
	/** Avanza hasta terminar o hasta pasar el instante límite (System.nanoTime). Devuelve true si ya terminó. */
	boolean avanzar(long limiteNanos);

	/** Posición encontrada, o null si no se encontró nada. Solo válido cuando {@link #avanzar} devolvió true. */
	@Nullable BlockPos resultado();

	default void cancelar() {}

	/** Búsqueda que ya tiene su resultado. */
	static Busqueda hecha(BlockPos pos) {
		return new Busqueda() {
			@Override
			public boolean avanzar(long limiteNanos) {
				return true;
			}

			@Override
			public BlockPos resultado() {
				return pos;
			}
		};
	}
}
