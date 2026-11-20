package dev.venatio.ayudas;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Estructura más cercana, con el mismo método que /locate structure (ChunkGenerator.findNearestMapStructure):
 * recorre anillos de regiones alrededor del jugador y consulta en cada una si la estructura puede generarse ahí.
 * A diferencia del comando, avanza unos milisegundos por tick para no congelar el servidor, y el radio se limita en bloques.
 */
final class BusquedaEstructura implements Busqueda {
	/** Tope de anillos, como el comando de vanilla. */
	private static final int MAX_ANILLOS = 100;

	private record Anillos(RandomSpreadStructurePlacement colocacion, Set<Holder<Structure>> estructuras, int maxRadio) {}

	private final ServerLevel nivel;
	private final BlockPos origen;
	private final int chunkX, chunkZ;
	private final long semilla;
	private final StructureManager estructuras;
	private final List<Anillos> anillos = new ArrayList<>();
	private final List<Holder<Structure>> concentricas = new ArrayList<>();
	private final int maxRadio;

	private boolean concentricasHechas;
	private int radio, indiceAnillos, indicePos;
	private boolean hallazgoEnEsteRadio, terminada;
	private BlockPos mejor;
	private double mejorDist = Double.MAX_VALUE;

	/** buscadas: estructuras que tienen colocación en este mundo (ver {@link #posibles}). */
	BusquedaEstructura(ServerLevel nivel, BlockPos origen, List<Holder<Structure>> buscadas, int radioBloques) {
		this.nivel = nivel;
		this.origen = origen;
		this.chunkX = SectionPos.blockToSectionCoord(origen.getX());
		this.chunkZ = SectionPos.blockToSectionCoord(origen.getZ());
		ChunkGeneratorStructureState estado = nivel.getChunkSource().getGeneratorState();
		this.semilla = estado.getLevelSeed();
		this.estructuras = nivel.structureManager();

		Map<StructurePlacement, Set<Holder<Structure>>> porColocacion = new LinkedHashMap<>();
		for (Holder<Structure> h : buscadas) {
			for (StructurePlacement c : estado.getPlacementsForStructure(h)) {
				porColocacion.computeIfAbsent(c, k -> new LinkedHashSet<>()).add(h);
			}
		}
		int max = 0;
		for (var e : porColocacion.entrySet()) {
			if (e.getKey() instanceof ConcentricRingsStructurePlacement) {
				concentricas.addAll(e.getValue());
			} else if (e.getKey() instanceof RandomSpreadStructurePlacement aleatoria) {
				int r = Math.min(MAX_ANILLOS, Math.max(1, (int) Math.ceil(radioBloques / (16.0 * aleatoria.spacing()))));
				anillos.add(new Anillos(aleatoria, e.getValue(), r));
				max = Math.max(max, r);
			}
		}
		this.maxRadio = max;
	}

	/** Si el mundo se creó sin estructuras, no hay nada que buscar (vanilla hace la misma comprobación). */
	static boolean seGeneran(ServerLevel nivel) {
		return nivel.getServer().getWorldGenSettings().options().generateStructures();
	}

	/** Las estructuras de la lista que pueden aparecer en este mundo. */
	static List<Holder<Structure>> posibles(ServerLevel nivel, List<Holder<Structure>> lista) {
		ChunkGeneratorStructureState estado = nivel.getChunkSource().getGeneratorState();
		return lista.stream().filter(h -> !estado.getPlacementsForStructure(h).isEmpty()).toList();
	}

	@Override
	public boolean avanzar(long limiteNanos) {
		if (terminada) return true;
		if (!concentricasHechas) {
			// Fortalezas: posiciones precalculadas por el juego; la búsqueda de vanilla ya es rápida.
			concentricasHechas = true;
			if (!concentricas.isEmpty()) {
				Pair<BlockPos, Holder<Structure>> par = nivel.getChunkSource().getGenerator()
					.findNearestMapStructure(nivel, HolderSet.direct(concentricas), origen, 0, false);
				if (par != null) considerar(par.getFirst());
			}
			if (System.nanoTime() > limiteNanos) return false;
		}
		while (radio <= maxRadio) {
			while (indiceAnillos < anillos.size()) {
				Anillos a = anillos.get(indiceAnillos);
				int total = radio == 0 ? 1 : 8 * radio;
				if (radio <= a.maxRadio) {
					while (indicePos < total) {
						int i = indicePos++;
						BlockPos hallado = comprobar(a, i);
						if (hallado != null) {
							hallazgoEnEsteRadio = true;
							considerar(hallado);
						}
						if (System.nanoTime() > limiteNanos) return false;
					}
				}
				indiceAnillos++;
				indicePos = 0;
			}
			// Como vanilla: se para en el primer anillo con algo (de ese anillo, el más cercano).
			if (hallazgoEnEsteRadio) break;
			radio++;
			indiceAnillos = 0;
		}
		terminada = true;
		return true;
	}

	private void considerar(BlockPos pos) {
		double d = origen.distSqr(pos);
		if (d < mejorDist) {
			mejorDist = d;
			mejor = pos;
		}
	}

	/** Región i del anillo actual (recorre el borde del cuadrado de lado 2·radio). */
	private @Nullable BlockPos comprobar(Anillos a, int i) {
		int r = radio, x, z;
		if (r == 0) {
			x = 0;
			z = 0;
		} else if (i < 2 * r) {
			x = -r + i;
			z = -r;
		} else if (i < 4 * r) {
			x = r;
			z = -r + (i - 2 * r);
		} else if (i < 6 * r) {
			x = r - (i - 4 * r);
			z = r;
		} else {
			x = -r;
			z = r - (i - 6 * r);
		}
		int espaciado = a.colocacion.spacing();
		ChunkPos candidato = a.colocacion.getPotentialStructureChunk(semilla, chunkX + espaciado * x, chunkZ + espaciado * z);
		for (Holder<Structure> h : a.estructuras) {
			StructureCheckResult rapido = estructuras.checkStructurePresence(candidato, h.value(), a.colocacion, false);
			if (rapido == StructureCheckResult.START_NOT_PRESENT) continue;
			if (rapido == StructureCheckResult.START_PRESENT) return a.colocacion.getLocatePos(candidato);
			// Puede generarse: hay que generar el inicio de estructuras de ese chunk (como vanilla) para saber
			// cuál de las estructuras del conjunto salió de verdad (p. ej. fortaleza o bastión).
			ChunkAccess chunk = nivel.getChunk(candidato.x(), candidato.z(), ChunkStatus.STRUCTURE_STARTS);
			StructureStart inicio = estructuras.getStartForStructure(h.value(), chunk);
			if (inicio != null && inicio.isValid()) return a.colocacion.getLocatePos(inicio.getChunkPos());
		}
		return null;
	}

	@Override
	public @Nullable BlockPos resultado() {
		return mejor;
	}
}
