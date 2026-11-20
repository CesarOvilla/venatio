package dev.venatio.juego;

import dev.venatio.Venatio;
import dev.venatio.carton.Carton;
import dev.venatio.mundo.MundosPartida;
import dev.venatio.red.Paquetes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
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

	Partida(Juego juego, Configuracion cfg, long semilla, Carton carton, MundosPartida mundos, Map<UUID, ColorEquipo> participantes) {
		this.juego = juego;
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
		spawns.replaceAll((equipo, pos) ->
			new BlockPos(pos.getX(), mundo.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()), pos.getZ()));
		for (ServerPlayer p : jugadoresEnLinea()) {
			Jugadores.reiniciar(p, GameType.SURVIVAL);
			llevarAlSpawn(p);
			Jugadores.congelar(p, true);
		}
		// Los jugadores ya mantienen cargada su zona; los tickets de pregeneración sobran.
		for (ChunkPos centro : centrosConTicket) mundo.getChunkSource().removeTicketWithRadius(TicketType.FORCED, centro, RADIO_PREGEN);
		centrosConTicket.clear();
		fase = Fase.CUENTA_ATRAS;
		ticks = 0;
		juego.enviarEstadoATodos();
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
		Inventory inv = p.getInventory();
		for (int s = 0; s < inv.getContainerSize(); s++) {
			ItemStack st = inv.getItem(s);
			if (cas.objetivo.cumple(st)) return st;
		}
		ItemStack enCursor = p.containerMenu.getCarried();
		return cas.objetivo.cumple(enCursor) ? enCursor : null;
	}

	private void marcar(int indice, ColorEquipo equipo, ServerPlayer p, ItemStack item) {
		carton.casillas.get(indice).logros.put(equipo, segundos());
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
			};
			if (gana) {
				declarar(c, false);
				return;
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
	}

	/** Un participante volvió a entrar al server. */
	void alReconectar(ServerPlayer p) {
		if (fase == Fase.PREPARANDO) {
			return;
		}
		if (!MundosPartida.esDePartida(p.level())) {
			Jugadores.reiniciar(p, GameType.SURVIVAL);
			llevarAlSpawn(p);
		}
		Jugadores.congelar(p, fase == Fase.CUENTA_ATRAS);
	}

	/** Murió sin cama en el mundo de la partida: vuelve al punto de aparición de su equipo. */
	void alReaparecer(ServerPlayer p) {
		if (fase != Fase.PREPARANDO && !MundosPartida.esDePartida(p.level())) llevarAlSpawn(p);
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
		for (Carton.Casilla cas : carton.casillas) {
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
		return new Paquetes.Estado(faseRed, cfg.modo.id(), segundosRed, cfg.limiteSegundos(), cfg.pvp, progreso,
			carton.tamano, casillas, equiposRed, miEquipo == null ? -1 : miEquipo.ordinal(), ganadorRed);
	}
}
