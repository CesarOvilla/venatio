package dev.venatio.juego;

import dev.venatio.Venatio;
import dev.venatio.ayudas.Ayudas;
import dev.venatio.carton.Carton;
import dev.venatio.carton.Objetivo;
import dev.venatio.info.Fichas;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Prediction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Bloques de la suerte (opción "suerte"): regados por la superficie del mundo normal de la partida, más o menos uno cada
 * 128 bloques y en los mismos lugares para una misma semilla. Quien rompe uno se lleva algo bueno, algo inútil, algo malo o
 * un evento que le pasa solo a él.
 *
 * <p>Nunca dan algo que pida la partida; lo más cerca es una <b>parte</b> de la receta de algo que le falta a su equipo.
 * Se colocan revisando cada segundo las celdas alrededor de los jugadores (no con eventos de chunks), así no importa si el
 * terreno ya estaba generado por el precalentado.
 */
public final class BloquesSuerte {
	public static final ResourceKey<Block> CLAVE =
		ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(Venatio.MOD_ID, "bloque_suerte"));
	/** No suelta nada, no lo mueven los pistones y las explosiones no lo rompen: solo se abre a mano. */
	public static final Block BLOQUE = Registry.register(BuiltInRegistries.BLOCK, CLAVE, new BloqueSuerte(BlockBehaviour.Properties.of()
		.setId(CLAVE).mapColor(MapColor.GOLD).strength(0.6f, 3_600_000f).sound(SoundType.AMETHYST).lightLevel(s -> 11)
		.pushReaction(PushReaction.IMMOVEABLE).noLootTable()));

	/** Lado de cada celda, en chunks: un bloque por celda (128 bloques). */
	private static final int CELDA = 8;
	/** Lugares que se prueban en el chunk elegido antes de dejar la celda sin bloque (por ejemplo, en pleno océano). */
	private static final int INTENTOS = 16;
	/** Sin bloques pegados al punto de aparición de un equipo: nadie empieza con uno a los pies. */
	private static final int LEJOS_DEL_SPAWN = 24;
	private static final int MECHA_TNT = 100;

	public enum Tipo { BUENO, INUTIL, MALO, EVENTO }

	/** Los eventos que se pueden aplicar a un solo jugador: noche, tormenta e intercambio afectarían a otros. */
	public static final List<TipoEvento> EVENTOS = List.of(TipoEvento.VELOCIDAD, TipoEvento.TELETRANSPORTE, TipoEvento.HORDA,
		TipoEvento.LLUVIA, TipoEvento.LUNAR, TipoEvento.NIEBLA, TipoEvento.MERCADER, TipoEvento.BRILLO, TipoEvento.EXPERIENCIA);

	private record Premio(Item item, int cantidad) {}

	private static final Premio[] BUENOS = {
		new Premio(Items.IRON_INGOT, 6), new Premio(Items.GOLD_INGOT, 4), new Premio(Items.DIAMOND, 2), new Premio(Items.BREAD, 6),
		new Premio(Items.COOKED_BEEF, 6), new Premio(Items.ENDER_PEARL, 2), new Premio(Items.GOLDEN_APPLE, 1), new Premio(Items.COAL, 8),
	};
	private static final Premio[] INUTILES = {
		new Premio(Items.DIRT, 1), new Premio(Items.WHEAT_SEEDS, 1), new Premio(Items.STICK, 1), new Premio(Items.DEAD_BUSH, 1),
		new Premio(Items.ROTTEN_FLESH, 1), new Premio(Items.POISONOUS_POTATO, 1),
	};
	private static final int NOMBRES_GALLINA = 4;

	/** Pruebas: obliga el tipo del próximo bloque y qué evento sale (null = al azar). */
	public static Tipo forzarTipo;
	public static TipoEvento forzarEvento;

	private final Partida partida;
	private final RandomSource rnd = RandomSource.create();
	private final Set<Long> revisadas = new HashSet<>();
	private final List<BlockPos> colocados = new ArrayList<>();
	/** Para las pruebas: qué salió en el último bloque abierto. */
	private String ultimo;

	BloquesSuerte(Partida partida) {
		this.partida = partida;
	}

	public static void registrar() {
		PlayerBlockBreakEvents.AFTER.register((nivel, jugador, pos, estado, entidad) -> {
			if (!estado.is(BLOQUE) || !(jugador instanceof ServerPlayer p)) return;
			Partida partida = Juego.INSTANCIA.partida();
			if (partida != null) partida.suerte.abrir(p, pos);
		});
	}

	public List<BlockPos> colocados() {
		return List.copyOf(colocados);
	}

	public String ultimo() {
		return ultimo;
	}

	public boolean pideLaPartida(ItemStack st) {
		return partida.eventos.pideLaPartida(st);
	}

	// ---------------------------------------------------------------- colocación

	void alSegundo() {
		if (!partida.opcion(Opcion.SUERTE)) return;
		ServerLevel mundo = partida.mundos.mundo();
		int radio = mundo.getServer().getPlayerList().getViewDistance() / CELDA + 1;
		for (ServerPlayer p : partida.jugadoresEnLinea()) {
			if (p.level() != mundo) continue;
			int cx = Math.floorDiv(p.getBlockX() >> 4, CELDA), cz = Math.floorDiv(p.getBlockZ() >> 4, CELDA);
			for (int dx = -radio; dx <= radio; dx++) {
				for (int dz = -radio; dz <= radio; dz++) revisar(mundo, cx + dx, cz + dz);
			}
		}
	}

	/** Coloca el bloque de la celda si su chunk ya existe. Cada celda se decide una sola vez: uno abierto no vuelve a salir. */
	private void revisar(ServerLevel mundo, int cx, int cz) {
		long clave = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
		if (revisadas.contains(clave)) return;
		RandomSource r = RandomSource.create(partida.semilla ^ (cx * 341873128712L) ^ (cz * 132897987541L));
		int chunkX = cx * CELDA + r.nextInt(CELDA), chunkZ = cz * CELDA + r.nextInt(CELDA);
		if (mundo.getChunkSource().getChunkNow(chunkX, chunkZ) == null) return;
		revisadas.add(clave);
		for (int i = 0; i < INTENTOS; i++) {
			int x = (chunkX << 4) + r.nextInt(16), z = (chunkZ << 4) + r.nextInt(16);
			BlockPos pos = new BlockPos(x, mundo.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
			if (sirve(mundo, pos)) {
				mundo.setBlock(pos, BLOQUE.defaultBlockState(), Block.UPDATE_ALL);
				colocados.add(pos);
				return;
			}
		}
	}

	/** A cielo abierto (sin hojas encima), con aire donde va y sobre suelo firme: ni en el agua ni colgado de un árbol. */
	private boolean sirve(ServerLevel mundo, BlockPos pos) {
		if (mundo.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) != pos.getY()) return false;
		if (!mundo.getBlockState(pos).isAir() || !mundo.getBlockState(pos.above()).isAir()) return false;
		BlockPos abajo = pos.below();
		BlockState suelo = mundo.getBlockState(abajo);
		if (!suelo.getFluidState().isEmpty() || !suelo.isFaceSturdy(mundo, abajo, Direction.UP)) return false;
		return !partida.cercaDeUnSpawn(pos, LEJOS_DEL_SPAWN);
	}

	// ---------------------------------------------------------------- abrir

	private void abrir(ServerPlayer p, BlockPos pos) {
		if (partida.fase() != Partida.Fase.JUGANDO || !partida.participa(p) || p.level() != partida.mundos.mundo()) return;
		Tipo tipo = forzarTipo != null ? forzarTipo : sortear();
		ServerLevel mundo = p.level();
		mundo.sendParticles(ParticleTypes.FIREWORK, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 25, 0.3, 0.3, 0.3, 0.08);
		SoundEvent sonido = switch (tipo) {
			case BUENO -> SoundEvents.PLAYER_LEVELUP;
			case INUTIL -> SoundEvents.VILLAGER_NO;
			case MALO -> SoundEvents.WITCH_CELEBRATE;
			case EVENTO -> SoundEvents.ENCHANTMENT_TABLE_USE;
		};
		mundo.playSound(null, pos, sonido, SoundSource.BLOCKS, 1f, 1f);
		switch (tipo) {
			case BUENO -> bueno(p);
			case INUTIL -> inutil(p, pos);
			case MALO -> malo(p, pos);
			case EVENTO -> evento(p);
		}
	}

	private Tipo sortear() {
		int r = rnd.nextInt(100);
		return r < 35 ? Tipo.BUENO : r < 60 ? Tipo.INUTIL : r < 85 ? Tipo.MALO : Tipo.EVENTO;
	}

	private void bueno(ServerPlayer p) {
		int r = rnd.nextInt(100);
		if (r < 40) {
			ItemStack parte = parteDeCrafteo(p);
			if (!parte.isEmpty()) {
				Component nombre = nombre(parte);
				dar(p, parte);
				ultimo = "parte";
				avisar(p, Component.translatable("venatio.suerte.parte", nombre), ChatFormatting.GREEN);
				return;
			}
		} else if (r < 50) {
			int segundos = Ayudas.regalarMinimapa(p);
			if (segundos > 0) {
				ultimo = "minimapa";
				avisar(p, Component.translatable("venatio.suerte.minimapa", String.format("%d:%02d", segundos / 60, segundos % 60)),
					ChatFormatting.GREEN);
				return;
			}
		} else if (r < 60 && !pideLaPartida(new ItemStack(Items.IRON_PICKAXE))) {
			ItemStack pico = new ItemStack(Items.IRON_PICKAXE);
			pico.enchant(p.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY), 3);
			Component nombre = nombre(pico);
			dar(p, pico);
			ultimo = "herramienta";
			avisar(p, Component.translatable("venatio.suerte.bueno", nombre), ChatFormatting.GREEN);
			return;
		}
		ItemStack premio = premioDe(BUENOS);
		if (premio.isEmpty()) {
			nada(p);
			return;
		}
		Component nombre = nombre(premio);
		dar(p, premio);
		ultimo = "premio";
		avisar(p, Component.translatable("venatio.suerte.bueno", nombre), ChatFormatting.GREEN);
	}

	private void inutil(ServerPlayer p, BlockPos pos) {
		int r = rnd.nextInt(100);
		if (r < 20) {
			nada(p);
		} else if (r < 40) {
			Chicken gallina = EntityTypes.CHICKEN.spawn(p.level(), pos, EntitySpawnReason.EVENT);
			if (gallina != null) {
				gallina.setCustomName(Component.translatable("venatio.suerte.gallina." + (1 + rnd.nextInt(NOMBRES_GALLINA))));
				gallina.setCustomNameVisible(true);
			}
			ultimo = "gallina";
			avisar(p, Component.translatable("venatio.suerte.gallina"), ChatFormatting.GRAY);
		} else {
			ItemStack premio = premioDe(INUTILES);
			if (premio.isEmpty()) {
				nada(p);
				return;
			}
			Component nombre = nombre(premio);
			dar(p, premio);
			ultimo = "inutil";
			avisar(p, Component.translatable("venatio.suerte.inutil", nombre), ChatFormatting.GRAY);
		}
	}

	private void nada(ServerPlayer p) {
		ultimo = "nada";
		avisar(p, Component.translatable("venatio.suerte.nada"), ChatFormatting.GRAY);
	}

	/** Molesta pero no mata de golpe: la TNT tiene 5 s de mecha para alcanzar a huir. */
	private void malo(ServerPlayer p, BlockPos pos) {
		switch (rnd.nextInt(6)) {
			case 0 -> {
				partida.eventos.monstruosPara(p);
				aviso(p, "monstruos");
			}
			case 1 -> {
				telaranas(p);
				aviso(p, "telaranas");
			}
			case 2 -> efecto(p, MobEffects.BLINDNESS, 8, 0, "ceguera");
			case 3 -> efecto(p, MobEffects.SLOWNESS, 20, 1, "lentitud");
			case 4 -> efecto(p, MobEffects.HUNGER, 20, 1, "hambre");
			default -> {
				PrimedTnt tnt = new PrimedTnt(p.level(), pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, null);
				tnt.setFuse(MECHA_TNT);
				p.level().addFreshEntity(tnt);
				aviso(p, "tnt");
			}
		}
	}

	private void aviso(ServerPlayer p, String id) {
		ultimo = id;
		avisar(p, Component.translatable("venatio.suerte.malo." + id), ChatFormatting.RED);
	}

	private void efecto(ServerPlayer p, Holder<MobEffect> efecto, int segundos, int nivel, String id) {
		p.addEffect(new MobEffectInstance(efecto, segundos * 20, nivel, false, true, true));
		aviso(p, id);
	}

	/** Telarañas alrededor de los pies, solo donde hay aire. */
	private static void telaranas(ServerPlayer p) {
		BlockPos centro = p.blockPosition();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dy = 0; dy <= 1; dy++) {
					BlockPos pos = centro.offset(dx, dy, dz);
					if (p.level().getBlockState(pos).isAir()) p.level().setBlock(pos, Blocks.COBWEB.defaultBlockState(), Block.UPDATE_ALL);
				}
			}
		}
	}

	private void evento(ServerPlayer p) {
		TipoEvento t = forzarEvento != null ? forzarEvento : EVENTOS.get(rnd.nextInt(EVENTOS.size()));
		ultimo = "evento:" + t.id;
		avisar(p, Component.translatable("venatio.suerte.evento", t.nombre()), ChatFormatting.LIGHT_PURPLE);
		partida.eventos.aplicarSoloA(t, p);
	}

	// ---------------------------------------------------------------- premios

	/**
	 * Una parte de la receta de mesa de algo que le falta a su equipo: un solo tipo de ingrediente, y la mitad si la receta
	 * usa uno solo. Recetas de una sola pieza no cuentan (sería casi regalar el item) y nunca da algo que pida la partida.
	 */
	public ItemStack parteDeCrafteo(ServerPlayer p) {
		List<Item> faltan = pendientes(partida.equipoDe(p));
		Collections.shuffle(faltan, new Random(rnd.nextLong()));
		ContextMap contexto = SlotDisplayContext.fromLevel(p.level());
		for (Item item : faltan) {
			for (RecipeDisplay receta : Fichas.recetasDe(item)) {
				List<SlotDisplay> casillas = switch (receta) {
					case ShapedCraftingRecipeDisplay d -> d.ingredients();
					case ShapelessCraftingRecipeDisplay d -> d.ingredients();
					default -> List.of();
				};
				ItemStack parte = parteDe(casillas, contexto);
				if (!parte.isEmpty()) return parte;
			}
		}
		return ItemStack.EMPTY;
	}

	private ItemStack parteDe(List<SlotDisplay> casillas, ContextMap contexto) {
		Map<SlotDisplay, Integer> cuenta = new LinkedHashMap<>();
		int piezas = 0;
		for (SlotDisplay casilla : casillas) {
			if (casilla.resolveForStacks(contexto).isEmpty()) continue;
			cuenta.merge(casilla, 1, Integer::sum);
			piezas++;
		}
		if (piezas < 2) return ItemStack.EMPTY;
		List<SlotDisplay> tipos = new ArrayList<>(cuenta.keySet());
		Collections.shuffle(tipos, new Random(rnd.nextLong()));
		for (SlotDisplay tipo : tipos) {
			List<Item> opciones = tipo.resolveForStacks(contexto).stream().filter(s -> !s.isEmpty()).map(ItemStack::getItem).distinct()
				.filter(i -> !pideLaPartida(new ItemStack(i))).toList();
			if (opciones.isEmpty()) continue;
			int n = cuenta.size() == 1 ? Math.max(1, cuenta.get(tipo) / 2) : cuenta.get(tipo);
			return new ItemStack(opciones.get(rnd.nextInt(opciones.size())), n);
		}
		return ItemStack.EMPTY;
	}

	/** Items que todavía le faltan al equipo: casillas sin conseguir (en Lockout, sin dueño) o su item actual del Rastreo. */
	private List<Item> pendientes(ColorEquipo equipo) {
		List<Item> lista = new ArrayList<>();
		if (partida.rastreo != null) {
			agregar(lista, partida.rastreo.actual(equipo));
			return lista;
		}
		for (Carton.Casilla cas : partida.carton.casillas) {
			boolean tomada = partida.cfg.modo.exclusivo() ? !cas.logros.isEmpty() : cas.logros.containsKey(equipo);
			if (!tomada) agregar(lista, cas.objetivo);
		}
		return lista;
	}

	private static void agregar(List<Item> lista, Objetivo o) {
		if (o != null && !o.esReto()) lista.add(o.itemFicha());
	}

	/** El primero de la tabla, en orden al azar, que no pida la partida; vacío si todos los pide. */
	private ItemStack premioDe(Premio[] tabla) {
		List<Premio> orden = new ArrayList<>(Arrays.asList(tabla));
		Collections.shuffle(orden, new Random(rnd.nextLong()));
		for (Premio premio : orden) {
			ItemStack st = new ItemStack(premio.item(), premio.cantidad());
			if (!pideLaPartida(st)) return st;
		}
		return ItemStack.EMPTY;
	}

	private static Component nombre(ItemStack st) {
		return st.getCount() > 1 ? Component.translatable("venatio.carton.cantidad", st.getCount(), st.getHoverName()) : st.getHoverName();
	}

	/** Directo al inventario de quien lo abrió (si está lleno, cae a sus pies). */
	private static void dar(ServerPlayer p, ItemStack st) {
		p.getInventory().placeItemBackInInventory(st, Prediction.SERVER_ONLY);
	}

	private static void avisar(ServerPlayer p, Component mensaje, ChatFormatting color) {
		Component texto = mensaje.copy().withStyle(color);
		p.sendSystemMessage(texto);
		p.sendOverlayMessage(texto);
	}
}
