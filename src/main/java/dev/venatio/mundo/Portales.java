package dev.venatio.mundo;

import dev.venatio.juego.Juego;
import dev.venatio.juego.Partida;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.EndPlatformFeature;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/** Destinos de los portales del End dentro de los mundos de una partida (los del Nether van en el mixin). */
public final class Portales {
	private Portales() {}

	/** Igual que vanilla al entrar al End, pero al End de la partida. */
	public static TeleportTransition entradaAlEnd(MundosPartida m, Entity entidad) {
		ServerLevel end = m.end();
		Vec3 pos = Vec3.atBottomCenterOf(ServerLevel.END_SPAWN_POINT);
		EndPlatformFeature.createEndPlatform(end, BlockPos.containing(pos).below(), true);
		if (entidad instanceof ServerPlayer) pos = pos.subtract(0.0, 1.0, 0.0);
		return new TeleportTransition(end, pos, Vec3.ZERO, Direction.WEST.toYRot(), 0f,
			Relative.union(Relative.DELTA, Set.of(Relative.X_ROT)),
			TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET));
	}

	/** Al salir del End: la cama del jugador si está en el mundo de la partida; si no, el punto de su equipo. */
	public static TeleportTransition salidaDelEnd(MundosPartida m, Entity entidad) {
		ServerLevel mundo = m.mundo();
		if (entidad instanceof ServerPlayer sp) {
			ServerPlayer.RespawnConfig rc = sp.getRespawnConfig();
			if (rc != null && rc.respawnData().dimension() == mundo.dimension()) {
				TeleportTransition cama = sp.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING);
				if (cama.newLevel() == mundo) return cama;
			}
			Partida partida = Juego.INSTANCIA.partida();
			BlockPos spawn = partida != null ? partida.spawnDe(sp) : null;
			if (spawn != null) {
				return new TeleportTransition(mundo, Vec3.atBottomCenterOf(spawn), Vec3.ZERO, sp.getYRot(), 0f, TeleportTransition.DO_NOTHING);
			}
		}
		BlockPos centro = new BlockPos(0, mundo.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0), 0);
		return new TeleportTransition(mundo, Vec3.atBottomCenterOf(centro), Vec3.ZERO, 0f, 0f, TeleportTransition.DO_NOTHING);
	}
}
