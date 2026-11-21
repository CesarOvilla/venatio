package dev.venatio.juego;

import dev.venatio.Venatio;
import dev.venatio.carton.Carton;
import dev.venatio.carton.Objetivo;
import dev.venatio.mundo.MundosPartida;
import dev.venatio.red.Paquetes;
import dev.venatio.stats.ModuloStats;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Una partida: preparación del mundo, cuenta atrás, juego y final. */
public final class Partida {
	public enum Fase { PREPARANDO, CUENTA_ATRAS, JUGANDO, FINAL }

	private static final int SEGUNDOS_CUENTA = 10;
	private static final int SEGUNDOS_FINAL = 15;
	private static final int MAX_SEGUNDOS_PREPARACION = 120;
	/** Radio en chunks que se genera alrededor de cada punto de aparición antes de empezar. */
	private static final int RADIO_PREGEN = 2;

	private final Juego juego;
	final Configuracion cfg;
	final long semilla;
	final Carton carton;
	final MundosPartida mundos;
	final Map<UUID, ColorEquipo> participantes;
	final List<ColorEquipo> equipos;
	private final Map<ColorEquipo, BlockPos> spawns = new EnumMap<>(ColorEquipo.class);
	/** Chunks que tienen que estar generados antes de llevar a los jugadores. */
	private final List<ChunkPos> carga = new ArrayList<>();
	private final Set<ChunkPos> centrosConTicket = new java.util.HashSet<>();

	Fase fase = Fase.PREPARANDO;
	private int ticks;
	private int ticksJuego;
	int progreso;
	ColorEquipo ganador;
	boolean empate;
	/** Con fin automático apagado: el primer equipo que cumplió el objetivo, y quiénes ya fueron anunciados. */
	private ColorEquipo primerGanador;
	private final Set<ColorEquipo> anunciados = java.util.EnumSet.noneOf(ColorEquipo.class);

	/** Solo en modo Rastreo: la lista de items y por cuál va cada equipo. En Rastreo, carton es el historial de encontrados. */
	final Rastreo rastreo;

	Partida(Juego juego, Configuracion cfg, long semilla, Carton carton, Rastreo rastreo, MundosPartida mundos, Map<UUID, ColorEquipo> participantes) {
		this.juego = juego;
		this.rastreo = rastreo;
		this.cfg = cfg;
		this.semilla = semilla;
		this.carton = carton;
		this.mundos = mundos;
		this.participantes = participantes;
		this.equipos = participantes.values().stream().distinct().sorted().toList();
		planificarSpawns();
	}

	public Fase fase() {
		return fase;
	}

	public Rastreo rastreo() {
		return rastreo;
	}

	public Carton carton() {
		return carton;
	}

	public MundosPartida mundos() {
		return mundos;
	}

	public boolean participa(ServerPlayer p) {
		return participantes.containsKey(p.getUUID());
	}

	public ColorEquipo equipoDe(ServerPlayer p) {
		return participantes.get(p.getUUID());
	}

	/** Valor de una opción en la configuración de esta partida (la que se copió al iniciar). */
	public boolean opcion(Opcion o) {
		return o.valor(cfg);
	}

	public int segundos() {
		return ticksJuego / 20;
	}

	// ---------------------------------------------------------------- preparación

	/** Reparte los equipos en un círculo (o todos al centro con PvP) evitando océanos y ríos, y pide generar esos chunks. */
	private void planificarSpawns() {
		ServerLevel mundo = mundos.mundo();
		Random rnd = new Random(semilla);
		int n = equipos.size();
		double angulo0 = rnd.nextDouble() * Math.PI * 2;
		double radio = (cfg.pvp || n < 2) ? 0 : cfg.distancia / (2 * Math.sin(Math.PI / n));
		BlockPos compartido = null;
		for (int i = 0; i < n; i++) {
			BlockPos pos;
			if (radio == 0) {
				if (compartido == null) compartido = buscarTierra(mundo, 0, 0, rnd);
				pos = compartido;
			} else {
				double a = angulo0 + Math.PI * 2 * i / n;
				pos = buscarTierra(mundo, (int) (Math.cos(a) * radio), (int) (Math.sin(a) * radio), rnd);
			}
			spawns.put(equipos.get(i), pos);
		}
		// Un ticket forzado genera los chunks en segundo plano. (getChunkFuture desde el hilo principal
		// bloquea el servidor hasta terminar, y mientras procesa tareas a medias: no usarlo aquí.)
		for (BlockPos pos : Set.copyOf(spawns.values())) {
			ChunkPos centro = ChunkPos.containing(pos);
			centrosConTicket.add(centro);
			mundo.getChunkSource().addTicketWithRadius(TicketType.FORCED, centro, RADIO_PREGEN);
			for (int dx = -RADIO_PREGEN; dx <= RADIO_PREGEN; dx++) {
				for (int dz = -RADIO_PREGEN; dz <= RADIO_PREGEN; dz++) {
					carga.add(new ChunkPos(centro.x() + dx, centro.z() + dz));
				}
			}
		}
	}

	/** Busca cerca de (x, z) un bioma que no sea océano ni río, consultando solo el generador (sin generar chunks). */
	private static BlockPos buscarTierra(ServerLevel mundo, int x, int z, Random rnd) {
		for (int intento = 0; intento < 64; intento++) {
			int alcance = intento * 48;
			int cx = x + (intento == 0 ? 0 : rnd.nextInt(-alcance, alcance + 1));
			int cz = z + (intento == 0 ? 0 : rnd.nextInt(-alcance, alcance + 1));
			Holder<Biome> bioma = mundo.getUncachedNoiseBiome(cx >> 2, 16, cz >> 2);
			if (!bioma.is(BiomeTags.IS_OCEAN) && !bioma.is(BiomeTags.IS_DEEP_OCEAN) && !bioma.is(BiomeTags.IS_RIVER)) {
				return new BlockPos(cx, 0, cz);
			}
		}
		return new BlockPos(x, 0, z);
	}

	// ---------------------------------------------------------------- ciclo

	void tick() {
		ticks++;
		switch (fase) {
			case PREPARANDO -> tickPreparando();
			case CUENTA_ATRAS -> tickCuentaAtras();
			case JUGANDO -> tickJugando();
			case FINAL -> {
				if (ticks >= SEGUNDOS_FINAL * 20) juego.cerrarPartida();
			}
		}
	}

	private void tickPreparando() {
		ServerLevel mundo = mundos.mundo();
		long listos = carga.stream().filter(c -> mundo.getChunkSource().getChunkNow(c.x(), c.z()) != null).count();
		progreso = carga.isEmpty() ? 100 : (int) (listos * 100 / carga.size());
		if (listos == carga.size() || ticks >= MAX_SEGUNDOS_PREPARACION * 20) {
			if (listos < carga.size()) Venatio.LOG.warn("Preparación incompleta ({} de {} chunks); se empieza igual", listos, carga.size());
			empezarCuenta();
		}
	}

	private void empezarCuenta() {
		ServerLevel mundo = mundos.mundo();
		spawns.replaceAll((equipo, pos) -> sueloSeco(mundo, pos));
		for (ServerPlayer p : jugadoresEnLinea()) {
			Jugadores.reiniciar(p, GameType.SURVIVAL);
			llevarAlSpawn(p);
			aplicarKit(p);
			Jugadores.congelar(p, true);
		}
		// Los jugadores ya mantienen cargada su zona; los tickets de pregeneración sobran.
		for (ChunkPos centro : centrosConTicket) mundo.getChunkSource().removeTicketWithRadius(TicketType.FORCED, centro, RADIO_PREGEN);
		centrosConTicket.clear();
		fase = Fase.CUENTA_ATRAS;
		ticks = 0;
		juego.enviarEstadoATodos();
	}

	/**
	 * Primera columna con suelo firme (no agua ni lava) en anillos alrededor del punto elegido, dentro de la zona
	 * ya generada. El bioma descarta océanos y ríos, pero no lagos ni pantanos.
	 */
	private static BlockPos sueloSeco(ServerLevel mundo, BlockPos base) {
		for (int r = 0; r <= 32; r += 2) {
			for (int dx = -r; dx <= r; dx += 2) {
				for (int dz = -r; dz <= r; dz += 2) {
					if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
					int x = base.getX() + dx, z = base.getZ() + dz;
					int y = mundo.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
					if (mundo.getBlockState(new BlockPos(x, y - 1, z)).getFluidState().isEmpty()) return new BlockPos(x, y, z);
				}
			}
		}
		return new BlockPos(base.getX(), mundo.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base.getX(), base.getZ()), base.getZ());
	}

	private void tickCuentaAtras() {
		if (ticks % 20 == 1) {
			int restantes = SEGUNDOS_CUENTA - ticks / 20;
			if (restantes > 0) {
				Component numero = Component.literal(String.valueOf(restantes));
				for (ServerPlayer p : jugadoresEnLinea()) {
					Jugadores.titulo(p, numero, Component.translatable("venatio.cuenta.subtitulo", cfg.modo.nombre()), 0, 25, 0);
				}
			}
		}
		if (ticks >= SEGUNDOS_CUENTA * 20) {
			for (ServerPlayer p : jugadoresEnLinea()) {
				Jugadores.congelar(p, false);
				Jugadores.titulo(p, Component.translatable("venatio.cuenta.ya"), null, 0, 30, 10);
			}
			fase = Fase.JUGANDO;
			ticks = 0;
			juego.enviarEvento(new Paquetes.Evento(Paquetes.Evento.INICIO, -1, -1));
			juego.enviarEstadoATodos();
		}
	}

	private void tickJugando() {
		ticksJuego++;
		if (ticksJuego % 10 == 0) detectar();
		int limite = cfg.limiteSegundos();
		if (fase == Fase.JUGANDO && limite > 0 && segundos() >= limite) terminarPorTiempo();
	}

	// ---------------------------------------------------------------- detección

	private void detectar() {
		if (rastreo != null) {
			detectarRastreo();
			return;
		}
		for (ServerPlayer p : jugadoresEnLinea()) {
			ColorEquipo equipo = equipoDe(p);
			if (equipo == null || p.isSpectator() || !MundosPartida.esDePartida(p.level())) continue;
			for (int i = 0; i < carton.casillas.size() && fase == Fase.JUGANDO; i++) {
				Carton.Casilla cas = carton.casillas.get(i);
				if (cas.logros.containsKey(equipo)) continue;
				if (cfg.modo.exclusivo() && !cas.logros.isEmpty()) continue;
				ItemStack encontrado = buscar(p, cas);
				if (encontrado != null) marcar(i, equipo, p, encontrado);
			}
		}
	}

	private static ItemStack buscar(ServerPlayer p, Carton.Casilla cas) {
		return buscar(p, cas.objetivo);
	}

	private static ItemStack buscar(ServerPlayer p, Objetivo objetivo) {
		Inventory inv = p.getInventory();
		for (int s = 0; s < inv.getContainerSize(); s++) {
			ItemStack st = inv.getItem(s);
			if (objetivo.cumple(st)) return st;
		}
		ItemStack enCursor = p.containerMenu.getCarried();
		return objetivo.cumple(enCursor) ? enCursor : null;
	}

	private void detectarRastreo() {
		for (ServerPlayer p : jugadoresEnLinea()) {
			ColorEquipo equipo = equipoDe(p);
			if (equipo == null || p.isSpectator() || !MundosPartida.esDePartida(p.level()) || fase != Fase.JUGANDO) continue;
			Objetivo objetivo = rastreo.actual(equipo);
			ItemStack encontrado = buscar(p, objetivo);
			if (encontrado == null) continue;
			carton.anotar(objetivo, equipo, segundos(), p.getUUID());
			rastreo.avanzar(equipo);
			juego.difundir(Component.translatable("venatio.rastreo.encontrado", p.getDisplayName(), encontrado.getHoverName(), equipo.nombre()));
			juego.enviarEvento(new Paquetes.Evento(Paquetes.Evento.CASILLA, equipo.ordinal(), 0));
			juego.enviarEstadoATodos();
			comprobarVictoria();
		}
	}

	/** Un participante pide saltar el item actual (compartido: para todos; independiente: para su equipo). */
	Component saltar(ServerPlayer p) {
		if (rastreo == null) return Component.translatable("venatio.rastreo.error.no_rastreo");
		if (fase != Fase.JUGANDO || !participa(p)) return Component.translatable("venatio.rastreo.error.no_jugando");
		ColorEquipo equipo = equipoDe(p);
		int espera = rastreo.esperaParaSaltar(equipo, segundos());
		if (espera > 0) return Component.translatable("venatio.rastreo.error.espera", espera);
		Objetivo saltado = rastreo.actual(equipo);
		rastreo.registrarSalto(equipo, segundos());
		rastreo.avanzar(equipo);
		juego.difundir(Component.translatable(rastreo.independiente() ? "venatio.rastreo.salto_equipo" : "venatio.rastreo.salto",
			p.getDisplayName(), saltado.icono().getHoverName(), equipo.nombre()));
		juego.enviarEstadoATodos();
		return null;
	}

	private void marcar(int indice, ColorEquipo equipo, ServerPlayer p, ItemStack item) {
		Carton.Casilla cas = carton.casillas.get(indice);
		cas.logros.put(equipo, segundos());
		cas.jugadores.put(equipo, p.getUUID());
		juego.difundir(Component.translatable("venatio.casilla.conseguida",
			p.getDisplayName(), item.getHoverName(), equipo.nombre()));
		juego.enviarEvento(new Paquetes.Evento(Paquetes.Evento.CASILLA, equipo.ordinal(), indice));
		juego.enviarEstadoATodos();
		comprobarVictoria();
	}

	// ---------------------------------------------------------------- victoria

	private void comprobarVictoria() {
		int total = carton.casillas.size();
		for (ColorEquipo c : equipos) {
			int puntos = carton.puntos(c);
			boolean gana = switch (cfg.modo) {
				case LINEA -> carton.tieneLinea(c);
				case APAGON, CONTRARRELOJ -> puntos == total;
				case LOCKOUT -> carton.tieneLinea(c) || puntos > maxOtros(c) + carton.sinReclamar();
				case RASTREO -> false;
				case RASTREO_CARRERA -> puntos >= cfg.meta;
			};
			if (!gana) continue;
			if (cfg.finAutomatico || puntos == total || rastreo != null) {
				declarar(primerGanador != null ? primerGanador : c, false);
				return;
			}
			if (anunciados.add(c)) {
				if (primerGanador == null) primerGanador = c;
				juego.difundir(Component.translatable("venatio.bingo.sigue", c.nombre()));
				for (ServerPlayer p : jugadoresEnLinea()) {
					Jugadores.titulo(p, Component.translatable("venatio.bingo.titulo"), Component.translatable("venatio.bingo.sigue", c.nombre()), 5, 50, 15);
				}
				juego.enviarEvento(new Paquetes.Evento(Paquetes.Evento.VICTORIA, c.ordinal(), -1));
			}
		}
	}

	private int maxOtros(ColorEquipo c) {
		int max = 0;
		for (ColorEquipo o : equipos) if (o != c) max = Math.max(max, carton.puntos(o));
		return max;
	}

	/** Se acabó el tiempo: gana quien tenga más casillas; empata quien llegó antes a ese número. */
	private void terminarPorTiempo() {
		if (primerGanador != null) {
			declarar(primerGanador, false);
			return;
		}
		ColorEquipo mejor = null;
		boolean empatado = false;
		for (ColorEquipo c : equipos) {
			if (mejor == null) {
				mejor = c;
				continue;
			}
			int dif = Integer.compare(carton.puntos(c), carton.puntos(mejor));
			if (dif == 0) dif = Integer.compare(carton.ultimoLogro(mejor), carton.ultimoLogro(c));
			if (dif > 0) {
				mejor = c;
				empatado = false;
			} else if (dif == 0) {
				empatado = true;
			}
		}
		if (mejor == null || carton.puntos(mejor) == 0) declarar(null, true);
		else declarar(empatado ? null : mejor, empatado);
	}

	/** El anfitrión termina la partida: gana quien ya hizo bingo (fin automático apagado) o nadie. */
	void terminarPorAnfitrion() {
		declarar(primerGanador, false);
	}

	/** Termina el juego. ganador null y empate false = terminada por el anfitrión. */
	void declarar(ColorEquipo ganador, boolean empate) {
		if (fase == Fase.FINAL) return;
		this.fase = Fase.FINAL;
		this.ticks = 0;
		this.ganador = ganador;
		this.empate = empate;

		Component resumen = ganador != null ? Component.translatable("venatio.final.gana", ganador.nombre())
			: empate ? Component.translatable("venatio.final.empate")
			: Component.translatable("venatio.final.cancelada");
		juego.difundir(resumen);
		for (ColorEquipo c : equipos) {
			juego.difundir(Component.translatable("venatio.final.puntos", c.nombre(), carton.puntos(c), carton.casillas.size()));
		}
		for (ServerPlayer p : jugadoresEnLinea()) {
			boolean gano = ganador != null && ganador == equipoDe(p);
			Component titulo = Component.translatable(gano ? "venatio.final.titulo.ganaste" : "venatio.final.titulo.fin");
			Jugadores.titulo(p, titulo, resumen, 5, 80, 20);
		}
		juego.enviarEvento(new Paquetes.Evento(Paquetes.Evento.VICTORIA, ganador == null ? -1 : ganador.ordinal(), -1));
		juego.enviarEstadoATodos();
		ModuloStats.partidaTerminada(this);
	}

	// ---------------------------------------------------------------- jugadores

	List<ServerPlayer> jugadoresEnLinea() {
		List<ServerPlayer> lista = new ArrayList<>();
		for (ServerPlayer p : juego.server().getPlayerList().getPlayers()) if (participa(p)) lista.add(p);
		return lista;
	}

	void llevarAlSpawn(ServerPlayer p) {
		BlockPos pos = spawns.get(equipoDe(p));
		if (pos == null) return;
		p.teleportTo(mundos.mundo(), pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, Set.of(), p.getYRot(), 0f, true);
		// Reaparecer directamente en el spawn del equipo, dentro del mundo de la partida. Además de ahorrar un viaje,
		// es necesario para keepInventory: vanilla decide si copia el inventario según las reglas del mundo donde reapareces.
		p.setRespawnPosition(new ServerPlayer.RespawnConfig(
			LevelData.RespawnData.of(mundos.mundo().dimension(), pos, p.getYRot(), 0f), true), false);
	}

	/** Un participante volvió a entrar al server. */
	void alReconectar(ServerPlayer p) {
		if (fase == Fase.PREPARANDO) {
			return;
		}
		if (!MundosPartida.esDePartida(p.level())) {
			Jugadores.reiniciar(p, GameType.SURVIVAL);
			llevarAlSpawn(p);
			aplicarKit(p);
		}
		Jugadores.congelar(p, fase == Fase.CUENTA_ATRAS);
	}

	/** Murió sin cama en el mundo de la partida: vuelve al punto de aparición de su equipo. */
	void alReaparecer(ServerPlayer p) {
		if (fase == Fase.PREPARANDO) return;
		if (!MundosPartida.esDePartida(p.level())) llevarAlSpawn(p);
		aplicarEfectos(p);
	}

	// ---------------------------------------------------------------- opciones de partida

	/** Efectos, equipo y recetas según las opciones. Se aplica al empezar y al volver de fuera de la partida. */
	private void aplicarKit(ServerPlayer p) {
		aplicarEfectos(p);
		if (cfg.elitro) {
			p.getInventory().add(new ItemStack(Items.ELYTRA));
			p.getInventory().add(new ItemStack(Items.FIREWORK_ROCKET, 64));
		}
		if (cfg.depthStrider) {
			ItemStack botas = new ItemStack(Items.LEATHER_BOOTS);
			botas.enchant(p.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.DEPTH_STRIDER), 3);
			p.setItemSlot(EquipmentSlot.FEET, botas);
		}
		if (cfg.recetas) p.awardRecipes(juego.server().getRecipeManager().getRecipes());
	}

	/** Los efectos se pierden al morir: se reaplican al reaparecer. */
	private void aplicarEfectos(ServerPlayer p) {
		if (cfg.vision) p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false, true));
		if (cfg.velocidad) p.addEffect(new MobEffectInstance(MobEffects.SPEED, MobEffectInstance.INFINITE_DURATION, 1, false, false, true));
	}

	/** Salida del End para un jugador: su cama si está en el mundo de la partida, si no el punto de su equipo. */
	public BlockPos spawnDe(ServerPlayer p) {
		return spawns.get(equipoDe(p));
	}

	// ---------------------------------------------------------------- red

	Paquetes.Estado estado(ColorEquipo miEquipo) {
		int faseRed = switch (fase) {
			case PREPARANDO -> Paquetes.Estado.PREPARANDO;
			case CUENTA_ATRAS -> Paquetes.Estado.CUENTA_ATRAS;
			case JUGANDO -> Paquetes.Estado.JUGANDO;
			case FINAL -> Paquetes.Estado.FINAL;
		};
		int segundosRed = fase == Fase.CUENTA_ATRAS ? Math.max(0, SEGUNDOS_CUENTA - ticks / 20) : segundos();
		List<Paquetes.CasillaRed> casillas = new ArrayList<>();
		if (rastreo != null) {
			// En Rastreo el cliente ve una sola casilla: el item que su equipo busca ahora.
			ColorEquipo quien = miEquipo != null ? miEquipo : equipos.isEmpty() ? null : equipos.get(0);
			casillas.add(new Paquetes.CasillaRed(rastreo.actual(quien).icono(), List.of()));
		} else for (Carton.Casilla cas : carton.casillas) {
			List<Paquetes.Logro> logros = new ArrayList<>();
			cas.logros.forEach((c, s) -> logros.add(new Paquetes.Logro(c.ordinal(), s)));
			casillas.add(new Paquetes.CasillaRed(cas.icono, logros));
		}
		List<Paquetes.EquipoRed> equiposRed = new ArrayList<>();
		for (ColorEquipo c : equipos) {
			List<String> miembros = new ArrayList<>();
			participantes.forEach((uuid, eq) -> {
				if (eq == c) miembros.add(juego.nombre(uuid));
			});
			equiposRed.add(new Paquetes.EquipoRed(c.ordinal(), carton.puntos(c), miembros));
		}
		int ganadorRed = fase != Fase.FINAL ? -1 : ganador != null ? ganador.ordinal() : empate ? -2 : -3;
		return new Paquetes.Estado(faseRed, cfg.modo.id(), segundosRed, cfg.limiteSegundos(), cfg.pvp, progreso, cfg.meta,
			rastreo != null ? 1 : carton.tamano, casillas, equiposRed, miEquipo == null ? -1 : miEquipo.ordinal(), ganadorRed);
	}
}
