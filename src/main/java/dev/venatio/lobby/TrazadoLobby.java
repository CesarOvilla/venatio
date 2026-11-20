package dev.venatio.lobby;

import dev.venatio.juego.ColorEquipo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Diseño del lobby: dónde va cada bloque. Solo llena un PlanoLobby; no toca el mundo.
 *
 * Orientación: el norte es -Z. Plaza circular centrada en el origen con el piso en y=64.
 * Al norte, el escenario con la fachada de los tableros; al este, el nicho de la última partida;
 * al sur, el portón; al oeste, el parkour. Las ocho zonas de equipo forman un anillo.
 */
final class TrazadoLobby {
	/** Bloque del piso de la plaza; se camina en Y_PISO + 1. */
	static final int Y_PISO = 64;
	static final double RADIO = 25.5;

	/** Escenario: interior |x| <= 20, z de -33 a -22. De z=-24 hacia el norte está elevado un bloque. */
	static final int ESC_ANCHO = 20, ESC_Z_FONDO = -33, ESC_Z_FRENTE = -22, ESC_Z_TARIMA = -24;
	/** Plano del muro de la fachada norte; los marcos de los tableros sobresalen en Z_FACHADA + 1. */
	static final int Z_FACHADA = -34;
	/** Muro del nicho este; el marco sobresale en X_MURO_ESTE - 1. */
	static final int X_MURO_ESTE = 28;
	/** Parkour: interior x de -38 a -25, |z| <= 8. */
	private static final int PK_X1 = -38, PK_X2 = -25, PK_Z = 8;

	static final BlockPos ATRIL = new BlockPos(0, 66, -25);

	/** Media anchura de la zona de equipo (2 = 5×5). */
	static final int RADIO_ZONA = 2;

	record Zona(ColorEquipo equipo, int cx, int cz, DyeColor tinte, String nombre) {}

	/** Los equipos más usados quedan al frente (norte), a los lados del camino al escenario. */
	static final List<Zona> ZONAS = List.of(
		new Zona(ColorEquipo.ROJO, -6, -14, DyeColor.RED, "Rojo"),
		new Zona(ColorEquipo.AZUL, 6, -14, DyeColor.BLUE, "Azul"),
		new Zona(ColorEquipo.VERDE, -14, -6, DyeColor.LIME, "Verde"),
		new Zona(ColorEquipo.AMARILLO, 14, -6, DyeColor.YELLOW, "Amarillo"),
		new Zona(ColorEquipo.MORADO, -14, 6, DyeColor.PURPLE, "Morado"),
		new Zona(ColorEquipo.NARANJA, 14, 6, DyeColor.ORANGE, "Naranja"),
		new Zona(ColorEquipo.CIAN, -6, 14, DyeColor.CYAN, "Cian"),
		new Zona(ColorEquipo.ROSA, 6, 14, DyeColor.PINK, "Rosa")
	);

	/** Saltos del parkour: x, z, altura en bloques sobre el piso. */
	private static final int[][] SALTOS = {
		{-27, -6, 1}, {-29, -6, 2}, {-32, -6, 2}, {-35, -6, 2}, {-37, -4, 3}, {-37, -1, 3},
		{-35, 1, 3}, {-32, 1, 3}, {-29, 2, 3}, {-27, 4, 3}, {-29, 6, 3}, {-32, 6, 3}
	};

	private static final int ORO = 0xFFAA00;

	private final BlockState aire = Blocks.AIR.defaultBlockState();
	private final BlockState ladrillo = Blocks.STONE_BRICKS.defaultBlockState();
	private final BlockState ladrilloMusgoso = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
	private final BlockState ladrilloRoto = Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
	private final BlockState losaLadrillo = Blocks.STONE_BRICK_SLAB.defaultBlockState();
	private final BlockState ladrilloPizarra = Blocks.DEEPSLATE_BRICKS.defaultBlockState();
	private final BlockState tejaPizarra = Blocks.DEEPSLATE_TILES.defaultBlockState();
	private final BlockState pizarraPulida = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
	private final BlockState musgo = Blocks.MOSS_BLOCK.defaultBlockState();
	private final BlockState tablonAbeto = Blocks.SPRUCE_PLANKS.defaultBlockState();
	private final BlockState tablonOscuro = Blocks.DARK_OAK_PLANKS.defaultBlockState();
	private final BlockState tronco = Blocks.SPRUCE_LOG.defaultBlockState();
	private final BlockState vigaX = eje(Blocks.SPRUCE_LOG, Direction.Axis.X);
	private final BlockState vigaZ = eje(Blocks.SPRUCE_LOG, Direction.Axis.Z);
	private final BlockState cerca = Blocks.SPRUCE_FENCE.defaultBlockState();
	private final BlockState farol = Blocks.LANTERN.defaultBlockState();
	private final BlockState farolColgado = Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true);
	// Cobre encerado: el que no lo está sigue oxidándose o cambia con el tiempo.
	private final BlockState cobre = Blocks.COPPER_BLOCK.waxed().oxidized().defaultBlockState();
	private final BlockState cobreCortado = Blocks.CUT_COPPER.waxed().oxidized().defaultBlockState();
	private final BlockState cobreCincelado = Blocks.CHISELED_COPPER.waxed().oxidized().defaultBlockState();
	private final BlockState techoNorte = escalon(Blocks.DARK_OAK_STAIRS, Direction.NORTH);
	private final BlockState techoEste = escalon(Blocks.DARK_OAK_STAIRS, Direction.EAST);

	private final PlanoLobby p = new PlanoLobby();

	private TrazadoLobby() {}

	static PlanoLobby trazar() {
		TrazadoLobby t = new TrazadoLobby();
		t.pisoYMuro();
		t.jardin();
		t.escenario();
		t.fachada();
		t.nichoEste();
		t.porton();
		t.zonas();
		t.parkour();
		t.luces();
		return t.p;
	}

	// ---------- Forma ----------

	private static boolean enEscenario(int x, int z) {
		return Math.abs(x) <= ESC_ANCHO && z >= ESC_Z_FONDO && z <= ESC_Z_FRENTE;
	}

	private static boolean enParkour(int x, int z) {
		return x >= PK_X1 && x <= PK_X2 && Math.abs(z) <= PK_Z;
	}

	private static boolean enNicho(int x, int z) {
		return x >= 24 && x < X_MURO_ESTE && Math.abs(z) <= 6;
	}

	private static boolean enDisco(int x, int z) {
		return x * x + z * z <= RADIO * RADIO;
	}

	/** Celdas donde se puede estar. */
	static boolean dentro(int x, int z) {
		return enDisco(x, z) || enEscenario(x, z) || enParkour(x, z) || enNicho(x, z);
	}

	/** Celdas del muro perimetral: fuera, pero tocando (también en diagonal) una celda de dentro. */
	static boolean esMuro(int x, int z) {
		if (dentro(x, z)) return false;
		for (int dx = -1; dx <= 1; dx++)
			for (int dz = -1; dz <= 1; dz++)
				if (dentro(x + dx, z + dz)) return true;
		return false;
	}

	// ---------- Piso, muro y jardín ----------

	private static int azar(int x, int z) {
		int h = x * 73428767 ^ z * 912931;
		h ^= h >>> 13;
		h *= 0x5bd1e995;
		h ^= h >>> 15;
		return Math.floorMod(h, 100);
	}

	private BlockState ladrilloVariado(int x, int z) {
		int a = azar(x, z);
		return a < 7 ? ladrilloMusgoso : a < 11 ? ladrilloRoto : ladrillo;
	}

	private BlockState piso(int x, int z) {
		if (enEscenario(x, z)) return z == ESC_Z_FRENTE ? pizarraPulida : ladrillo;
		if (!enDisco(x, z)) {
			if (enParkour(x, z)) return Math.abs(z) <= 1 && x >= -27 ? ladrilloPizarra : musgo;
			return tejaPizarra;
		}
		double r = Math.sqrt(x * x + z * z);
		if (r < 0.5) return cobreCincelado;
		if (r < 3.5) return x == 0 || z == 0 ? cobreCortado : tejaPizarra;
		if (r < 4.5) return pizarraPulida;
		// Explanadas frente al tablero del este y al portón del sur.
		if (x >= 21 && Math.abs(z) <= 6 || z >= 21 && Math.abs(x) <= 4) return tejaPizarra;
		// Caminos a los cuatro rumbos.
		if (Math.abs(x) <= 1 || Math.abs(z) <= 1) return ladrilloPizarra;
		if (r >= 10.5 && r < 11.5 || r >= 19.5 && r < 20.5) return pizarraPulida;
		// Anillo de jardín, salvo frente al escenario.
		if (r >= 20.5 && z > -18) return musgo;
		return ladrilloVariado(x, z);
	}

	private void pisoYMuro() {
		Set<Long> pilares = pilares();
		for (int x = PlanoLobby.MIN; x <= PlanoLobby.MAX; x++) {
			for (int z = PlanoLobby.MIN; z <= PlanoLobby.MAX; z++) {
				if (dentro(x, z)) {
					p.poner(x, Y_PISO - 1, z, ladrilloPizarra);
					p.poner(x, Y_PISO, z, piso(x, z));
				} else if (esMuro(x, z)) {
					muro(x, z, pilares.contains(BlockPos.asLong(x, 0, z)));
				}
			}
		}
	}

	private void muro(int x, int z, boolean pilar) {
		// Junto al parkour el muro sube más: desde el último salto no se alcanza a brincar encima.
		int tope = x <= -24 && Math.abs(z) <= 10 ? 70 : 67;
		p.columna(x, z, Y_PISO - 1, Y_PISO + 1, ladrilloPizarra);
		for (int y = Y_PISO + 2; y <= tope; y++) p.poner(x, y, z, ladrilloVariado(x + y * 31, z - y * 17));
		p.poner(x, tope + 1, z, losaLadrillo);
		if (pilar) {
			p.columna(x, z, Y_PISO + 1, tope + 1, tronco);
			p.poner(x, tope + 2, z, farol);
		}
	}

	/** Pilares de tronco con farol repartidos por el muro, sin dejar dos pegados. */
	private static Set<Long> pilares() {
		Set<Long> pilares = new HashSet<>();
		for (int pasada = 0; pasada < 2; pasada++) {
			for (int x = PlanoLobby.MIN; x <= PlanoLobby.MAX; x++) {
				for (int z = PlanoLobby.MIN; z <= PlanoLobby.MAX; z++) {
					if (!esMuro(x, z)) continue;
					boolean candidato = pasada == 0
						? Math.floorMod(x, 8) == 0 && (dentro(x, z + 1) || dentro(x, z - 1))
						: Math.floorMod(z, 8) == 0 && (dentro(x + 1, z) || dentro(x - 1, z));
					if (candidato && !hayCerca(pilares, x, z)) pilares.add(BlockPos.asLong(x, 0, z));
				}
			}
		}
		return pilares;
	}

	private static boolean hayCerca(Set<Long> pilares, int x, int z) {
		for (int dx = -3; dx <= 3; dx++)
			for (int dz = -3; dz <= 3; dz++)
				if (pilares.contains(BlockPos.asLong(x + dx, 0, z + dz))) return true;
		return false;
	}

	private void jardin() {
		for (int x = PlanoLobby.MIN; x <= PlanoLobby.MAX; x++) {
			for (int z = PlanoLobby.MIN; z <= PlanoLobby.MAX; z++) {
				if (!enDisco(x, z) || p.en(x, Y_PISO, z) != musgo) continue;
				int a = azar(x * 3 + 11, z * 5 - 7);
				if (a < 10) p.poner(x, Y_PISO + 1, z, Blocks.FERN);
				else if (a < 14) p.poner(x, Y_PISO + 1, z, Blocks.AZALEA);
				else if (a < 16) p.poner(x, Y_PISO + 1, z, Blocks.FLOWERING_AZALEA);
			}
		}
		// Faroles altos en las diagonales y bajos a los lados de los caminos.
		for (int sx = -1; sx <= 1; sx += 2) {
			for (int sz = -1; sz <= 1; sz += 2) {
				poste(16 * sx, 16 * sz, 3);
			}
			poste(20 * sx, 2, 2);
			poste(20 * sx, -2, 2);
			poste(2 * sx, 20, 2);
		}
	}

	private void poste(int x, int z, int alto) {
		p.columna(x, z, Y_PISO + 1, Y_PISO + alto, cerca);
		p.poner(x, Y_PISO + alto + 1, z, farol);
	}

	// ---------- Escenario y panel del anfitrión ----------

	private void escenario() {
		int y = Y_PISO + 1;
		BlockState nariz = eje(Blocks.STRIPPED_SPRUCE_LOG, Direction.Axis.X);
		BlockState subida = escalon(Blocks.STONE_BRICK_STAIRS, Direction.NORTH);
		for (int x = -ESC_ANCHO; x <= ESC_ANCHO; x++) {
			p.poner(x, y, ESC_Z_TARIMA + 1, subida);
			p.poner(x, y, ESC_Z_TARIMA, nariz);
			for (int z = ESC_Z_FONDO; z < ESC_Z_TARIMA; z++) {
				p.poner(x, y, z, Math.abs(x) % 5 == 0 ? tablonOscuro : tablonAbeto);
			}
		}

		// Podio de cobre con el atril: el anfitrión se para detrás, mirando a la plaza.
		p.caja(ATRIL.getX() - 1, y, ATRIL.getZ() - 1, ATRIL.getX() + 1, y, ATRIL.getZ() + 1, cobreCortado);
		p.poner(ATRIL.getX(), ATRIL.getY(), ATRIL.getZ(), Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, Direction.NORTH));
		List<Component> texto = List.of(
			Component.literal("Panel del").withColor(ORO),
			Component.literal("anfitrión").withColor(ORO),
			Component.literal("Configura"),
			Component.literal("la partida")
		);
		for (int s = -1; s <= 1; s += 2) {
			int x = ATRIL.getX() + s;
			p.poner(x, ATRIL.getY(), ATRIL.getZ(), cobre);
			p.poner(x, ATRIL.getY() + 1, ATRIL.getZ(), farol);
			p.letrero(x, ATRIL.getY(), ATRIL.getZ() + 1, letreroMural(Direction.SOUTH), texto);
		}
	}

	// ---------- Fachada norte: ranking, partida, equipos y título ----------

	private void fachada() {
		int z = Z_FACHADA;
		for (int x = -ESC_ANCHO - 1; x <= ESC_ANCHO + 1; x++) {
			int ax = Math.abs(x);
			boolean centro = ax <= 8;
			p.columna(x, z, Y_PISO - 1, PlanoLobby.Y_MAX, aire);
			p.columna(x, z, Y_PISO - 1, 65, ladrilloPizarra);
			p.poner(x, 66, z, pizarraPulida);
			p.columna(x, z, 67, centro ? 79 : 74, tablonAbeto);
			if (ax <= 7) {
				p.columna(x, z, 67, 75, tablonOscuro); // fondo del ranking
				p.columna(x, z, 77, 79, tejaPizarra); // fondo del título
			}
			if (ax >= 11 && ax <= 19) p.columna(x, z, 67, 72, tablonOscuro); // fondo de partida y equipos
		}

		// Marcos: postes y vigas un bloque por delante del muro.
		for (int x = -7; x <= 7; x++) p.poner(x, 76, z + 1, vigaX);
		for (int s = -1; s <= 1; s += 2) {
			p.columna(8 * s, z + 1, 66, 79, tronco);
			p.poner(8 * s, 76, z + 1, cobreCincelado);
			p.columna(10 * s, z + 1, 66, 72, tronco);
			p.columna(20 * s, z + 1, 66, 72, tronco);
			for (int ax = 10; ax <= 20; ax++) p.poner(ax * s, 73, z + 1, ax == 10 || ax == 20 ? cobreCincelado : vigaX);

			// Alero sobre los tableros laterales, con faroles colgados.
			for (int ax = 9; ax <= ESC_ANCHO + 1; ax++) {
				p.poner(ax * s, 75, z, techoNorte);
				p.poner(ax * s, 74, z + 1, techoNorte);
				p.poner(ax * s, 73, z + 2, techoNorte);
			}
			for (int ax = 12; ax <= 18; ax += 3) p.poner(ax * s, 72, z + 2, farolColgado);
			p.poner(7 * s, 80, z + 2, farolColgado);
		}

		// Frontón: techo a dos aguas de media pendiente sobre el título.
		for (int x = -9; x <= 9; x++) {
			int i = 9 - Math.abs(x);
			int y = 80 + i / 2;
			boolean arriba = i % 2 == 1;
			BlockState losa = Blocks.DARK_OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, arriba ? SlabType.TOP : SlabType.BOTTOM);
			for (int dz = -1; dz <= 2; dz++) p.poner(x, y, z + dz, dz == 0 && arriba ? tablonOscuro : losa);
			if (Math.abs(x) <= 8) p.columna(x, z, 80, y - 1, tablonAbeto);
		}
	}

	// ---------- Nicho este: última partida ----------

	private void nichoEste() {
		int x = X_MURO_ESTE;
		for (int z = -6; z <= 6; z++) {
			p.columna(x, z, Y_PISO - 1, PlanoLobby.Y_MAX, aire);
			p.columna(x, z, Y_PISO - 1, Y_PISO, ladrilloPizarra);
			p.poner(x, 65, z, pizarraPulida);
			p.columna(x, z, 66, 72, tablonAbeto);
			if (Math.abs(z) <= 4) p.columna(x, z, 66, 70, tablonOscuro);
			p.poner(x, 73, z, techoEste);
			p.poner(x - 1, 72, z, techoEste);
			p.poner(x - 2, 71, z, techoEste);
		}
		for (int z = -4; z <= 4; z++) p.poner(x - 1, 71, z, vigaZ);
		for (int s = -1; s <= 1; s += 2) {
			p.columna(x - 1, 5 * s, 65, 70, tronco);
			p.poner(x - 1, 71, 5 * s, cobreCincelado);
			p.poner(x - 2, 70, 5 * s, farolColgado);
		}
	}

	// ---------- Portón sur (decorativo) ----------

	private void porton() {
		int z = 26;
		for (int x = -4; x <= 4; x++) {
			int ax = Math.abs(x);
			p.columna(x, z, Y_PISO - 1, PlanoLobby.Y_MAX, aire);
			p.columna(x, z, Y_PISO - 1, Y_PISO, ladrilloPizarra);
			if (ax == 4) {
				p.columna(x, z, 65, 70, tronco);
				p.poner(x, 71, z, farol);
				continue;
			}
			p.columna(x, z, 65, 68, ax <= 2 ? tablonOscuro : ladrillo);
			p.poner(x, 69, z, vigaX);
			p.poner(x, 70, z, ladrillo);
			p.poner(x, 71, z, losaLadrillo);
		}
		BlockState banderin = Blocks.WALL_BANNER.pick(DyeColor.GREEN).defaultBlockState().setValue(WallBannerBlock.FACING, Direction.NORTH);
		p.poner(-2, 67, z - 1, banderin);
		p.poner(2, 67, z - 1, banderin);
		p.letrero(0, 66, z - 1, letreroMural(Direction.NORTH), List.of(
			Component.literal("Las puertas"),
			Component.literal("se abren al"),
			Component.literal("empezar la"),
			Component.literal("partida")
		));
	}

	// ---------- Zonas de equipo ----------

	private void zonas() {
		for (Zona zona : ZONAS) {
			int cx = zona.cx(), cz = zona.cz();
			int borde = RADIO_ZONA + 1;
			p.caja(cx - borde, Y_PISO, cz - borde, cx + borde, Y_PISO, cz + borde, pizarraPulida);
			p.caja(cx - RADIO_ZONA, Y_PISO, cz - RADIO_ZONA, cx + RADIO_ZONA, Y_PISO, cz + RADIO_ZONA,
				Blocks.CONCRETE.pick(zona.tinte()).defaultBlockState());

			// Letrero y banderines detrás de la zona, mirando al centro de la plaza.
			double largo = Math.sqrt(cx * cx + cz * cz);
			double ux = cx / largo, uz = cz / largo;
			double lx = cx + 3.6 * ux, lz = cz + 3.6 * uz;
			int giro = RotationSegment.convertToSegment((float) Math.toDegrees(Math.atan2(cx, -cz)));
			p.letrero((int) Math.round(lx), Y_PISO + 1, (int) Math.round(lz),
				Blocks.DARK_OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, giro),
				List.of(
					Component.literal("Equipo " + zona.nombre()).withColor(zona.equipo().rgb),
					Component.empty(),
					Component.literal("Párate aquí"),
					Component.literal("para unirte")
				));
			BlockState banderin = Blocks.BANNER.pick(zona.tinte()).defaultBlockState().setValue(BannerBlock.ROTATION, giro);
			for (int s = -1; s <= 1; s += 2) {
				p.poner((int) Math.round(lx - 2.2 * s * uz), Y_PISO + 1, (int) Math.round(lz + 2.2 * s * ux), banderin);
			}
		}
	}

	// ---------- Parkour ----------

	private void parkour() {
		for (int i = 0; i < SALTOS.length; i++) {
			int x = SALTOS[i][0], z = SALTOS[i][1], tope = Y_PISO + SALTOS[i][2];
			p.columna(x, z, Y_PISO + 1, tope - 1, tronco);
			p.poner(x, tope, z, i % 2 == 0 ? Blocks.HAY_BLOCK : Blocks.TARGET);
		}
		// Meta
		p.caja(-37, Y_PISO + 1, 6, -35, Y_PISO + 2, 7, tronco);
		p.caja(-37, Y_PISO + 3, 6, -35, Y_PISO + 3, 7, tablonAbeto);
		p.poner(-37, Y_PISO + 4, 7, Blocks.BELL);
		BlockState alEste = Blocks.DARK_OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, RotationSegment.convertToSegment(Direction.EAST));
		p.letrero(-37, Y_PISO + 4, 6, alEste, List.of(
			Component.literal("¡Meta!").withColor(ORO),
			Component.empty(),
			Component.literal("Toca la"),
			Component.literal("campana")
		));
		p.letrero(-25, Y_PISO + 1, -4, alEste, List.of(
			Component.literal("Parkour").withColor(ORO),
			Component.literal("Para pasar el"),
			Component.literal("rato mientras"),
			Component.literal("llegan todos")
		));
	}

	// ---------- Luz ----------

	/** Bloques de luz invisibles en rejilla: nada queda oscuro aunque en la dimensión sea de noche. */
	private void luces() {
		for (int x = PlanoLobby.MIN; x <= PlanoLobby.MAX; x++) {
			for (int z = PlanoLobby.MIN; z <= PlanoLobby.MAX; z++) {
				if (!dentro(x, z) || Math.floorMod(x, 4) != 0 || Math.floorMod(z, 4) != 0) continue;
				int base = enEscenario(x, z) && z <= ESC_Z_TARIMA ? Y_PISO + 4 : Y_PISO + 3;
				for (int y = base; y < base + 4; y++) {
					if (p.libre(x, y, z)) {
						p.poner(x, y, z, Blocks.LIGHT);
						break;
					}
				}
			}
		}
	}

	// ---------- Ayudantes de estado ----------

	private static BlockState eje(Block bloque, Direction.Axis eje) {
		return bloque.defaultBlockState().setValue(RotatedPillarBlock.AXIS, eje);
	}

	/** Escalón que sube hacia la dirección dada. */
	private static BlockState escalon(Block bloque, Direction hacia) {
		return bloque.defaultBlockState().setValue(StairBlock.FACING, hacia);
	}

	private static BlockState letreroMural(Direction cara) {
		return Blocks.DARK_OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, cara);
	}
}
