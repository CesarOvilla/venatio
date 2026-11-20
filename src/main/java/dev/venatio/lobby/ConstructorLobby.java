package dev.venatio.lobby;

import dev.venatio.juego.ColorEquipo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Construye el lobby en la dimensión vacía venatio:lobby.
 * El diseño está en TrazadoLobby; aquí solo se vuelca al mundo y se responde dónde está cada cosa.
 * El resto del mod solo usa esta API pública; su firma no debe cambiar.
 */
public final class ConstructorLobby {
	/** Donde aparece el jugador al entrar al lobby: el centro de la plaza. */
	public static final Vec3 SPAWN = new Vec3(0.5, TrazadoLobby.Y_PISO + 1, 0.5);
	/** Hacia dónde mira al aparecer (grados, 0 = sur): al norte, hacia el escenario y el ranking. */
	public static final float SPAWN_YAW = 180f;
	/** Si un jugador cae por debajo de esta Y, se le regresa a SPAWN. El bloque más bajo del lobby está en y=63. */
	public static final double Y_RESCATE = 55;

	/** Sin avisar a los vecinos: el plano ya trae cada bloque en su forma final y así nada se rompe a medio construir. */
	private static final int BANDERAS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	private static ResourceKey<Level> dimension;

	private ConstructorLobby() {}

	/**
	 * Se llama una vez al arrancar el server. Idempotente: compara el volumen completo contra el plano
	 * y solo cambia las celdas que difieren, así que lo que hubiera de un diseño anterior se borra.
	 */
	public static void construir(ServerLevel nivel) {
		dimension = nivel.dimension();
		PlanoLobby plano = TrazadoLobby.trazar();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		List<BlockPos> conexiones = new ArrayList<>();

		for (int x = PlanoLobby.MIN; x <= PlanoLobby.MAX; x++) {
			for (int z = PlanoLobby.MIN; z <= PlanoLobby.MAX; z++) {
				for (int y = PlanoLobby.Y_MIN; y <= PlanoLobby.Y_MAX; y++) {
					BlockState quiero = plano.en(x, y, z);
					pos.set(x, y, z);
					if (nivel.getBlockState(pos) != quiero) nivel.setBlock(pos, quiero, BANDERAS);
					if (seConecta(quiero)) conexiones.add(pos.immutable());
				}
			}
		}

		// Cercas y muretes calculan sus uniones a partir de los vecinos, ya con todo colocado.
		for (BlockPos p : conexiones) {
			BlockState actual = nivel.getBlockState(p);
			BlockState unido = Block.updateFromNeighbourShapes(actual, nivel, p);
			if (unido != actual) nivel.setBlock(p, unido, BANDERAS);
		}

		for (PlanoLobby.Letrero letrero : plano.letreros()) {
			if (nivel.getBlockEntity(letrero.pos()) instanceof SignBlockEntity entidad) escribir(entidad, letrero.lineas());
		}
	}

	private static boolean seConecta(BlockState estado) {
		Block b = estado.getBlock();
		return b instanceof FenceBlock || b instanceof WallBlock || b instanceof IronBarsBlock;
	}

	/** Texto blanco brillante (se lee sobre el roble oscuro); las líneas con color propio lo conservan. */
	private static void escribir(SignBlockEntity entidad, List<Component> lineas) {
		SignText.Mutable texto = SignText.EMPTY.asMutable().setColor(DyeColor.WHITE).setTextGlowing(true);
		for (int i = 0; i < 4 && i < lineas.size(); i++) texto.setLine(i, lineas.get(i));
		SignText listo = texto.asImmutable();
		if (!listo.equals(entidad.getText(SignTextSlot.FRONT))) entidad.setText(listo, SignTextSlot.FRONT);
		if (!listo.equals(entidad.getText(SignTextSlot.BACK))) entidad.setText(listo, SignTextSlot.BACK);
		entidad.setWaxed(true);
	}

	/** Equipo cuya zona de selección está pisando el jugador, o null. Solo compara coordenadas. */
	public static ColorEquipo padBajo(ServerPlayer p) {
		if (dimension != null && p.level().dimension() != dimension) return null;
		double y = p.getY();
		if (y < TrazadoLobby.Y_PISO + 0.5 || y > TrazadoLobby.Y_PISO + 3.5) return null;
		int x = (int) Math.floor(p.getX());
		int z = (int) Math.floor(p.getZ());
		for (TrazadoLobby.Zona zona : TrazadoLobby.ZONAS) {
			if (Math.abs(x - zona.cx()) <= TrazadoLobby.RADIO_ZONA && Math.abs(z - zona.cz()) <= TrazadoLobby.RADIO_ZONA) return zona.equipo();
		}
		return null;
	}

	/** Bloque del panel del anfitrión: el atril del podio de cobre, al frente del escenario. */
	public static BlockPos panelAnfitrion() {
		return TrazadoLobby.ATRIL;
	}
}
