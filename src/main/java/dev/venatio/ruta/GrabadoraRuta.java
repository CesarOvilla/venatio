package dev.venatio.ruta;

import dev.venatio.Venatio;
import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Espectadores;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Partida;
import dev.venatio.mundo.MundosPartida;
import dev.venatio.red.PaquetesRuta;
import dev.venatio.stats.ModuloStats;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Graba la ruta de la partida mientras se juega (sustituye a BingoCam): posición de cada participante cada 2 s,
 * eventos con su lugar (casillas, muertes, portales, ayudas) y una muestra del terreno que tuvieron cerca. El terreno
 * se toma durante la partida porque los mundos se borran al terminar; se lee de pocos chunks por tick, y solo de los
 * que ya están cargados, para no dar tirones. Al terminar se manda comprimida y en trozos a participantes y
 * espectadores, y se guarda para /venatio ruta hasta que empiece otra partida.
 */
public final class GrabadoraRuta {
	private static final int CADA_PUNTO = 40;
	/** Última posición conocida de cada uno: de ahí sale el lado de salida de un portal. */
	private static final int CADA_POSICION = 10;
	private static final int CADA_BUSQUEDA = 40;
	/** Radio en chunks del terreno que se muestrea alrededor de cada jugador (en el Nether, menos: es cueva). */
	private static final int RADIO = 6, RADIO_TECHO = 4;
	private static final int CHUNKS_POR_TICK = 6;
	/** Tope de chunks muestreados por dimensión (unos 16 bytes cada uno). */
	private static final int MAX_CHUNKS = 100_000;
	/**
	 * Resolución del terreno que se manda: 4×4 colores por chunk (1 cada 4 bloques) hasta este total de chunks; con más,
	 * 2×2; con mucho más, 1. Así una partida con alguien que se fue muy lejos no hace crecer la ruta sin límite.
	 */
	private static final int CHUNKS_RESOLUCION_MEDIA = 40_000, CHUNKS_RESOLUCION_BAJA = 120_000;
	/** Con el jugador quieto no se repite el punto, salvo cada tanto para que la línea de tiempo siga. */
	private static final int SEGUNDOS_QUIETO = 60;
	/** Una foto en vivo se reutiliza este tiempo si otro espectador la pide. */
	private static final int TICKS_FOTO = 40;
	private static final byte[] VACIO = new byte[0];

	private static MinecraftServer server;
	private static Partida partida;
	private static Grabacion g;
	private static int ticks;
	private static Envio ultima;
	private static DatosRuta ultimaDatos;
	private static Envio foto;
	private static int fotoTick;
	private static boolean fotoEnCamino;
	private static final Set<UUID> pendientesFoto = new LinkedHashSet<>();
	private static int siguienteId = 1;
	/** Sube con cada partida nueva: lo que se comprimía para la anterior ya no se usa. */
	private static int generacion;
	/** La ruta final se está comprimiendo (fuera del hilo del servidor). */
	private static boolean preparando;

	private GrabadoraRuta() {}

	/** Lo que se manda: la ruta comprimida en trozos y los iconos de las casillas. */
	private record Envio(int id, List<ItemStack> iconos, List<byte[]> trozos) {}

	private record EventoG(int tipo, UUID jugador, int segundo, int dim, int x, int z, int icono, int extra) {}

	private static final class Recorrido {
		final UUID uuid;
		final String nombre;
		final int equipo;
		final List<DatosRuta.Punto> puntos = new ArrayList<>();
		boolean corte = true;
		int dim = -1, x, z;
		/** Última posición vista (cada medio segundo). */
		int posDim = -1, posX, posZ;

		Recorrido(UUID uuid, String nombre, int equipo) {
			this.uuid = uuid;
			this.nombre = nombre;
			this.equipo = equipo;
		}
	}

	/** Terreno de una dimensión: lo ya muestreado y lo que falta. */
	private static final class Capa {
		final Long2ObjectOpenHashMap<byte[]> chunks = new Long2ObjectOpenHashMap<>();
		final LongArrayFIFOQueue cola = new LongArrayFIFOQueue();
		final LongOpenHashSet enCola = new LongOpenHashSet();
		final Long2IntOpenHashMap refY = new Long2IntOpenHashMap();
	}

	private static final class Grabacion {
		final Map<UUID, Recorrido> recorridos = new LinkedHashMap<>();
		final List<EventoG> eventos = new ArrayList<>();
		final List<ItemStack> iconos = new ArrayList<>();
		final List<DatosRuta.Spawn> spawns = new ArrayList<>();
		final Capa[] capas = {new Capa(), new Capa(), new Capa()};
		final MuestraTerreno muestra = new MuestraTerreno();
		final byte[] buffer = new byte[DatosRuta.MUESTRAS_POR_CHUNK];
		boolean spawnsListos, empezo;
		int capaSiguiente;
	}

	public static void registrar() {
		ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			server = null;
			partida = null;
			g = null;
			ultima = null;
			ultimaDatos = null;
			foto = null;
			preparando = false;
			fotoEnCamino = false;
			pendientesFoto.clear();
		});
		ServerTickEvents.END_SERVER_TICK.register(s -> tick());
		ServerLivingEntityEvents.AFTER_DEATH.register(GrabadoraRuta::alMorir);
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register(GrabadoraRuta::alCambiarNivel);
	}

	// ---------------------------------------------------------------- ciclo

	private static void tick() {
		if (server == null) return;
		ticks++;
		Partida p = Juego.INSTANCIA.partida();
		if (p != partida) {
			partida = p;
			g = p == null ? null : new Grabacion();
			if (p != null) generacion++;
			if (p != null && ultima != null) {
				// Empezó otra: la ruta de la anterior ya no se puede ver (y el tablero deja de ofrecerla).
				ultima = null;
				ultimaDatos = null;
				ModuloStats.actualizarTableros();
			}
			foto = null;
			fotoEnCamino = false;
			pendientesFoto.clear();
		}
		if (p == null || g == null) return;
		Partida.Fase fase = p.fase();
		if (fase == Partida.Fase.PREPARANDO || fase == Partida.Fase.FINAL) return;
		if (!g.spawnsListos) anotarSpawns(p);
		if (ticks % CADA_BUSQUEDA == 0) buscarChunks(p);
		muestrear(p, CHUNKS_POR_TICK);
		if (fase != Partida.Fase.JUGANDO) return;
		if (ticks % CADA_POSICION == 0) actualizarPosiciones(p);
		if (!g.empezo || ticks % CADA_PUNTO == 0) {
			anotarPuntos(p, false);
			g.empezo = true;
		}
	}

	/** Al empezar la cuenta atrás los puntos de aparición ya están en suelo firme. */
	private static void anotarSpawns(Partida p) {
		for (ColorEquipo c : p.equipos()) {
			BlockPos pos = p.spawnDe(c);
			if (pos != null) g.spawns.add(new DatosRuta.Spawn(c.ordinal(), DatosRuta.MUNDO, pos.getX(), pos.getZ()));
		}
		g.spawnsListos = true;
	}

	private static int dim(Partida p, Level nivel) {
		MundosPartida m = p.mundos();
		return nivel == m.mundo() ? DatosRuta.MUNDO : nivel == m.nether() ? DatosRuta.NETHER : nivel == m.end() ? DatosRuta.END : -1;
	}

	private static ServerLevel nivel(Partida p, int dim) {
		MundosPartida m = p.mundos();
		return switch (dim) {
			case DatosRuta.MUNDO -> m.mundo();
			case DatosRuta.NETHER -> m.nether();
			default -> m.end();
		};
	}

	/** Participantes en línea, vivos y dentro de los mundos de la partida. */
	private static List<ServerPlayer> enJuego(Partida p) {
		List<ServerPlayer> lista = new ArrayList<>();
		for (ServerPlayer j : server.getPlayerList().getPlayers()) {
			if (p.participa(j) && j.isAlive() && !j.isSpectator() && dim(p, j.level()) >= 0) lista.add(j);
		}
		return lista;
	}

	private static Recorrido recorrido(Partida p, ServerPlayer j) {
		return g.recorridos.computeIfAbsent(j.getUUID(), u -> {
			ColorEquipo c = p.equipoDe(j);
			return new Recorrido(u, j.getGameProfile().name(), c == null ? -1 : c.ordinal());
		});
	}

	// ---------------------------------------------------------------- recorridos

	private static void anotarPuntos(Partida p, boolean forzar) {
		int s = p.segundos();
		for (ServerPlayer j : enJuego(p)) punto(recorrido(p, j), s, dim(p, j.level()), j.getBlockX(), j.getBlockZ(), forzar);
	}

	private static void punto(Recorrido r, int segundo, int dim, int x, int z, boolean forzar) {
		if (r.dim != dim) r.corte = true;
		if (!forzar && !r.corte && !r.puntos.isEmpty()) {
			DatosRuta.Punto ultimo = r.puntos.getLast();
			if (Math.abs(x - ultimo.x()) + Math.abs(z - ultimo.z()) <= 1 && segundo - ultimo.segundo() < SEGUNDOS_QUIETO) return;
		}
		r.puntos.add(new DatosRuta.Punto(segundo, dim, x, z, r.corte));
		r.corte = false;
		r.dim = dim;
		r.x = x;
		r.z = z;
	}

	private static void actualizarPosiciones(Partida p) {
		for (ServerPlayer j : enJuego(p)) {
			Recorrido r = recorrido(p, j);
			r.posDim = dim(p, j.level());
			r.posX = j.getBlockX();
			r.posZ = j.getBlockZ();
		}
	}

	private static boolean grabando(ServerPlayer j) {
		Partida p = Juego.INSTANCIA.partida();
		return g != null && p != null && p == partida && p.fase() == Partida.Fase.JUGANDO && p.participa(j);
	}

	private static void evento(int tipo, ServerPlayer j, int segundo, int dim, int x, int z, int icono, int extra) {
		g.eventos.add(new EventoG(tipo, j.getUUID(), segundo, dim, x, z, icono, extra));
	}

	private static void alCambiarNivel(ServerPlayer j, ServerLevel origen, ServerLevel destino) {
		if (!grabando(j)) return;
		int dO = dim(partida, origen), dD = dim(partida, destino);
		if (dO < 0 || dD < 0) return;
		Recorrido r = recorrido(partida, j);
		int s = partida.segundos();
		if (r.posDim == dO) {
			punto(r, s, dO, r.posX, r.posZ, true);
			evento(DatosRuta.PORTAL_SALIDA, j, s, dO, r.posX, r.posZ, -1, dD);
		}
		r.corte = true;
		punto(r, s, dD, j.getBlockX(), j.getBlockZ(), true);
		evento(DatosRuta.PORTAL_LLEGADA, j, s, dD, j.getBlockX(), j.getBlockZ(), -1, dO);
		r.posDim = dD;
		r.posX = j.getBlockX();
		r.posZ = j.getBlockZ();
	}

	private static void alMorir(LivingEntity entidad, DamageSource fuente) {
		if (!(entidad instanceof ServerPlayer j) || !grabando(j)) return;
		int d = dim(partida, j.level());
		if (d < 0) return;
		Recorrido r = recorrido(partida, j);
		int s = partida.segundos();
		punto(r, s, d, j.getBlockX(), j.getBlockZ(), true);
		evento(DatosRuta.MUERTE, j, s, d, j.getBlockX(), j.getBlockZ(), -1, -1);
		r.corte = true; // reaparece en otro sitio: la línea no se une
	}

	/** La llama Partida al marcar una casilla (o un item del Rastreo). */
	public static void casilla(ServerPlayer j, ItemStack icono) {
		if (!grabando(j)) return;
		int d = dim(partida, j.level());
		if (d < 0) return;
		int s = partida.segundos();
		punto(recorrido(partida, j), s, d, j.getBlockX(), j.getBlockZ(), true);
		evento(DatosRuta.CASILLA, j, s, d, j.getBlockX(), j.getBlockZ(), indiceIcono(icono), -1);
	}

	/** Se compró una ayuda de pago (la llaman las estadísticas al registrarla). */
	public static void ayuda(UUID jugador) {
		if (server == null) return;
		ServerPlayer j = server.getPlayerList().getPlayer(jugador);
		if (j == null || !grabando(j)) return;
		int d = dim(partida, j.level());
		if (d >= 0) evento(DatosRuta.AYUDA, j, partida.segundos(), d, j.getBlockX(), j.getBlockZ(), -1, -1);
	}

	private static int indiceIcono(ItemStack icono) {
		for (int i = 0; i < g.iconos.size(); i++) if (ItemStack.isSameItemSameComponents(g.iconos.get(i), icono)) return i;
		g.iconos.add(icono.copyWithCount(1));
		return g.iconos.size() - 1;
	}

	// ---------------------------------------------------------------- terreno

	/** Pone en cola los chunks cargados alrededor de cada jugador que todavía no se muestrearon. */
	private static void buscarChunks(Partida p) {
		for (ServerPlayer j : enJuego(p)) {
			int d = dim(p, j.level());
			Capa c = g.capas[d];
			if (c.chunks.size() + c.enCola.size() >= MAX_CHUNKS) continue;
			ServerLevel nivel = j.level();
			boolean techo = nivel.dimensionType().hasCeiling();
			int r = techo ? RADIO_TECHO : RADIO;
			int pcx = j.getBlockX() >> 4, pcz = j.getBlockZ() >> 4;
			for (int dz = -r; dz <= r; dz++) {
				for (int dx = -r; dx <= r; dx++) {
					if (dx * dx + dz * dz > r * r + r) continue;
					long k = ChunkPos.pack(pcx + dx, pcz + dz);
					if (c.chunks.containsKey(k) || c.enCola.contains(k)) continue;
					if (nivel.getChunkSource().getChunkNow(pcx + dx, pcz + dz) == null) continue;
					c.cola.enqueue(k);
					c.enCola.add(k);
					if (techo) c.refY.put(k, j.getBlockY());
				}
			}
		}
	}

	/** Muestrea hasta n chunks de las colas, alternando dimensiones. */
	private static void muestrear(Partida p, int n) {
		int hechos = 0, vacias = 0;
		while (hechos < n && vacias < g.capas.length) {
			int d = g.capaSiguiente;
			g.capaSiguiente = (d + 1) % g.capas.length;
			Capa c = g.capas[d];
			if (c.cola.isEmpty()) {
				vacias++;
				continue;
			}
			vacias = 0;
			long k = c.cola.dequeueLong();
			c.enCola.remove(k);
			ServerLevel nivel = nivel(p, d);
			LevelChunk chunk = nivel.getChunkSource().getChunkNow(ChunkPos.getX(k), ChunkPos.getZ(k));
			int refY = c.refY.remove(k);
			if (chunk == null) continue; // se descargó: si alguien vuelve a pasar, se pone otra vez en cola
			boolean algo = g.muestra.muestrear(nivel, chunk, nivel.dimensionType().hasCeiling(), refY, g.buffer);
			c.chunks.put(k, algo ? g.buffer.clone() : VACIO);
			hechos++;
		}
	}

	// ---------------------------------------------------------------- armado y envío

	private static DatosRuta armar(Partida p, boolean enVivo) {
		Map<UUID, Integer> indices = new HashMap<>();
		List<DatosRuta.Jugador> jugadores = new ArrayList<>();
		for (Recorrido r : g.recorridos.values()) {
			indices.put(r.uuid, jugadores.size());
			jugadores.add(new DatosRuta.Jugador(r.uuid, r.nombre, r.equipo, List.copyOf(r.puntos)));
		}
		List<DatosRuta.Evento> eventos = new ArrayList<>();
		for (EventoG e : g.eventos) {
			eventos.add(new DatosRuta.Evento(e.tipo, indices.getOrDefault(e.jugador, -1), e.segundo, e.dim, e.x, e.z, e.icono, e.extra));
		}
		int total = 0;
		for (Capa c : g.capas) for (byte[] b : c.chunks.values()) if (b.length > 0) total++;
		int m = total > CHUNKS_RESOLUCION_BAJA ? 1 : total > CHUNKS_RESOLUCION_MEDIA ? 2 : DatosRuta.MUESTRAS_POR_LADO;
		int paso = DatosRuta.MUESTRAS_POR_LADO / m;
		List<DatosRuta.Terreno> terrenos = new ArrayList<>();
		for (int d = 0; d < g.capas.length; d++) {
			Long2ObjectOpenHashMap<byte[]> chunks = g.capas[d].chunks;
			long[] claves = chunks.long2ObjectEntrySet().stream().filter(e -> e.getValue().length > 0)
				.mapToLong(e -> e.getLongKey()).toArray();
			if (claves.length == 0) continue;
			// Por filas (z y luego x): las diferencias entre chunks seguidos quedan pequeñas.
			Long[] orden = Arrays.stream(claves).boxed().toArray(Long[]::new);
			Arrays.sort(orden, (a, b) -> ChunkPos.getZ(a) != ChunkPos.getZ(b) ? Integer.compare(ChunkPos.getZ(a), ChunkPos.getZ(b))
				: Integer.compare(ChunkPos.getX(a), ChunkPos.getX(b)));
			byte[] colores = new byte[orden.length * m * m];
			for (int k = 0; k < orden.length; k++) {
				claves[k] = orden[k];
				byte[] muestra = chunks.get(claves[k]);
				// Con menos resolución se toma la muestra del centro de cada cuadro de paso × paso.
				for (int j = 0; j < m; j++) {
					for (int i = 0; i < m; i++) {
						colores[k * m * m + j * m + i] = muestra[(j * paso + paso / 2) * DatosRuta.MUESTRAS_POR_LADO + i * paso + paso / 2];
					}
				}
			}
			terrenos.add(new DatosRuta.Terreno(d, m, claves, colores));
		}
		return new DatosRuta(p.segundos(), enVivo, jugadores, eventos, List.copyOf(g.spawns), terrenos);
	}

	/** Los iconos de las casillas, copiados para mandarlos (la grabación sigue cambiando si la partida sigue). */
	private static List<ItemStack> copiarIconos() {
		List<ItemStack> iconos = new ArrayList<>();
		for (ItemStack s : g.iconos) iconos.add(s.copy());
		return iconos;
	}

	/** Ruta comprimida en trozos que caben en un paquete. */
	private static Envio empaquetar(byte[] comprimida, List<ItemStack> iconos) {
		List<byte[]> trozos = new ArrayList<>();
		for (int i = 0; i < comprimida.length || trozos.isEmpty(); i += PaquetesRuta.TAM_TROZO) {
			trozos.add(Arrays.copyOfRange(comprimida, i, Math.min(comprimida.length, i + PaquetesRuta.TAM_TROZO)));
		}
		return new Envio(siguienteId++, iconos, trozos);
	}

	/**
	 * Comprime fuera del hilo del servidor (con mucho terreno son cientos de KB y no debe dar tirones) y entrega el
	 * resultado de vuelta en el hilo del servidor, si sigue siendo la misma partida. null si falló.
	 */
	private static void comprimir(DatosRuta datos, List<ItemStack> iconos, Consumer<Envio> listo) {
		int gen = generacion;
		CompletableFuture.supplyAsync(datos::comprimir, Util.backgroundExecutor())
			.whenCompleteAsync((bytes, error) -> {
				if (gen != generacion) return;
				if (error != null) {
					Venatio.LOG.error("No se pudo comprimir la ruta de la partida", error);
					listo.accept(null);
					return;
				}
				listo.accept(empaquetar(bytes, iconos));
			}, server);
	}

	private static void enviar(ServerPlayer j, Envio e, boolean abrir) {
		for (int i = 0; i < e.trozos.size(); i++) {
			ServerPlayNetworking.send(j, new PaquetesRuta.RutaTrozo(e.id, i, e.trozos.size(), abrir, i == 0 ? e.iconos : List.of(), e.trozos.get(i)));
		}
	}

	/**
	 * La llaman las estadísticas al terminar la partida: termina de muestrear, arma la ruta, la manda a participantes
	 * y espectadores y la guarda para /venatio ruta.
	 */
	public static void terminar(Partida p) {
		if (g == null || p != partida) return;
		muestrear(p, Integer.MAX_VALUE); // lo que quedó en cola sigue cargado: los jugadores todavía están ahí
		anotarPuntos(p, true);
		DatosRuta datos = armar(p, false);
		ultimaDatos = datos;
		List<UUID> destinatarios = new ArrayList<>();
		for (ServerPlayer j : server.getPlayerList().getPlayers()) {
			if ((p.participa(j) || Espectadores.es(j)) && ServerPlayNetworking.canSend(j, PaquetesRuta.RutaTrozo.TYPE)) destinatarios.add(j.getUUID());
		}
		preparando = true;
		comprimir(datos, copiarIconos(), envio -> {
			preparando = false;
			if (envio == null) return;
			ultima = envio;
			int puntos = datos.jugadores().stream().mapToInt(j -> j.puntos().size()).sum();
			int chunks = datos.terrenos().stream().mapToInt(DatosRuta.Terreno::cantidad).sum();
			int m = datos.terrenos().isEmpty() ? 0 : datos.terrenos().getFirst().muestras();
			Venatio.LOG.info("Ruta de la partida: {} jugadores, {} puntos, {} eventos, {} chunks de terreno ({}×{} colores por chunk), {} KB en {} trozo(s)",
				datos.jugadores().size(), puntos, datos.eventos().size(), chunks, m, m,
				envio.trozos.stream().mapToInt(t -> t.length).sum() / 1024, envio.trozos.size());
			Component aviso = Component.translatable("venatio.ruta.lista", Component.translatable("venatio.ruta.ver")
				.withStyle(s -> s.withColor(ChatFormatting.GOLD).withUnderlined(true)
					.withClickEvent(new ClickEvent.RunCommand("/venatio ruta"))
					.withHoverEvent(new HoverEvent.ShowText(Component.translatable("venatio.ruta.ver.ayuda")))));
			for (UUID u : destinatarios) {
				ServerPlayer j = server.getPlayerList().getPlayer(u);
				if (j == null) continue;
				enviar(j, envio, false);
				j.sendSystemMessage(aviso);
			}
		});
	}

	/**
	 * /venatio ruta: la ruta de la última partida (hasta que empiece otra). Durante la partida solo los espectadores
	 * pueden verla, y en vivo. Devuelve el error o null.
	 */
	public static Component pedir(ServerPlayer j) {
		if (!ServerPlayNetworking.canSend(j, PaquetesRuta.RutaTrozo.TYPE)) return Component.translatable("venatio.ruta.error.sin_mod");
		Partida p = Juego.INSTANCIA.partida();
		if (p != null && p == partida && g != null && (p.fase() == Partida.Fase.CUENTA_ATRAS || p.fase() == Partida.Fase.JUGANDO)) {
			if (!Espectadores.es(j)) return Component.translatable("venatio.ruta.error.en_juego");
			if (foto != null && ticks - fotoTick <= TICKS_FOTO) {
				enviar(j, foto, true);
				return null;
			}
			// Foto nueva: se arma aquí y se comprime aparte; les llega a todos los que la pidieron mientras tanto.
			pendientesFoto.add(j.getUUID());
			if (!fotoEnCamino) {
				fotoEnCamino = true;
				comprimir(armar(p, true), copiarIconos(), envio -> {
					fotoEnCamino = false;
					if (envio != null) {
						foto = envio;
						fotoTick = ticks;
						for (UUID u : pendientesFoto) {
							ServerPlayer o = server.getPlayerList().getPlayer(u);
							if (o != null) enviar(o, envio, true);
						}
					}
					pendientesFoto.clear();
				});
			}
			return null;
		}
		if (ultima == null) return Component.translatable(preparando ? "venatio.ruta.error.preparando" : "venatio.ruta.error.sin_ruta");
		enviar(j, ultima, true);
		return null;
	}

	// ---------------------------------------------------------------- consultas (pruebas)

	/** Puntos grabados de un jugador en la partida en curso (0 si no hay). */
	public static int puntosDe(UUID jugador) {
		if (g == null) return 0;
		Recorrido r = g.recorridos.get(jugador);
		return r == null ? 0 : r.puntos.size();
	}

	/** Chunks con terreno muestreado en una dimensión de la partida en curso. */
	public static int chunksMuestreados(int dim) {
		if (g == null) return 0;
		int n = 0;
		for (byte[] b : g.capas[dim].chunks.values()) if (b.length > 0) n++;
		return n;
	}

	/** Eventos de un tipo grabados en la partida en curso. */
	public static int eventos(int tipo) {
		if (g == null) return 0;
		int n = 0;
		for (EventoG e : g.eventos) if (e.tipo == tipo) n++;
		return n;
	}

	/** La ruta de la última partida terminada, o null. */
	public static DatosRuta ultima() {
		return ultimaDatos;
	}

	/** {bytes comprimidos, trozos} de la última ruta ya lista para mandar, o null mientras se comprime. */
	public static int[] tamanoUltima() {
		Envio e = ultima;
		return e == null ? null : new int[]{e.trozos.stream().mapToInt(t -> t.length).sum(), e.trozos.size()};
	}
}
