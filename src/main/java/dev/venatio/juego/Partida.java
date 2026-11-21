package dev.venatio.juego;

import dev.venatio.Venatio;
import dev.venatio.carton.Carton;
import dev.venatio.carton.Objetivo;
import dev.venatio.mundo.MundosPartida;
import dev.venatio.red.Paquetes;
import dev.venatio.ruta.GrabadoraRuta;
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
	/** Casillas de logro: qué llevaba cada jugador en sus estadísticas cuando se empezó a mirar cada logro. */
	private final SeguimientoRetos retos = new SeguimientoRetos(this);
	/** Rastreo con cantidades: unidades que junta cada equipo del item que busca ahora. */
	private final Map<ColorEquipo, Integer> progresoRastreo = new EnumMap<>(ColorEquipo.class);
	/** Eventos sorpresa y lo que va a la línea de tiempo sin ser casilla. */
	final Eventos eventos;
	/** Cartón oculto, draft, casilla secreta y relevo. */
	final Variantes variantes;

	Partida(Juego juego, Configuracion cfg, long semilla, Carton carton, Rastreo rastreo, MundosPartida mundos, Map<UUID, ColorEquipo> participantes) {
		this.juego = juego;
		this.rastreo = rastreo;
		this.cfg = cfg;
		this.semilla = semilla;
		this.carton = carton;
		this.mundos = mundos;
		this.participantes = participantes;
		this.equipos = participantes.values().stream().distinct().sorted().toList();
		this.variantes = new Variantes(this);
		planificarSpawns();
		this.eventos = new Eventos(this);
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

	public Variantes variantes() {
		return variantes;
	}

	/** Casillas que cuentan para el equipo: las suyas, con la casilla secreta valiendo doble. */
	public int valor(ColorEquipo c) {
		return carton.puntos(c) + variantes.extra(c);
	}

	public MundosPartida mundos() {
		return mundos;
	}

	public Eventos eventos() {
		return eventos;
	}

	/** Equipos que juegan la partida. */
	public List<ColorEquipo> equipos() {
		return equipos;
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
				Fuegos.tick(this, ticks);
				if (ticks >= SEGUNDOS_FINAL * 20) juego.cerrarPartida();
			}
		}
	}

	private void tickPreparando() {
		variantes.tickPreparando();
		ServerLevel mundo = mundos.mundo();
		long listos = carga.stream().filter(c -> mundo.getChunkSource().getChunkNow(c.x(), c.z()) != null).count();
		progreso = carga.isEmpty() ? 100 : (int) (listos * 100 / carga.size());
		if ((listos == carga.size() || ticks >= MAX_SEGUNDOS_PREPARACION * 20) && !variantes.draftEnCurso()) {
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
		variantes.alEmpezarCuenta();
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

	/** Los números y el "¡A cazar!" los dibuja el cliente (HudEfectos) a partir del estado y del evento INICIO. */
	private void tickCuentaAtras() {
		if (ticks >= SEGUNDOS_CUENTA * 20) {
			for (ServerPlayer p : jugadoresEnLinea()) Jugadores.congelar(p, false);
			fase = Fase.JUGANDO;
			ticks = 0;
			juego.enviarEvento(new Paquetes.Evento(Paquetes.Evento.INICIO, -1, -1));
			juego.enviarEstadoATodos();
		}
	}

	private void tickJugando() {
		ticksJuego++;
		if (ticksJuego % 10 == 0) detectar();
		if (ticksJuego % 20 == 10) variantes.tickSegundo();
		if (ticksJuego % 20 == 0) reponerRegalos();
		if (ticksJuego % 20 == 0) {
			Switcheroo.tick(this);
			eventos.alSegundo();
		}
		int limite = cfg.limiteSegundos();
		if (fase == Fase.JUGANDO && limite > 0 && segundos() >= limite) terminarPorTiempo();
	}

	// ---------------------------------------------------------------- detección

	/** Quién consiguió una casilla para su equipo y con qué nombre se anuncia ("Diamante", "16 × Tronco de roble", "Mata un creeper"). */
	private record Logrado(ServerPlayer jugador, Component nombre) {}

	private void detectar() {
		if (rastreo != null) {
			detectarRastreo();
			return;
		}
		Map<ColorEquipo, List<ServerPlayer>> activos = activosPorEquipo();
		for (int i = 0; i < carton.casillas.size() && fase == Fase.JUGANDO; i++) {
			Carton.Casilla cas = carton.casillas.get(i);
			for (ColorEquipo equipo : equipos) {
				if (fase != Fase.JUGANDO || cfg.modo.exclusivo() && !cas.logros.isEmpty()) break;
				if (cas.logros.containsKey(equipo) || variantes.tapada(i, equipo)) continue;
				Logrado l = comprobar(cas.objetivo, equipo, variantes.quienesCuentan(i, equipo, activos.getOrDefault(equipo, List.of())), cas.progreso);
				if (l != null) marcar(i, equipo, l.jugador(), l.nombre());
			}
		}
	}

	/** Participantes que pueden conseguir casillas ahora: conectados, vivos o no, fuera del modo espectador y en la partida. */
	private Map<ColorEquipo, List<ServerPlayer>> activosPorEquipo() {
		Map<ColorEquipo, List<ServerPlayer>> mapa = new EnumMap<>(ColorEquipo.class);
		for (ServerPlayer p : jugadoresEnLinea()) {
			ColorEquipo equipo = equipoDe(p);
			if (equipo == null || p.isSpectator() || !MundosPartida.esDePartida(p.level())) continue;
			mapa.computeIfAbsent(equipo, k -> new ArrayList<>()).add(p);
		}
		return mapa;
	}

	/**
	 * Si el equipo cumple ahora el objetivo: un logro lo cumple cualquiera de sus miembros; una casilla con cantidad, la suma
	 * de lo que tienen todos en el inventario (y anota cuánto llevan en progreso); un item, cualquiera que lo tenga.
	 */
	private Logrado comprobar(Objetivo objetivo, ColorEquipo equipo, List<ServerPlayer> miembros, Map<ColorEquipo, Integer> progreso) {
		if (objetivo.esReto()) {
			for (ServerPlayer p : miembros) if (retos.cumple(p, objetivo.reto())) return new Logrado(p, objetivo.icono().getHoverName());
			return null;
		}
		if (objetivo.cantidad() > 1) {
			int total = 0, mayor = 0;
			ServerPlayer quien = null;
			for (ServerPlayer p : miembros) {
				int n = contar(p, objetivo);
				total += n;
				if (n > mayor) {
					mayor = n;
					quien = p;
				}
			}
			progreso.put(equipo, Math.min(total, objetivo.cantidad()));
			return total >= objetivo.cantidad() ? new Logrado(quien, objetivo.icono().getHoverName()) : null;
		}
		for (ServerPlayer p : miembros) {
			ItemStack encontrado = buscar(p, objetivo);
			if (encontrado != null) return new Logrado(p, encontrado.getHoverName());
		}
		return null;
	}

	private static ItemStack buscar(ServerPlayer p, Objetivo objetivo) {
		Inventory inv = p.getInventory();
		for (int s = 0; s < inv.getContainerSize(); s++) {
			ItemStack st = inv.getItem(s);
			if (!Regalos.esRegalo(st) && objetivo.cumple(st)) return st;
		}
		// Lo que da la partida (élitro, cohetes, botas) no cuenta: el cohete del cartón hay que craftearlo.
		ItemStack enCursor = p.containerMenu.getCarried();
		return !Regalos.esRegalo(enCursor) && objetivo.cumple(enCursor) ? enCursor : null;
	}

	/** Unidades del objetivo que tiene el jugador (inventario y cursor), sin contar los regalos de la partida. */
	private static int contar(ServerPlayer p, Objetivo objetivo) {
		Inventory inv = p.getInventory();
		int n = 0;
		for (int s = 0; s < inv.getContainerSize(); s++) {
			ItemStack st = inv.getItem(s);
			if (!Regalos.esRegalo(st) && objetivo.cumple(st)) n += st.getCount();
		}
		ItemStack enCursor = p.containerMenu.getCarried();
		if (!Regalos.esRegalo(enCursor) && objetivo.cumple(enCursor)) n += enCursor.getCount();
		return n;
	}

	private void detectarRastreo() {
		Map<ColorEquipo, List<ServerPlayer>> activos = activosPorEquipo();
		for (ColorEquipo equipo : equipos) {
			if (fase != Fase.JUGANDO) return;
			List<ServerPlayer> miembros = activos.getOrDefault(equipo, List.of());
			Objetivo objetivo = rastreo.actual(equipo);
			Logrado l = comprobar(objetivo, equipo, miembros, progresoRastreo);
			if (l == null) continue;
			carton.anotar(objetivo, equipo, segundos(), l.jugador().getUUID());
			GrabadoraRuta.casilla(l.jugador(), objetivo.icono());
			rastreo.avanzar(equipo, segundos());
			if (objetivo.esReto()) retos.olvidar(objetivo.reto(), miembros);
			if (rastreo.independiente()) progresoRastreo.remove(equipo);
			else progresoRastreo.clear();
			if (cfg.modo == Modo.SWITCHEROO) Switcheroo.punto(this, l.jugador(), equipo, objetivo.icono());
			else juego.difundir(Component.translatable(objetivo.esReto() ? "venatio.casilla.logro" : "venatio.rastreo.encontrado",
				l.jugador().getDisplayName(), l.nombre(), equipo.nombre()));
			juego.enviarEvento(new Paquetes.Evento(Paquetes.Evento.CASILLA, equipo.ordinal(), 0));
			juego.enviarEstadoATodos();
			comprobarVictoria();
		}
	}

	/** Un participante pide saltar el item actual (compartido: para todos; independiente: para su equipo). */
	Component saltar(ServerPlayer p) {
		if (rastreo == null) return Component.translatable("venatio.rastreo.error.no_rastreo");
		if (fase != Fase.JUGANDO || !participa(p)) return Component.translatable("venatio.rastreo.error.no_jugando");
		if (cfg.modo == Modo.SWITCHEROO) return Component.translatable("venatio.switcheroo.error.saltar");
		ColorEquipo equipo = equipoDe(p);
		int espera = rastreo.esperaParaSaltar(equipo, segundos());
		if (espera > 0) return Component.translatable("venatio.rastreo.error.espera", espera);
		Objetivo saltado = rastreo.actual(equipo);
		rastreo.registrarSalto(equipo, segundos());
		rastreo.avanzar(equipo, segundos());
		if (rastreo.independiente()) progresoRastreo.remove(equipo);
		else progresoRastreo.clear();
		juego.difundir(Component.translatable(rastreo.independiente() ? "venatio.rastreo.salto_equipo" : "venatio.rastreo.salto",
			p.getDisplayName(), saltado.icono().getHoverName(), equipo.nombre()));
		juego.enviarEstadoATodos();
		return null;
	}

	private void marcar(int indice, ColorEquipo equipo, ServerPlayer p, Component conseguido) {
		Carton.Casilla cas = carton.casillas.get(indice);
		cas.logros.put(equipo, segundos());
		cas.jugadores.put(equipo, p.getUUID());
		GrabadoraRuta.casilla(p, cas.objetivo.icono());
		variantes.alMarcar(indice, equipo);
		// Con el cartón oculto, a quien la tiene tapada no se le dice qué pedía.
		variantes.anunciar(indice, p, equipo, Component.translatable(cas.objetivo.esReto() ? "venatio.casilla.logro" : "venatio.casilla.conseguida",
			p.getDisplayName(), conseguido, equipo.nombre()));
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
				case LOCKOUT -> carton.tieneLinea(c) || valor(c) > maxOtros(c);
				case RASTREO -> false;
				case RASTREO_CARRERA, SWITCHEROO -> puntos >= cfg.meta;
			};
			if (!gana) continue;
			if (cfg.finAutomatico || puntos == total || rastreo != null) {
				declarar(primerGanador != null ? primerGanador : c, false);
				return;
			}
			if (anunciados.add(c)) {
				if (primerGanador == null) primerGanador = c;
				juego.difundir(Component.translatable("venatio.bingo.sigue", c.nombre()));
				juego.enviarEvento(new Paquetes.Evento(Paquetes.Evento.BINGO, c.ordinal(), -1));
			}
		}
	}

	/** Lo más que puede llegar a valer otro equipo: lo suyo más todo lo que queda sin dueño (su casilla secreta, doble). */
	private int maxOtros(ColorEquipo c) {
		int max = carton.sinReclamar();
		for (ColorEquipo o : equipos) if (o != c) max = Math.max(max, valor(o) + carton.sinReclamar() + variantes.extraPosible(o));
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
			int dif = Integer.compare(valor(c), valor(mejor));
			if (dif == 0) dif = Integer.compare(carton.ultimoLogro(mejor), carton.ultimoLogro(c));
			if (dif > 0) {
				mejor = c;
				empatado = false;
			} else if (dif == 0) {
				empatado = true;
			}
		}
		if (mejor == null || valor(mejor) == 0) declarar(null, true);
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
		variantes.alTerminar();

		Component resumen = ganador != null ? Component.translatable("venatio.final.gana", ganador.nombre())
			: empate ? Component.translatable("venatio.final.empate")
			: Component.translatable("venatio.final.cancelada");
		eventos.terminar();
		juego.difundir(resumen);
		for (ColorEquipo c : equipos) {
			juego.difundir(cfg.modo == Modo.SWITCHEROO
				? Component.translatable("venatio.switcheroo.final.puntos", c.nombre(), carton.puntos(c))
				: Component.translatable("venatio.final.puntos", c.nombre(), carton.puntos(c), carton.casillas.size()));
		}
		variantes.lineasFinal().forEach(juego::difundir);
		// El banner del resultado lo dibuja el cliente con este evento; los fuegos artificiales salen en el tick del final.
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
		Regalos.reponer(p, cfg);
	}

	/** Élitro, cohetes y botas que se acabaron o se perdieron: se reponen cada segundo, como en MCBingo. */
	private void reponerRegalos() {
		if (!cfg.elitro && !cfg.depthStrider) return;
		for (ServerPlayer p : jugadoresEnLinea()) {
			if (p.isAlive() && !p.isSpectator() && MundosPartida.esDePartida(p.level())) Regalos.reponer(p, cfg);
		}
	}

	// ---------------------------------------------------------------- opciones de partida

	/** Efectos, equipo y recetas según las opciones. Se aplica al empezar y al volver de fuera de la partida. */
	private void aplicarKit(ServerPlayer p) {
		aplicarEfectos(p);
		Regalos.reponer(p, cfg);
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

	/** Punto de aparición de un equipo (en suelo firme desde la cuenta atrás). */
	public BlockPos spawnDe(ColorEquipo equipo) {
		return spawns.get(equipo);
	}

	// ---------------------------------------------------------------- red

	private static Paquetes.CasillaRed casillaRed(Objetivo o, ItemStack icono, List<Paquetes.Logro> logros, Map<ColorEquipo, Integer> progreso) {
		List<Paquetes.Progreso> lista = new ArrayList<>();
		if (o.cantidad() > 1) progreso.forEach((c, n) -> lista.add(new Paquetes.Progreso(c.ordinal(), n)));
		return new Paquetes.CasillaRed(icono, logros, o.esReto() ? o.reto().id() : "", o.cantidad(), lista);
	}

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
			Objetivo actual = rastreo.actual(quien);
			casillas.add(casillaRed(actual, actual.icono(), List.of(), progresoRastreo));
		} else for (int i = 0; i < carton.casillas.size(); i++) {
			Carton.Casilla cas = carton.casillas.get(i);
			List<Paquetes.Logro> logros = new ArrayList<>();
			cas.logros.forEach((c, s) -> logros.add(new Paquetes.Logro(c.ordinal(), s)));
			// Cartón oculto: lo tapado no viaja, para que no se pueda leer por fuera.
			casillas.add(variantes.ocultaPara(miEquipo, i) ? Variantes.casillaTapada(logros) : casillaRed(cas.objetivo, cas.icono, logros, cas.progreso));
		}
		List<Paquetes.EquipoRed> equiposRed = new ArrayList<>();
		for (ColorEquipo c : equipos) {
			List<String> miembros = new ArrayList<>();
			participantes.forEach((uuid, eq) -> {
				if (eq == c) miembros.add(juego.nombre(uuid));
			});
			// La casilla secreta (doble) solo se nota en el marcador de su propio equipo hasta el final.
			equiposRed.add(new Paquetes.EquipoRed(c.ordinal(), fase == Fase.FINAL || c == miEquipo ? valor(c) : carton.puntos(c), miembros));
		}
		int ganadorRed = fase != Fase.FINAL ? -1 : ganador != null ? ganador.ordinal() : empate ? -2 : -3;
		return new Paquetes.Estado(faseRed, cfg.modo.id(), segundosRed, cfg.limiteSegundos(), cfg.pvp, progreso, cfg.meta,
			rastreo != null ? 1 : carton.tamano, casillas, equiposRed, miEquipo == null ? -1 : miEquipo.ordinal(), ganadorRed,
			variantes.red(miEquipo));
	}
}
