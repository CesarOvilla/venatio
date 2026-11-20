package dev.venatio.mundo;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;

import java.util.Locale;

/**
 * Overworld, Nether y End propios de una partida, con semilla nueva. Se borran al terminar.
 * Cada partida usa ids de dimensión distintos, así ningún mapa ni minimapa reutiliza terreno de antes.
 */
public final class MundosPartida {
	private static MundosPartida activa;

	private final RuntimeLevelHandle hMundo, hNether, hEnd;
	private final ServerLevel mundo, nether, end;

	private MundosPartida(RuntimeLevelHandle hMundo, RuntimeLevelHandle hNether, RuntimeLevelHandle hEnd) {
		this.hMundo = hMundo;
		this.hNether = hNether;
		this.hEnd = hEnd;
		this.mundo = hMundo.asLevel();
		this.nether = hNether.asLevel();
		this.end = hEnd.asLevel();
	}

	public static MundosPartida crear(MinecraftServer server, long semilla, boolean pvp) {
		if (activa != null) throw new IllegalStateException("Ya hay mundos de partida abiertos");
		WorldDimensions dims = WorldPresets.createNormalWorldDimensions(server.registryAccess());
		String base = "partida_" + Long.toString(System.currentTimeMillis() / 1000L, 36).toLowerCase(Locale.ROOT);
		Fantasy fantasy = Fantasy.get(server);
		RuntimeLevelHandle m = fantasy.openTemporaryLevel(id(base + "_mundo"), config(dims, LevelStem.OVERWORLD, semilla, pvp));
		RuntimeLevelHandle n = fantasy.openTemporaryLevel(id(base + "_nether"), config(dims, LevelStem.NETHER, semilla, pvp));
		RuntimeLevelHandle e = fantasy.openTemporaryLevel(id(base + "_end"), config(dims, LevelStem.END, semilla, pvp));
		activa = new MundosPartida(m, n, e);
		return activa;
	}

	private static RuntimeLevelConfig config(WorldDimensions dims, ResourceKey<LevelStem> clave, long semilla, boolean pvp) {
		LevelStem stem = dims.get(clave).orElseThrow();
		return new RuntimeLevelConfig()
			.setSeed(semilla)
			.setGenerator(stem.generator())
			.setDimensionType(stem.type())
			.setDifficulty(Difficulty.NORMAL)
			.setShouldTickTime(true)
			.setGameRule(GameRules.PVP, pvp);
	}

	private static Identifier id(String nombre) {
		return Identifier.fromNamespaceAndPath("venatio", nombre);
	}

	public ServerLevel mundo() {
		return mundo;
	}

	public ServerLevel nether() {
		return nether;
	}

	public ServerLevel end() {
		return end;
	}

	public void borrar() {
		if (activa == this) activa = null;
		hMundo.delete();
		hNether.delete();
		hEnd.delete();
	}

	// --- Consultas para los mixins de portales ---

	public static MundosPartida activa() {
		return activa;
	}

	public static boolean esDePartida(Level level) {
		MundosPartida a = activa;
		return a != null && (level == a.mundo || level == a.nether || level == a.end);
	}

	/** El fuego solo crea portales del Nether en overworld y Nether; aquí se habilita en los de la partida. */
	public static boolean permitePortalNether(Level level) {
		MundosPartida a = activa;
		return a != null && (level == a.mundo || level == a.nether);
	}

	/** Mundo ↔ Nether de la misma partida; null si no aplica. */
	public static ServerLevel destinoNether(ServerLevel desde) {
		MundosPartida a = activa;
		if (a == null) return null;
		if (desde == a.mundo) return a.nether;
		if (desde == a.nether) return a.mundo;
		return null;
	}
}
