package dev.venatio.info;

import dev.venatio.Venatio;
import dev.venatio.carton.Exclusiones;
import dev.venatio.carton.Pool;
import dev.venatio.carton.Reto;
import dev.venatio.carton.Retos;
import dev.venatio.juego.Ajustes;
import dev.venatio.juego.Juego;
import dev.venatio.red.PaquetesFicha;
import dev.venatio.red.PaquetesFicha.Ficha;
import dev.venatio.red.PaquetesFicha.Fuente;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
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
			(p, ctx) -> ServerPlayNetworking.send(ctx.player(), itemsPosibles(ctx.player())));
		ServerPlayNetworking.registerGlobalReceiver(PaquetesFicha.AlternarExclusion.TYPE, (p, ctx) -> alternarExclusion(ctx.player(), p.entrada()));
		ServerPlayNetworking.registerGlobalReceiver(PaquetesFicha.PedirFichaReto.TYPE, (p, ctx) -> responderFichaReto(ctx.player(), p.reto()));
	}

	private static PaquetesFicha.ItemsPosibles itemsPosibles(ServerPlayer p) {
		return new PaquetesFicha.ItemsPosibles(entradasPool, List.copyOf(Exclusiones.lista()), esAnfitrion(p));
	}

	private static boolean esAnfitrion(ServerPlayer p) {
		return Commands.LEVEL_GAMEMASTERS.check(p.permissions());
	}

	/** Las entradas quitadas cambiaron: todos reciben la lista nueva (la pantalla de items posibles se actualiza sola). */
	public static void enviarItemsPosiblesATodos() {
		MinecraftServer server = Juego.INSTANCIA.server();
		if (server == null) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(p, PaquetesFicha.ItemsPosibles.TYPE)) ServerPlayNetworking.send(p, itemsPosibles(p));
		}
	}

	/** Clic derecho de un operador en "Items posibles": quita la entrada del pool o la vuelve a poner. */
	private static void alternarExclusion(ServerPlayer p, String entrada) {
		if (!esAnfitrion(p)) {
			p.sendSystemMessage(Component.translatable("venatio.pool.error.permiso").withStyle(ChatFormatting.RED));
			return;
		}
		Component error = Ajustes.excluir(entrada, !Exclusiones.excluida(entrada));
		if (error != null) p.sendSystemMessage(error.copy().withStyle(ChatFormatting.RED));
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

	private static void responderFichaReto(ServerPlayer jugador, String id) {
		Reto r = Retos.porId(id);
		if (r == null || recetas == null || fuentes == null) return;
		ServerPlayNetworking.send(jugador, new PaquetesFicha.FichaReto(id, fichaReto(r)));
	}

	/**
	 * Ficha de una casilla de logro: primero dónde lograrlo (los biomas, el mob con dónde aparece, estructuras útiles) y
	 * después recetas, procedencia y pista del item relacionado (la cama para dormir, la comida para comer…).
	 */
	public static Ficha fichaReto(Reto r) {
		List<Fuente> lista = new ArrayList<>();
		switch (r.tipo()) {
			case BIOMA -> r.objetivos().forEach(b -> lista.add(new Fuente(Fuente.BIOMA, b, Fuente.BIOMA_LOGRO, Optional.of(b), List.of(), List.of())));
			case MATAR -> r.objetivos().forEach(m -> BuiltInRegistries.ENTITY_TYPE.getOptional(m).ifPresent(t -> lista.add(fuentes.mob(t, Fuente.BUSCAR))));
			default -> {}
		}
		for (String extra : EXTRAS_RETO.getOrDefault(r.id(), List.of())) {
			Identifier id = Identifier.parse(extra.substring(extra.indexOf(':') + 1));
			switch (extra.substring(0, extra.indexOf(':'))) {
				case "mob" -> BuiltInRegistries.ENTITY_TYPE.getOptional(id).ifPresent(t -> lista.add(fuentes.mob(t, Fuente.BUSCAR)));
				case "estructura" -> lista.add(fuentes.estructura(id, Fuente.NATURAL));
				default -> lista.add(bioma(id));
			}
		}
		Item rel = r.itemRelacionado();
		if (rel == null) {
			return new Ficha(BuiltInRegistries.ITEM.getKey(r.itemIcono()), List.of(), List.of(), lista, "", Optional.empty());
		}
		Ficha delItem = ficha(rel);
		Set<String> vistas = new HashSet<>();
		for (Fuente f : lista) vistas.add(f.tipo() + "|" + f.id());
		for (Fuente f : delItem.fuentes()) if (vistas.add(f.tipo() + "|" + f.id())) lista.add(f);
		return new Ficha(delItem.item(), delItem.recetas(), delItem.destilaciones(), lista, delItem.pista(), delItem.grupo());
	}

	private static Fuente bioma(Identifier id) {
		return new Fuente(Fuente.BIOMA, id, 0, Optional.of(id), List.of(), List.of());
	}

	/** Lo que ayuda a lograr algunos logros además de su item: "mob:…", "estructura:…" o "bioma:…" (ids de minecraft). */
	private static final Map<String, List<String>> EXTRAS_RETO = Map.ofEntries(
		Map.entry("domesticar", List.of("mob:wolf", "mob:cat", "mob:parrot", "mob:horse")),
		Map.entry("criar", List.of("mob:cow", "mob:sheep", "mob:pig", "mob:chicken")),
		Map.entry("montar_caballo", List.of("mob:horse")),
		Map.entry("montar_cerdo", List.of("mob:pig")),
		Map.entry("montar_strider", List.of("mob:strider")),
		Map.entry("comerciar", List.of("estructura:village_plains")),
		Map.entry("campana", List.of("estructura:village_plains")),
		Map.entry("nether", List.of("estructura:ruined_portal")),
		Map.entry("minar_obsidiana", List.of("estructura:ruined_portal")),
		Map.entry("minar_esmeralda", List.of("bioma:windswept_hills", "bioma:meadow", "bioma:stony_peaks", "bioma:cherry_grove")),
		Map.entry("minar_cuarzo", List.of("bioma:nether_wastes")),
		Map.entry("pescar", List.of("bioma:river")),
		Map.entry("altura_200", List.of("bioma:jagged_peaks", "bioma:stony_peaks")),
		Map.entry("altura_menos_50", List.of("bioma:deep_dark", "bioma:dripstone_caves")));

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
