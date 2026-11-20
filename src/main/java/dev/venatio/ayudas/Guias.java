package dev.venatio.ayudas;

import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Partida;
import dev.venatio.red.PaquetesTienda;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSpawnOverride;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Guías de pago: validar, cobrar, buscar el objetivo y acompañar al jugador hasta la zona. */
final class Guias {
	/** Tiempo de servidor por tick para todas las búsquedas juntas. */
	private static final long PRESUPUESTO_NANOS = 4_000_000L;
	/** Si una búsqueda no termina en este tiempo se da por fallida (y se devuelve el pago). */
	private static final int MAX_TICKS_BUSQUEDA = 20 * 45;
	private static final int TICKS_SEGUIMIENTO = 40;
	/** Distancia mínima que tiene que moverse un mob seguido para reenviar la zona. */
	private static final int REENVIO_SEGUIMIENTO = 16;

	/** Cómo se busca un objetivo ya validado. La búsqueda se arranca solo después de cobrar. */
	private record Plan(Component nombre, Supplier<Busqueda> busqueda, @Nullable Predicate<Holder<Biome>> biomaLlegada,
						@Nullable UUID entidad, @Nullable Component aviso) {}

	private record Pendiente(UUID jugador, TipoAyuda tipo, Identifier objetivo, ServerLevel nivel, Plan plan,
							 Busqueda busqueda, Precios.Precio pagado, long inicio) {}

	/** Guía en curso de un jugador. destino es el punto real; la zona que ve el cliente está desplazada (dx, dz). */
	private static final class Guia {
		final UUID jugador;
		final TipoAyuda tipo;
		final Identifier objetivo;
		final Component nombre;
		final ServerLevel nivel;
		final @Nullable Predicate<Holder<Biome>> biomaLlegada;
		final @Nullable UUID entidad;
		final long fin;
		final int dx, dz;
		BlockPos destino;
		BlockPos zonaEnviada;

		Guia(Pendiente p, BlockPos destino, int dx, int dz, long fin) {
			this.jugador = p.jugador;
			this.tipo = p.tipo;
			this.objetivo = p.objetivo;
			this.nombre = p.plan.nombre();
			this.nivel = p.nivel;
			this.biomaLlegada = p.plan.biomaLlegada();
			this.entidad = p.plan.entidad();
			this.destino = destino;
			this.dx = dx;
			this.dz = dz;
			this.fin = fin;
		}

		BlockPos zona() {
			return destino.offset(dx, 0, dz);
		}
	}

	private static final Map<UUID, Pendiente> pendientes = new LinkedHashMap<>();
	private static final Map<UUID, Guia> activas = new HashMap<>();

	private Guias() {}

	// ---------------------------------------------------------------- compra

	static void pedir(ServerPlayer p, int tipoRed, Identifier id) {
		TipoAyuda tipo = TipoAyuda.deGuia(tipoRed);
		if (tipo == null || !Ayudas.puedeUsar(p) || Ayudas.enEnfriamiento(p, tipo)) return;
		if (pendientes.containsKey(p.getUUID())) {
			Ayudas.error(p, Component.translatable("venatio.ayudas.error.buscando"));
			return;
		}
		ServerLevel nivel = p.level();
		Plan plan = switch (tipo) {
			case GUIA_BIOMA -> planBioma(p, nivel, id);
			case GUIA_ESTRUCTURA -> planEstructura(p, nivel, id);
			default -> planMob(p, nivel, id);
		};
		if (plan == null) return;
		Precios.Precio precio = Ayudas.precios().de(tipo);
		if (!Cobro.cobrar(p, precio, tipo)) return;
		Busqueda busqueda = plan.busqueda().get();
		pendientes.put(p.getUUID(), new Pendiente(p.getUUID(), tipo, id, nivel, plan, busqueda, precio, Ayudas.ticks()));
		p.sendSystemMessage(Component.translatable("venatio.ayudas.guia.buscando", plan.nombre()).withStyle(ChatFormatting.GRAY));
	}

	private static @Nullable Plan planBioma(ServerPlayer p, ServerLevel nivel, Identifier id) {
		Registry<Biome> registro = nivel.registryAccess().lookupOrThrow(Registries.BIOME);
		ResourceKey<Biome> clave = ResourceKey.create(Registries.BIOME, id);
		if (registro.get(clave).isEmpty()) {
			Ayudas.error(p, Component.translatable("venatio.ayudas.error.desconocido"));
			return null;
		}
		Component nombre = NombresObjetivo.bioma(id);
		boolean posible = nivel.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().stream().anyMatch(b -> b.is(clave));
		if (!posible) {
			Ayudas.error(p, Component.translatable("venatio.ayudas.error.otra_dimension", nombre));
			return null;
		}
		if (nivel.getBiome(p.blockPosition()).is(clave)) {
			Ayudas.error(p, Component.translatable("venatio.ayudas.error.ya_estas", nombre));
			return null;
		}
		Predicate<Holder<Biome>> esEse = b -> b.is(clave);
		BlockPos origen = p.blockPosition();
		int radio = Ayudas.precios().radioBusqueda;
		return new Plan(nombre, () -> new BusquedaBioma(nivel, origen, esEse, radio), esEse, null, null);
	}

	private static @Nullable Plan planEstructura(ServerPlayer p, ServerLevel nivel, Identifier id) {
		List<Holder<Structure>> lista = estructuras(nivel, id);
		if (lista.isEmpty()) {
			Ayudas.error(p, Component.translatable("venatio.ayudas.error.desconocido"));
			return null;
		}
		if (!BusquedaEstructura.seGeneran(nivel)) {
			Ayudas.error(p, Component.translatable("venatio.ayudas.error.sin_estructuras"));
			return null;
		}
		Component nombre = NombresObjetivo.estructura(id);
		List<Holder<Structure>> posibles = BusquedaEstructura.posibles(nivel, lista);
		if (posibles.isEmpty()) {
			Ayudas.error(p, Component.translatable("venatio.ayudas.error.otra_dimension", nombre));
			return null;
		}
		BlockPos origen = p.blockPosition();
		int radio = Ayudas.precios().radioBusqueda;
		return new Plan(nombre, () -> new BusquedaEstructura(nivel, origen, posibles, radio), null, null, null);
	}

	/** Mob: el ejemplar vivo más cercano en chunks cargados; si no hay, el bioma (o la estructura) más cercano donde aparece. */
	private static @Nullable Plan planMob(ServerPlayer p, ServerLevel nivel, Identifier id) {
		Optional<EntityType<?>> buscado = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
		if (buscado.isEmpty() || buscado.get() == EntityTypes.PLAYER) {
			Ayudas.error(p, Component.translatable("venatio.ayudas.error.desconocido"));
			return null;
		}
		EntityType<?> tipo = buscado.get();
		Component nombre = tipo.getDescription();

		Entity cercano = masCercano(nivel, tipo, p);
		if (cercano != null) {
			BlockPos pos = cercano.blockPosition();
			return new Plan(nombre, () -> Busqueda.hecha(pos), null, cercano.getUUID(), null);
		}

		Set<ResourceKey<Biome>> biomas = new HashSet<>();
		for (Holder<Biome> b : nivel.getChunkSource().getGenerator().getBiomeSource().possibleBiomes()) {
			if (apareceEn(nivel, b, tipo)) b.unwrapKey().ifPresent(biomas::add);
		}
		BlockPos origen = p.blockPosition();
		int radio = Ayudas.precios().radioBusqueda;
		if (!biomas.isEmpty()) {
			Predicate<Holder<Biome>> donde = b -> b.unwrapKey().map(biomas::contains).orElse(false);
			if (donde.test(nivel.getBiome(origen))) {
				Ayudas.error(p, Component.translatable("venatio.ayudas.error.mob_aqui", nombre));
				return null;
			}
			return new Plan(nombre, () -> new BusquedaBioma(nivel, origen, donde, radio), donde, null,
				Component.translatable("venatio.ayudas.guia.mob_bioma", nombre));
		}

		List<Holder<Structure>> conMob = new ArrayList<>();
		nivel.registryAccess().lookupOrThrow(Registries.STRUCTURE).listElements().forEach(s -> {
			for (StructureSpawnOverride o : s.value().spawnOverrides().values()) {
				if (contiene(o.spawns().unwrap(), tipo)) {
					conMob.add(s);
					break;
				}
			}
		});
		List<Holder<Structure>> posibles = BusquedaEstructura.posibles(nivel, conMob);
		if (!posibles.isEmpty() && BusquedaEstructura.seGeneran(nivel)) {
			return new Plan(nombre, () -> new BusquedaEstructura(nivel, origen, posibles, radio), null, null,
				Component.translatable("venatio.ayudas.guia.mob_estructura", nombre));
		}
		Ayudas.error(p, Component.translatable("venatio.ayudas.error.otra_dimension", nombre));
		return null;
	}

	private static <T extends Entity> @Nullable Entity masCercano(ServerLevel nivel, EntityType<T> tipo, ServerPlayer p) {
		Entity mejor = null;
		double mejorDist = Double.MAX_VALUE;
		for (T e : nivel.getEntities(tipo, Entity::isAlive)) {
			double d = e.distanceToSqr(p);
			if (d < mejorDist) {
				mejorDist = d;
				mejor = e;
			}
		}
		return mejor;
	}

	/** El mob aparece de forma natural en ese bioma (spawns del bioma sobre los de la dimensión). */
	private static boolean apareceEn(ServerLevel nivel, Holder<Biome> bioma, EntityType<?> tipo) {
		MobSpawnSettings base = nivel.dimensionType().attributes()
			.applyModifier(EnvironmentAttributes.NATURAL_MOB_SPAWNS, MobSpawnSettings.EMPTY);
		MobSpawnSettings spawns = bioma.value().getAttributes().applyModifier(EnvironmentAttributes.NATURAL_MOB_SPAWNS, base);
		for (MobCategory c : MobCategory.values()) {
			if (contiene(spawns.getMobsToSpawn(c).unwrap(), tipo)) return true;
		}
		return false;
	}

	private static boolean contiene(List<Weighted<MobSpawnSettings.SpawnerData>> lista, EntityType<?> tipo) {
		for (Weighted<MobSpawnSettings.SpawnerData> w : lista) if (w.value().type() == tipo) return true;
		return false;
	}

	/** Una estructura concreta (minecraft:fortress) o un grupo por etiqueta (minecraft:village = todas las aldeas). */
	static List<Holder<Structure>> estructuras(ServerLevel nivel, Identifier id) {
		Registry<Structure> registro = nivel.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		Optional<Holder.Reference<Structure>> una = registro.get(ResourceKey.create(Registries.STRUCTURE, id));
		if (una.isPresent()) return List.of(una.get());
		return registro.get(TagKey.create(Registries.STRUCTURE, id)).map(set -> set.stream().toList()).orElse(List.of());
	}

	// ---------------------------------------------------------------- ciclo

	static void tick(long ticks) {
		if (!pendientes.isEmpty()) avanzarBusquedas(ticks);
		if (ticks % 10 == 0 && !activas.isEmpty()) revisarActivas(ticks);
	}

	private static void avanzarBusquedas(long ticks) {
		long limite = System.nanoTime() + PRESUPUESTO_NANOS;
		for (UUID id : new ArrayList<>(pendientes.keySet())) {
			Pendiente pd = pendientes.remove(id);
			boolean vencida = ticks - pd.inicio > MAX_TICKS_BUSQUEDA;
			if (!vencida && !pd.busqueda.avanzar(limite)) {
				pendientes.put(id, pd); // vuelve al final de la cola: el tiempo se reparte entre búsquedas
			} else {
				if (vencida) pd.busqueda.cancelar();
				BlockPos hallado = vencida ? null : pd.busqueda.resultado();
				ServerPlayer p = Ayudas.jugador(id);
				if (p != null) {
					if (hallado != null) activar(p, pd, hallado);
					else fallar(p, pd);
				}
			}
			if (System.nanoTime() > limite) break;
		}
	}

	private static void fallar(ServerPlayer p, Pendiente pd) {
		Cobro.devolver(p, pd.pagado);
		Ayudas.error(p, Component.translatable("venatio.ayudas.error.no_encontrado", pd.plan.nombre(), Ayudas.precios().radioBusqueda));
	}

	private static void activar(ServerPlayer p, Pendiente pd, BlockPos destino) {
		Precios precios = Ayudas.precios();
		// Desplazamiento estable: el mismo equipo hacia el mismo objetivo siempre ve la misma zona (comprar otra vez no afina).
		Partida partida = Juego.INSTANCIA.partida();
		ColorEquipo equipo = partida == null ? null : partida.equipoDe(p);
		long semilla = (equipo == null ? 7 : equipo.ordinal() + 1) * 0x9E3779B97F4A7C15L
			^ pd.objetivo.hashCode() * 31L ^ ((long) (destino.getX() >> 4) << 32) ^ (destino.getZ() >> 4);
		Random rnd = new Random(semilla);
		int d = precios.guiaDesplazamiento;
		int dx = d == 0 ? 0 : rnd.nextInt(-d, d + 1);
		int dz = d == 0 ? 0 : rnd.nextInt(-d, d + 1);
		Guia g = new Guia(pd, destino, dx, dz, Ayudas.ticks() + precios.guiaDuracion * 20L);
		activas.put(p.getUUID(), g);
		Ayudas.ponerEnfriamiento(p, pd.tipo);
		enviar(p, g);
		if (pd.plan.aviso() != null) p.sendSystemMessage(pd.plan.aviso().copy().withStyle(ChatFormatting.YELLOW));
		p.sendSystemMessage(Component.translatable("venatio.ayudas.guia.activa", g.nombre, rango(p, g.zona()))
			.withStyle(ChatFormatting.GREEN));
		Ayudas.enviarTienda(p);
	}

	private static void revisarActivas(long ticks) {
		int llegada = Ayudas.precios().guiaLlegada;
		Iterator<Guia> it = activas.values().iterator();
		while (it.hasNext()) {
			Guia g = it.next();
			ServerPlayer p = Ayudas.jugador(g.jugador);
			if (p == null) continue; // se reenvía al volver a entrar
			if (ticks >= g.fin) {
				it.remove();
				terminarEn(p, PaquetesTienda.GuiaTerminada.TIEMPO);
				p.sendSystemMessage(Component.translatable("venatio.ayudas.guia.tiempo", g.nombre).withStyle(ChatFormatting.GRAY));
				continue;
			}
			if (g.entidad != null && ticks % TICKS_SEGUIMIENTO == 0) seguir(p, g);
			if (p.level() != g.nivel) continue;
			boolean llego = distanciaHorizontal(p.blockPosition(), g.destino) <= llegada
				|| (g.biomaLlegada != null && g.biomaLlegada.test(g.nivel.getBiome(p.blockPosition())));
			if (llego) {
				it.remove();
				terminarEn(p, PaquetesTienda.GuiaTerminada.LLEGADA);
				p.sendSystemMessage(Component.translatable("venatio.ayudas.guia.llegaste", g.nombre).withStyle(ChatFormatting.GREEN));
			}
		}
	}

	/** Guía a un mob vivo: la zona sigue al ejemplar mientras esté cargado. */
	private static void seguir(ServerPlayer p, Guia g) {
		Entity e = g.nivel.getEntity(g.entidad);
		if (e == null || !e.isAlive()) return;
		g.destino = e.blockPosition();
		if (distanciaHorizontal(g.zona(), g.zonaEnviada) > REENVIO_SEGUIMIENTO) enviar(p, g);
	}

	private static void enviar(ServerPlayer p, Guia g) {
		BlockPos zona = g.zona();
		g.zonaEnviada = zona;
		int segundos = (int) Math.max(0, (g.fin - Ayudas.ticks()) / 20);
		int radio = Math.max(16, Ayudas.precios().guiaDesplazamiento);
		Ayudas.enviar(p, new PaquetesTienda.Guia(g.tipo.ordinal(), g.objetivo, g.nivel.dimension().identifier(),
			zona.getX(), zona.getZ(), radio, segundos));
	}

	private static void terminarEn(ServerPlayer p, int motivo) {
		Ayudas.enviar(p, new PaquetesTienda.GuiaTerminada(motivo));
	}

	static double distanciaHorizontal(BlockPos a, BlockPos b) {
		double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** "cerca / a media distancia / lejos", sin números. Los mismos umbrales usa el HUD. */
	static Component rango(ServerPlayer p, BlockPos zona) {
		double d = distanciaHorizontal(p.blockPosition(), zona);
		return Component.translatable(d < 150 ? "venatio.ayudas.rango.cerca" : d < 600 ? "venatio.ayudas.rango.media" : "venatio.ayudas.rango.lejos");
	}

	// ---------------------------------------------------------------- conexión y reinicio

	static void alEntrar(ServerPlayer p) {
		Guia g = activas.get(p.getUUID());
		if (g != null) enviar(p, g);
	}

	/** Si se va a mitad de una búsqueda, se cancela y se le devuelve el pago. */
	static void alSalir(ServerPlayer p) {
		Pendiente pd = pendientes.remove(p.getUUID());
		if (pd != null) {
			pd.busqueda.cancelar();
			Cobro.devolver(p, pd.pagado);
		}
	}

	static boolean hayEstado() {
		return !pendientes.isEmpty() || !activas.isEmpty();
	}

	static void reiniciar() {
		for (Pendiente pd : pendientes.values()) pd.busqueda.cancelar();
		pendientes.clear();
		for (Guia g : activas.values()) {
			ServerPlayer p = Ayudas.jugador(g.jugador);
			if (p != null) terminarEn(p, PaquetesTienda.GuiaTerminada.CANCELADA);
		}
		activas.clear();
	}

	// ---------------------------------------------------------------- para pruebas

	/** Zona (con desplazamiento) de la guía activa del jugador, o null. */
	static @Nullable BlockPos zonaActiva(UUID jugador) {
		Guia g = activas.get(jugador);
		return g == null ? null : g.zona();
	}

	static @Nullable TipoAyuda tipoActivo(UUID jugador) {
		Guia g = activas.get(jugador);
		return g == null ? null : g.tipo;
	}

	static boolean buscando(UUID jugador) {
		return pendientes.containsKey(jugador);
	}
}
