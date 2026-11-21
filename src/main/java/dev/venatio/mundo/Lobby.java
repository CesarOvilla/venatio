package dev.venatio.mundo;

import dev.venatio.juego.Jugadores;
import dev.venatio.lobby.ConstructorLobby;
import dev.venatio.lobby.TablerosLobby;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;
import xyz.nucleoid.fantasy.util.VoidChunkGenerator;

import java.util.Set;

/** Lobby: dimensión vacía y persistente. Lo que se construye ahí está en dev.venatio.lobby. */
public final class Lobby {
	private static ServerLevel nivel;

	private Lobby() {}

	public static void abrir(MinecraftServer server) {
		RuntimeLevelConfig config = new RuntimeLevelConfig()
			.setGenerator(new VoidChunkGenerator(server))
			.setDimensionType(BuiltinDimensionTypes.OVERWORLD)
			.setShouldTickTime(false)
			.setGameRule(GameRules.SPAWN_MOBS, false)
			.setGameRule(GameRules.ADVANCE_TIME, false)
			.setGameRule(GameRules.ADVANCE_WEATHER, false)
			.setGameRule(GameRules.PVP, false)
			.setGameRule(GameRules.LOCATOR_BAR, false); // en el lobby no hay barra de localización
		nivel = Fantasy.get(server).getOrOpenPersistentLevel(Identifier.fromNamespaceAndPath("venatio", "lobby"), config).asLevel();
		ConstructorLobby.construir(nivel);
		TablerosLobby.crear(nivel);
	}


	public static ServerLevel nivel() {
		return nivel;
	}

	public static boolean esLobby(Level level) {
		return level != null && level == nivel;
	}

	/** Lleva al jugador al lobby con el inventario limpio y en modo aventura. */
	public static void enviar(ServerPlayer p) {
		Jugadores.reiniciar(p, GameType.ADVENTURE);
		p.setRespawnPosition(null, false);
		volverAlCentro(p);
	}

	public static void volverAlCentro(ServerPlayer p) {
		Vec3 s = ConstructorLobby.SPAWN;
		p.teleportTo(nivel, s.x, s.y, s.z, Set.of(), ConstructorLobby.SPAWN_YAW, 0f, true);
	}
}
