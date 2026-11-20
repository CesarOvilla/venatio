package dev.venatio.info;

import dev.venatio.Venatio;
import dev.venatio.carton.Pool;
import dev.venatio.red.PaquetesFicha;
import dev.venatio.red.PaquetesFicha.Ficha;
import dev.venatio.red.PaquetesFicha.Fuente;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Módulo de fichas en el servidor: arma los índices al arrancar (y tras /reload) y contesta las peticiones de ficha
 * y de lista de items posibles. Recetas y "de dónde sale" son gratis (ver DISEÑO.md, sección 4).
 */
public final class Fichas {
	private static final String POOL = "/data/venatio/pools/clasico.json";

	private static IndiceRecetas recetas;
	private static IndiceFuentes fuentes;
	private static Pistas pistas;
	private static List<String> entradasPool = List.of();
	private static List<TagKey<Item>> gruposPool = List.of();

	private Fichas() {}

	public static void registrar() {
		PaquetesFicha.registrar();
		ServerLifecycleEvents.SERVER_STARTED.register(Fichas::construir);
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, recursos, ok) -> {
			if (ok) construir(server);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			recetas = null;
			fuentes = null;
			pistas = null;
		});
		ServerPlayNetworking.registerGlobalReceiver(PaquetesFicha.PedirFicha.TYPE, (p, ctx) -> responderFicha(ctx.player(), p.item()));
		ServerPlayNetworking.registerGlobalReceiver(PaquetesFicha.PedirItemsPosibles.TYPE,
			(p, ctx) -> ServerPlayNetworking.send(ctx.player(), new PaquetesFicha.ItemsPosibles(entradasPool)));
	}

	private static void construir(MinecraftServer server) {
		cargarPool();
		pistas = Pistas.cargar(server.getResourceManager());
		recetas = IndiceRecetas.construir(server);
		fuentes = IndiceFuentes.construir(server, recetas);
		Venatio.LOG.info("Fichas listas: recetas de {} items, {} pistas, {} entradas en el pool",
			recetas.cantidadItems(), pistas.cantidad(), entradasPool.size());
	}

	private static void cargarPool() {
		Pool pool = Pool.cargar(POOL);
		Set<String> entradas = new LinkedHashSet<>();
		for (Map<Integer, String> cat : pool.categorias()) entradas.addAll(cat.values());
		entradasPool = List.copyOf(entradas);
		List<TagKey<Item>> grupos = new ArrayList<>();
		for (String e : entradasPool) {
			if (e.startsWith("#")) grupos.add(TagKey.create(Registries.ITEM, Identifier.parse(e.substring(1))));
		}
		gruposPool = List.copyOf(grupos);
	}

	private static void responderFicha(ServerPlayer jugador, Identifier itemId) {
		if (recetas == null || fuentes == null) return;
		Optional<Item> item = BuiltInRegistries.ITEM.getOptional(itemId).filter(i -> i != Items.AIR);
		if (item.isEmpty()) return;
		ServerPlayNetworking.send(jugador, ficha(item.get()));
	}

	/** Todo lo que se sabe de un item: recetas, fuentes (calculadas y de la pista) y la pista. */
	public static Ficha ficha(Item item) {
		Identifier id = BuiltInRegistries.ITEM.getKey(item);
		Identifier grupo = null;
		for (TagKey<Item> tag : gruposPool) {
			if (item.builtInRegistryHolder().is(tag)) {
				grupo = tag.location();
				break;
			}
		}
		Pistas.Pista pista = pistas.de(id, grupo);
		return new Ficha(id, recetas.de(item), recetas.destilacionesDe(item), fuentesCon(item, pista), pista.texto(),
			Optional.ofNullable(grupo));
	}

	/** Las pistas escritas a mano van primero; lo calculado no repite un mob o estructura que la pista ya nombra. */
	private static List<Fuente> fuentesCon(Item item, Pistas.Pista pista) {
		List<Fuente> calculadas = fuentes.de(item);
		List<Fuente> lista = new ArrayList<>();
		Set<String> vistas = new HashSet<>();
		for (Fuente f : calculadas) vistas.add(f.tipo() + "|" + f.id());
		for (Identifier bioma : pista.biomas()) {
			lista.add(new Fuente(Fuente.BIOMA, bioma, 0, Optional.of(bioma), List.of(), List.of()));
		}
		for (Identifier estructura : pista.estructuras()) {
			Fuente f = fuentes.estructura(estructura, Fuente.NATURAL);
			if (vistas.add(f.tipo() + "|" + f.id())) lista.add(f);
		}
		for (Identifier mob : pista.mobs()) {
			Optional<EntityType<?>> tipo = BuiltInRegistries.ENTITY_TYPE.getOptional(mob);
			if (tipo.isEmpty()) continue;
			Fuente f = fuentes.mob(tipo.get(), Fuente.CURADO);
			if (vistas.add(f.tipo() + "|" + f.id())) lista.add(f);
		}
		lista.addAll(calculadas);
		return lista;
	}
}
