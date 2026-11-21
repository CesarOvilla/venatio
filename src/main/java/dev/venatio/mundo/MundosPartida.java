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
	/** Para que dos juegos de mundos creados en el mismo segundo no repitan id (Precalentado puede crear y tirar seguido). */
	private static int creados;

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

	public static MundosPartida crear(MinecraftServer server, long semilla, boolean pvp, boolean conservarInventario) {
		return preparar(server, semilla, pvp, conservarInventario).activar();
	}

	/** Crea los mundos sin hacerlos los de la partida en curso (Precalentado los genera desde el lobby). */
	public static MundosPartida preparar(MinecraftServer server, long semilla, boolean pvp, boolean conservarInventario) {
		WorldDimensions dims = WorldPresets.createNormalWorldDimensions(server.registryAccess());
		String base = "partida_" + Long.toString(System.currentTimeMillis() / 1000L, 36).toLowerCase(Locale.ROOT)
			+ (creados++ == 0 ? "" : "_" + creados);
		Fantasy fantasy = Fantasy.get(server);
		RuntimeLevelHandle m = fantasy.openTemporaryLevel(id(base + "_mundo"), config(dims, LevelStem.OVERWORLD, semilla, pvp, conservarInventario));
		RuntimeLevelHandle n = fantasy.openTemporaryLevel(id(base + "_nether"), config(dims, LevelStem.NETHER, semilla, pvp, conservarInventario));
		RuntimeLevelHandle e = fantasy.openTemporaryLevel(id(base + "_end"), config(dims, LevelStem.END, semilla, pvp, conservarInventario));
		return new MundosPartida(m, n, e);
	}

	/** Estos pasan a ser los mundos de la partida en curso (los que miran portales, reaparición, etc.). */
	public MundosPartida activar() {
		if (activa != null && activa != this) throw new IllegalStateException("Ya hay mundos de partida abiertos");
		activa = this;
		return this;
	}

	/** PvP y conservar inventario pueden cambiar en el lobby después de preparar los mundos (cada mundo tiene sus reglas). */
	public void ajustarReglas(boolean pvp, boolean conservarInventario) {
		for (ServerLevel l : new ServerLevel[]{mundo, nether, end}) {
			l.getGameRules().set(GameRules.PVP, pvp, null);
			l.getGameRules().set(GameRules.KEEP_INVENTORY, conservarInventario, null);
		}
	}

	private static RuntimeLevelConfig config(WorldDimensions dims, ResourceKey<LevelStem> clave, long semilla, boolean pvp, boolean conservarInventario) {
		LevelStem stem = dims.get(clave).orElseThrow();
		return new RuntimeLevelConfig()
			.setSeed(semilla)
			.setGenerator(stem.generator())
			.setDimensionType(stem.type())
			.setDifficulty(Difficulty.NORMAL)
			.setShouldTickTime(true)
			.setGameRule(GameRules.PVP, pvp)
			.setGameRule(GameRules.KEEP_INVENTORY, conservarInventario)
			// Los logros no se anuncian en el chat durante la partida (como en MCBingo)
			.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false)
			.setGameRule(GameRules.LOCATOR_BAR, false); // la dibuja Venatio (dev.venatio.localizador); así no se enciman
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
