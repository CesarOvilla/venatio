package dev.venatio.juego;

import dev.venatio.carton.Carton;
import dev.venatio.mundo.MundosPartida;
import dev.venatio.red.PaquetesEventos;
import dev.venatio.red.PaquetesStats;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Eventos sorpresa de una partida (opción "eventos"): cada {@link Configuracion#eventosMinutos} minutos, con algo de azar,
 * se anuncia uno 10 s antes y después le pasa a todos. El anfitrión puede lanzar uno a mano con /venatio evento.
 * Solo corre en la fase JUGANDO: nunca en el lobby ni en la cuenta atrás.
 *
 * <p>También guarda lo que va a la línea de tiempo de la pantalla final sin ser casilla: los eventos y, en Switcheroo,
 * los items que cambiaron sin que nadie los consiguiera.
 */
public final class Eventos {
	/** Segundos entre el anuncio y el evento. */
	public static final int ANUNCIO = 10;
	/** Un evento automático no sale antes de este segundo de partida. */
	private static final int PRIMERO_DESDE = 60;
	/** Antes de este segundo de partida la horda no trae creepers. */
	private static final int CREEPERS_DESDE = 60;
	/** Cuánto se ve en el HUD un evento que pasa de una vez. */
	private static final int MOSTRAR_INSTANTANEO = 5;
	/** Si al llegar su hora el evento no se puede (alguien a medio salto para el intercambio…), se espera hasta estos segundos. */
	private static final int ESPERA_MAXIMA = 5;
	private static final int DIA = 24000, MEDIANOCHE = 18000;
	/** Daño de un rayo a un participante (vanilla: 5) y lo más que dura encendido después (vanilla: 8 s). */
	private static final float DANO_RAYO = 3f;
	private static final int FUEGO_RAYO_MAX = 40;
	/** Para no volver a recortar el daño que aplicamos nosotros mismos. */
	private static boolean aplicandoRayo;
	/** Pruebas: obliga el destino del próximo teletransporte (null = al azar). */
	public static LugarSeguro.Destino forzarDestino;

	/** Comida, hierro, carbón y troncos: de cada fila cae el primero que no pida la partida, en tantas piezas de tanto. */
	private static final Item[][] LLUVIA = {
		{Items.BREAD, Items.COOKED_BEEF, Items.BAKED_POTATO, Items.COOKED_PORKCHOP},
		{Items.IRON_INGOT, Items.RAW_IRON, Items.COPPER_INGOT},
		{Items.COAL, Items.CHARCOAL},
		{Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG},
	};
	private static final int[][] LLUVIA_PIEZAS = {{4, 1}, {3, 1}, {3, 2}, {3, 2}};

	/** Algo de la línea de tiempo que no es casilla. quien: clave de idioma de lo que va en la columna del jugador. */
	record Suceso(int segundo, ItemStack icono, String quien) {}

	private final Partida partida;
	private final RandomSource rnd = RandomSource.create();
	/** Segundo de partida en que se anuncia el próximo evento automático (-1 = sin calcular). */
	private int proximo = -1;
	private TipoEvento anunciado, activo, ultimo;
	/** anunciado: segundo en que empieza. activo: cuándo empezó y cuándo deja de mostrarse. */
	private int empieza, empezo, termina;
	/** Segundos que lleva esperando el anunciado a poder empezar. */
	private int esperando;
	private final List<Suceso> sucesos = new ArrayList<>();
	/** Noche eterna: tiempo del reloj del mundo al empezar (-1 si no hay noche en curso). */
	private long relojAntes = -1;
	/** Para las pruebas: a dónde mandó el último teletransporte a cada jugador. */
	private final Map<UUID, BlockPos> destinos = new HashMap<>();
	/** Tormenta: jugador → tick del servidor en que le cayó el último rayo. */
	private final Map<UUID, Integer> rayos = new HashMap<>();

	Eventos(Partida partida) {
		this.partida = partida;
	}

	/**
	 * Engancha lo que no depende de una partida: en la partida los rayos lastiman, pero moderado. Hacen 1.5 corazones
	 * (en vez de 2.5) y el jugador arde como mucho 2 s (en vez de 8), así que un rayo cuesta unos 2.5 corazones en
	 * total. El fuego que dejan en el suelo es el normal.
	 */
	public static void registrar() {
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entidad, fuente, cantidad) -> {
			if (aplicandoRayo || !(entidad instanceof ServerPlayer p) || !fuente.is(DamageTypes.LIGHTNING_BOLT)) return true;
			Partida partida = Juego.INSTANCIA.partida();
			if (partida == null || !partida.participa(p)) return true;
			if (p.getRemainingFireTicks() > FUEGO_RAYO_MAX) p.setRemainingFireTicks(FUEGO_RAYO_MAX);
			if (cantidad <= DANO_RAYO) return true;
			aplicandoRayo = true;
			try {
				p.hurtServer(p.level(), fuente, DANO_RAYO);
			} finally {
				aplicandoRayo = false;
			}
			return false;
		});
	}

	/** /venatio evento &lt;id&gt;: el anfitrión lanza uno a mano, con su anuncio de 10 s. Devuelve el error, o null. */
	public static Component lanzarDesdeComando(TipoEvento t) {
		Partida p = Juego.INSTANCIA.partida();
		return p == null ? Component.translatable("venatio.error.sin_partida") : p.eventos.lanzar(t, true);
	}

	// ---------------------------------------------------------------- consultas

	public TipoEvento activo() {
		return activo;
	}

	public TipoEvento anunciado() {
		return anunciado;
	}

	/** Para las pruebas: a dónde mandó el último teletransporte a este jugador, o null. */
	public BlockPos destinoTeletransporte(UUID jugador) {
		return destinos.get(jugador);
	}

	/** Sucesos para la línea de tiempo de la pantalla final (casilla -1 y equipo -1; jugador = clave de idioma). */
	public List<PaquetesStats.Hito> hitosFinales() {
		List<PaquetesStats.Hito> lista = new ArrayList<>();
		for (Suceso s : sucesos) lista.add(new PaquetesStats.Hito(-1, s.icono.copy(), s.quien, -1, s.segundo));
		return lista;
	}

	/** Para las pruebas: el evento anunciado empieza en el próximo segundo, sin esperar los 10 s. */
	public void adelantarAnuncio() {
		if (anunciado != null) empieza = partida.segundos();
	}

	/** Para las pruebas: el próximo evento automático se anuncia dentro de esos segundos. */
	public void programar(int enSegundos) {
		proximo = partida.segundos() + enSegundos;
	}

	/** Switcheroo: el item cambió sin que nadie lo consiguiera. */
	void anotarVencido(ItemStack icono, int segundo) {
		sucesos.add(new Suceso(segundo, icono.copyWithCount(1), "venatio.switcheroo.linea_nadie"));
	}

	// ---------------------------------------------------------------- ciclo

	/** Cada segundo de juego. */
	void alSegundo() {
		if (partida.fase() != Partida.Fase.JUGANDO) return;
		int s = partida.segundos();
		if (activo != null && s >= termina) terminarActivo();
		if (anunciado != null && s >= empieza) empezar();
		if (anunciado == null && activo == null && partida.opcion(Opcion.EVENTOS)) {
			if (proximo < 0) {
				int base = partida.cfg.eventosMinutos * 60;
				proximo = Math.max(PRIMERO_DESDE, s + Math.round(base * (0.75f + rnd.nextFloat() * 0.5f)));
			} else if (s >= proximo) {
				proximo = -1;
				TipoEvento t = elegir();
				if (t != null) anunciar(t);
			}
		}
		enviarHud();
	}

	/** Lanza un evento ya (con anuncio de 10 s o sin él). Si había otro, lo termina antes. Devuelve el error, o null. */
	public Component lanzar(TipoEvento t, boolean conAnuncio) {
		if (partida.fase() != Partida.Fase.JUGANDO) return Component.translatable("venatio.evento.error.no_jugando");
		Component error = noDisponible(t);
		if (error != null) return Component.translatable("venatio.evento.error.no_se_puede", t.nombre(), error);
		if (activo != null) terminarActivo();
		anunciado = null;
		proximo = -1;
		if (conAnuncio) anunciar(t);
		else {
			anunciado = t;
			esperando = 0;
			empezar();
		}
		enviarHud();
		return null;
	}

	/** Al terminar la partida: se deshace lo que dura (noche, tormenta) y no empieza nada más. */
	void terminar() {
		if (activo != null) quitar(activo);
		activo = null;
		anunciado = null;
		Juego.INSTANCIA.enviarEvento(PaquetesEventos.Sorpresas.NADA);
	}

	private TipoEvento elegir() {
		List<TipoEvento> opciones = new ArrayList<>();
		for (TipoEvento t : TipoEvento.values()) if (t != ultimo && noDisponible(t) == null) opciones.add(t);
		return opciones.isEmpty() ? null : opciones.get(rnd.nextInt(opciones.size()));
	}

	private void anunciar(TipoEvento t) {
		anunciado = t;
		esperando = 0;
		empieza = partida.segundos() + ANUNCIO;
		Juego j = Juego.INSTANCIA;
		j.difundir(Component.translatable("venatio.evento.anuncio", t.nombre().withStyle(ChatFormatting.GOLD), ANUNCIO, t.descripcion())
			.withStyle(ChatFormatting.YELLOW));
		j.enviarEvento(new PaquetesEventos.Aviso(PaquetesEventos.Aviso.ANUNCIO, -1, ItemStack.EMPTY, t.id));
	}

	private void empezar() {
		TipoEvento t = anunciado;
		Juego j = Juego.INSTANCIA;
		// En los 10 s del anuncio pudo cambiar quién está dónde (alguien se fue al Nether, saltó, se desconectó…):
		// se espera unos segundos y, si sigue sin poderse, se cancela.
		Component error = noDisponible(t);
		if (error != null && esperando < ESPERA_MAXIMA) {
			esperando++;
			empieza = partida.segundos() + 1;
			return;
		}
		anunciado = null;
		if (error != null) {
			j.difundir(Component.translatable("venatio.evento.cancelado", t.nombre(), error).withStyle(ChatFormatting.GRAY));
			return;
		}
		int s = partida.segundos();
		activo = t;
		ultimo = t;
		empezo = s;
		termina = s + Math.max(t.segundos, MOSTRAR_INSTANTANEO);
		aplicar(t);
		sucesos.add(new Suceso(s, t.iconoConNombre(), "venatio.evento.linea"));
		j.difundir(Component.translatable("venatio.evento.empieza", t.nombre().withStyle(ChatFormatting.GOLD), t.descripcion())
			.withStyle(ChatFormatting.YELLOW));
		j.enviarEvento(new PaquetesEventos.Aviso(PaquetesEventos.Aviso.EMPIEZA, -1, ItemStack.EMPTY, t.id));
	}

	private void terminarActivo() {
		TipoEvento t = activo;
		activo = null;
		quitar(t);
		if (t.segundos > 0) {
			Juego.INSTANCIA.difundir(Component.translatable("venatio.evento.termina", t.nombre()).withStyle(ChatFormatting.GRAY));
			Juego.INSTANCIA.enviarEvento(new PaquetesEventos.Aviso(PaquetesEventos.Aviso.TERMINA, -1, ItemStack.EMPTY, t.id));
		}
	}

	/** Lo que necesita el HUD: la cuenta de Switcheroo y el evento anunciado o en curso. */
	void enviarHud() {
		int cambio = -1, duracion = 0;
		if (partida.cfg.modo == Modo.SWITCHEROO && partida.rastreo != null) {
			cambio = Switcheroo.cambioEn(partida);
			duracion = Switcheroo.duracion(partida);
		}
		String id = "";
		int desde = -1, hasta = -1;
		if (anunciado != null) {
			id = anunciado.id;
			desde = empieza;
			hasta = empieza + Math.max(anunciado.segundos, MOSTRAR_INSTANTANEO);
		} else if (activo != null) {
			id = activo.id;
			desde = empezo;
			hasta = termina;
		}
		Juego.INSTANCIA.enviarEvento(new PaquetesEventos.Sorpresas(cambio, duracion, id, desde, hasta));
	}

	// ---------------------------------------------------------------- quién juega

	/** Participantes conectados, vivos y dentro de los mundos de la partida. */
	private List<ServerPlayer> jugadores() {
		List<ServerPlayer> lista = new ArrayList<>();
		for (ServerPlayer p : partida.jugadoresEnLinea()) {
			if (p.isAlive() && !p.isSpectator() && MundosPartida.esDePartida(p.level())) lista.add(p);
		}
		return lista;
	}

	/** Los del mundo normal de la partida: los únicos a los que les tocan el teletransporte, la horda y el mercader. */
	private List<ServerPlayer> enMundoNormal() {
		List<ServerPlayer> lista = new ArrayList<>();
		for (ServerPlayer p : jugadores()) if (p.level() == partida.mundos.mundo()) lista.add(p);
		return lista;
	}

	/** Por qué no se puede lanzar ahora, o null si se puede. */
	private Component noDisponible(TipoEvento t) {
		if (jugadores().isEmpty()) return Component.translatable("venatio.evento.error.sin_jugadores");
		return switch (t) {
			case TELETRANSPORTE, HORDA, MERCADER -> enMundoNormal().isEmpty() ? Component.translatable("venatio.evento.error.mundo_normal") : null;
			case INTERCAMBIO -> pareja() == null ? Component.translatable("venatio.evento.error.intercambio") : null;
			default -> null;
		};
	}

	// ---------------------------------------------------------------- efectos

	private void aplicar(TipoEvento t) {
		switch (t) {
			case NOCHE -> noche(true);
			case VELOCIDAD -> efectos(t, MobEffects.SPEED, 1, MobEffects.HASTE, 1);
			case TELETRANSPORTE -> teletransportar();
			case INTERCAMBIO -> intercambiar();
			case HORDA -> horda();
			case LLUVIA -> lluvia();
			case LUNAR -> efectos(t, MobEffects.JUMP_BOOST, 2, MobEffects.SLOW_FALLING, 0);
			case NIEBLA -> efectos(t, MobEffects.DARKNESS, 0, null, 0);
			case TORMENTA -> partida.mundos.mundo().getServer().setWeatherParameters(0, t.segundos * 20 + 100, true, true);
			case MERCADER -> mercaderes();
			case BRILLO -> efectos(t, MobEffects.GLOWING, 0, null, 0);
			case EXPERIENCIA -> experiencia();
		}
	}

	/** Deshace lo que hay que deshacer al terminar. Los efectos de estado se acaban solos a la vez que el evento. */
	private void quitar(TipoEvento t) {
		switch (t) {
			case NOCHE -> noche(false);
			case TORMENTA -> partida.mundos.mundo().getServer().setWeatherParameters(6000, 0, false, false);
			default -> {}
		}
	}

	private void efectos(TipoEvento t, Holder<MobEffect> a, int nivelA, Holder<MobEffect> b, int nivelB) {
		for (ServerPlayer p : jugadores()) efectosA(p, t, a, nivelA, b, nivelB);
	}

	private static void efectosA(ServerPlayer p, TipoEvento t, Holder<MobEffect> a, int nivelA, Holder<MobEffect> b, int nivelB) {
		p.addEffect(new MobEffectInstance(a, t.segundos * 20, nivelA, false, true, true));
		if (b != null) p.addEffect(new MobEffectInstance(b, t.segundos * 20, nivelB, false, true, true));
	}

	/**
	 * Bloques de la suerte: el evento le pasa solo a quien abrió el bloque. No entra al calendario de eventos ni al HUD.
	 * Noche, tormenta e intercambio no están porque afectarían a más jugadores ({@link BloquesSuerte#EVENTOS}).
	 */
	void aplicarSoloA(TipoEvento t, ServerPlayer p) {
		switch (t) {
			case VELOCIDAD -> efectosA(p, t, MobEffects.SPEED, 1, MobEffects.HASTE, 1);
			case LUNAR -> efectosA(p, t, MobEffects.JUMP_BOOST, 2, MobEffects.SLOW_FALLING, 0);
			case NIEBLA -> efectosA(p, t, MobEffects.DARKNESS, 0, null, 0);
			case BRILLO -> efectosA(p, t, MobEffects.GLOWING, 0, null, 0);
			case TELETRANSPORTE -> teletransportarA(partida.mundos.mundo(), p);
			case HORDA -> hordaA(partida.mundos.mundo(), p, tiposHorda());
			case LLUVIA -> lluviaA(p, premioLluvia());
			case MERCADER -> mercaderA(partida.mundos.mundo(), p);
			case EXPERIENCIA -> experienciaA(p);
			default -> throw new IllegalArgumentException("Evento que no es personal: " + t);
		}
	}

	/**
	 * Noche eterna: el reloj del mundo de la partida (propio de Fantasy, no toca el lobby) salta a medianoche y se
	 * detiene. Al terminar sigue como si la noche no hubiera pasado.
	 */
	private void noche(boolean empieza) {
		ServerLevel mundo = partida.mundos.mundo();
		Optional<Holder<WorldClock>> reloj = mundo.dimensionType().defaultClock();
		if (reloj.isEmpty()) return;
		ServerClockManager relojes = mundo.clockManager();
		if (empieza) {
			long antes = relojes.getInstance(reloj.get()).totalTicks();
			relojAntes = antes;
			long medianoche = antes - Math.floorMod(antes, DIA) + MEDIANOCHE;
			if (medianoche < antes) medianoche += DIA;
			relojes.setTotalTicks(reloj.get(), medianoche);
			relojes.setPaused(reloj.get(), true);
		} else {
			relojes.setPaused(reloj.get(), false);
			if (relojAntes >= 0) relojes.setTotalTicks(reloj.get(), relojAntes + (long) (partida.segundos() - empezo) * 20);
			relojAntes = -1;
		}
	}

	/** Cada jugador del mundo normal, a un punto seguro en superficie a 150–300 bloques. Los del Nether o el End se salvan. */
	private LugarSeguro.Destino sortearDestino() {
		float r = rnd.nextFloat();
		if (r < 0.04f) return LugarSeguro.Destino.LAVA;
		if (r < 0.12f) return LugarSeguro.Destino.AGUA;
		if (r < 0.20f) return LugarSeguro.Destino.ALTURA;
		return LugarSeguro.Destino.SUELO;
	}

	private void teletransportar() {
		ServerLevel mundo = partida.mundos.mundo();
		for (ServerPlayer p : jugadores()) teletransportarA(mundo, p);
	}

	private void teletransportarA(ServerLevel mundo, ServerPlayer p) {
		if (p.level() != mundo) {
			p.sendSystemMessage(Component.translatable("venatio.evento.teletransporte.fuera").withStyle(ChatFormatting.GRAY));
			return;
		}
		// Casi siempre a suelo firme; a veces al agua (8 %), desde lo alto (8 %) o a la lava (4 %, con resistencia
		// al fuego unos segundos para poder salir).
		LugarSeguro.Destino tipo = forzarDestino != null ? forzarDestino : sortearDestino();
		BlockPos destino = LugarSeguro.arriesgado(mundo, p.blockPosition(), 150, 300, rnd, tipo);
		if (destino == null && tipo != LugarSeguro.Destino.SUELO) {
			tipo = LugarSeguro.Destino.SUELO;
			destino = LugarSeguro.lejos(mundo, p.blockPosition(), 150, 300, rnd);
		}
		if (destino == null) {
			p.sendSystemMessage(Component.translatable("venatio.evento.teletransporte.sin_lugar").withStyle(ChatFormatting.GRAY));
			return;
		}
		int distancia = (int) Math.round(Math.sqrt(p.blockPosition().distSqr(destino.atY(p.getBlockY()))));
		if (p.isPassenger()) p.stopRiding();
		p.teleportTo(mundo, destino.getX() + 0.5, destino.getY(), destino.getZ() + 0.5, Set.of(), p.getYRot(), p.getXRot(), true);
		p.resetFallDistance();
		destinos.put(p.getUUID(), destino);
		p.sendSystemMessage(Component.translatable("venatio.evento.teletransporte.llegaste", distancia).withStyle(ChatFormatting.LIGHT_PURPLE));
		if (tipo == LugarSeguro.Destino.LAVA) {
			p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 6 * 20, 0, false, true, true));
		}
		if (tipo != LugarSeguro.Destino.SUELO) {
			Component aviso = Component.translatable("venatio.evento.teletransporte." + tipo.name().toLowerCase(java.util.Locale.ROOT));
			p.sendSystemMessage(aviso.copy().withStyle(ChatFormatting.GOLD));
			p.sendOverlayMessage(aviso);
		}
	}

	/** Dos jugadores de equipos distintos que se pueden mover sin peligro (con los pies en el suelo o en el agua, fuera de la lava); null si no hay. */
	private ServerPlayer[] pareja() {
		List<ServerPlayer> lista = new ArrayList<>();
		for (ServerPlayer p : jugadores()) {
			if (!p.isInLava() && (p.onGround() || p.isInWater() || p.isPassenger())) lista.add(p);
		}
		Collections.shuffle(lista, new java.util.Random(rnd.nextLong()));
		for (ServerPlayer a : lista) {
			for (ServerPlayer b : lista) {
				if (partida.equipoDe(a) != partida.equipoDe(b)) return new ServerPlayer[]{a, b};
			}
		}
		return null;
	}

	private void intercambiar() {
		ServerPlayer[] par = pareja();
		if (par == null) return;
		ServerPlayer a = par[0], b = par[1];
		ServerLevel nivelA = a.level(), nivelB = b.level();
		Vec3 posA = a.position(), posB = b.position();
		float yA = a.getYRot(), xA = a.getXRot(), yB = b.getYRot(), xB = b.getXRot();
		for (ServerPlayer p : par) if (p.isPassenger()) p.stopRiding();
		a.teleportTo(nivelB, posB.x, posB.y, posB.z, Set.of(), yB, xB, true);
		b.teleportTo(nivelA, posA.x, posA.y, posA.z, Set.of(), yA, xA, true);
		a.resetFallDistance();
		b.resetFallDistance();
		Juego.INSTANCIA.difundir(Component.translatable("venatio.evento.intercambio.quienes", a.getDisplayName(), b.getDisplayName())
			.withStyle(ChatFormatting.LIGHT_PURPLE));
	}

	/** 2 o 3 monstruos cerca de cada jugador del mundo normal. En el Nether no cuadran: ahí no salen. */
	private void horda() {
		ServerLevel mundo = partida.mundos.mundo();
		List<EntityType<?>> tipos = tiposHorda();
		for (ServerPlayer p : enMundoNormal()) hordaA(mundo, p, tipos);
	}

	private List<EntityType<?>> tiposHorda() {
		List<EntityType<?>> tipos = new ArrayList<>(List.of(EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.SPIDER));
		if (partida.segundos() >= CREEPERS_DESDE) tipos.add(EntityTypes.CREEPER);
		return tipos;
	}

	private void hordaA(ServerLevel mundo, ServerPlayer p, List<EntityType<?>> tipos) {
		int n = 2 + rnd.nextInt(2);
		for (int i = 0; i < n; i++) {
			BlockPos pos = LugarSeguro.cerca(mundo, p.blockPosition(), 7, 12, rnd);
			if (pos != null) tipos.get(rnd.nextInt(tipos.size())).spawn(mundo, pos, EntitySpawnReason.EVENT);
		}
	}

	/** Bloques de la suerte: unos monstruos alrededor de quien abrió el bloque. */
	void monstruosPara(ServerPlayer p) {
		hordaA(partida.mundos.mundo(), p, tiposHorda());
	}

	/** Comida, hierro, carbón y troncos caen alrededor de cada jugador, sin regalar nada que pida la partida. */
	private void lluvia() {
		List<ItemStack> premio = premioLluvia();
		for (ServerPlayer p : jugadores()) lluviaA(p, premio);
	}

	private List<ItemStack> premioLluvia() {
		List<ItemStack> premio = new ArrayList<>();
		for (int fila = 0; fila < LLUVIA.length; fila++) {
			for (Item item : LLUVIA[fila]) {
				if (pideLaPartida(new ItemStack(item))) continue;
				for (int k = 0; k < LLUVIA_PIEZAS[fila][0]; k++) premio.add(new ItemStack(item, LLUVIA_PIEZAS[fila][1]));
				break;
			}
		}
		return premio;
	}

	private void lluviaA(ServerPlayer p, List<ItemStack> premio) {
		ServerLevel nivel = p.level();
		for (ItemStack st : premio) {
			double x = p.getX() + (rnd.nextDouble() - 0.5) * 4, z = p.getZ() + (rnd.nextDouble() - 0.5) * 4;
			ItemEntity item = new ItemEntity(nivel, x, alturaLibre(nivel, p, x, z), z, st.copy());
			item.setDeltaMovement((rnd.nextDouble() - 0.5) * 0.08, 0, (rnd.nextDouble() - 0.5) * 0.08);
			nivel.addFreshEntity(item);
		}
	}

	/** true si el item cuenta para el cartón o para la lista del Rastreo de esta partida. */
	boolean pideLaPartida(ItemStack st) {
		for (Carton.Casilla cas : partida.carton.casillas) if (cas.objetivo.cumple(st)) return true;
		return partida.rastreo != null && partida.rastreo.pide(st);
	}

	/** Lo más alto (hasta 6 bloques sobre la cabeza) desde donde un item puede caer sin atravesar un techo. */
	private static double alturaLibre(ServerLevel nivel, ServerPlayer p, double x, double z) {
		int bx = Mth.floor(x), bz = Mth.floor(z);
		double y = p.getY() + 1;
		for (int k = 1; k <= 7; k++) {
			BlockPos pos = new BlockPos(bx, p.getBlockY() + k, bz);
			if (!nivel.getBlockState(pos).getCollisionShape(nivel, pos).isEmpty()) break;
			y = p.getBlockY() + k;
		}
		return y;
	}

	/** Un mercader nómada junto a un jugador de cada equipo (en el mundo normal); se va a los 3 minutos. */
	private void mercaderes() {
		ServerLevel mundo = partida.mundos.mundo();
		Map<ColorEquipo, ServerPlayer> uno = new EnumMap<>(ColorEquipo.class);
		for (ServerPlayer p : enMundoNormal()) uno.putIfAbsent(partida.equipoDe(p), p);
		for (ServerPlayer p : uno.values()) mercaderA(mundo, p);
	}

	private void mercaderA(ServerLevel mundo, ServerPlayer p) {
		BlockPos pos = LugarSeguro.cerca(mundo, p.blockPosition(), 3, 6, rnd);
		WanderingTrader mercader = EntityTypes.WANDERING_TRADER.spawn(mundo, pos != null ? pos : p.blockPosition(), EntitySpawnReason.EVENT);
		if (mercader != null) mercader.setDespawnDelay(3 * 60 * 20);
	}

	/** Orbes de experiencia que caen alrededor de cada jugador (unos 5 niveles). */
	private void experiencia() {
		for (ServerPlayer p : jugadores()) experienciaA(p);
	}

	private void experienciaA(ServerPlayer p) {
		for (int i = 0; i < 8; i++) {
			double x = p.getX() + (rnd.nextDouble() - 0.5) * 3, z = p.getZ() + (rnd.nextDouble() - 0.5) * 3;
			ExperienceOrb.award(p.level(), new Vec3(x, alturaLibre(p.level(), p, x, z), z), 7);
		}
	}
}
