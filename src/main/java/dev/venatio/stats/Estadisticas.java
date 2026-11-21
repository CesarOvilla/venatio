package dev.venatio.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.venatio.Venatio;
import dev.venatio.juego.ResumenPartida;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Estadísticas persistentes por jugador y ranking por temporada, en config/venatio/estadisticas.json del server.
 * Todo se modifica en el hilo del servidor; el archivo se escribe en otro hilo, de forma atómica
 * (temporal y renombrar). Un archivo dañado se aparta y se empieza de cero sin tumbar el server.
 */
public final class Estadisticas {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final ExecutorService ESCRITOR = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Venatio-Estadisticas");
		t.setDaemon(true);
		return t;
	});

	private static Datos datos;
	private static Path archivo;
	private static MinecraftServer server;

	private Estadisticas() {}

	// ---------------------------------------------------------------- formato del JSON

	static final class Datos {
		int version = 1;
		int temporada = 1;
		String inicioTemporada = LocalDate.now().toString();
		Map<String, Jugador> jugadores = new LinkedHashMap<>();
		UltimaPartida ultimaPartida;
	}

	static final class Jugador {
		String nombre = "?";
		int partidas;
		/** Modo (id) → victorias. */
		Map<String, Integer> victorias = new LinkedHashMap<>();
		int casillas;
		/** Segundos del bingo más rápido; null si nunca ganó. */
		Integer bingoMasRapido;
		CasillaRapida casillaMasRapida;
		int ayudas;
		/** Número de temporada → resultados en ella. */
		Map<String, Temporada> temporadas = new LinkedHashMap<>();

		int victoriasTotales() {
			int n = 0;
			for (Integer v : victorias.values()) n += v == null ? 0 : v;
			return n;
		}
	}

	static final class Temporada {
		int puntos;
		int victorias;
		int casillas;
		int partidas;
	}

	static final class CasillaRapida {
		String item;
		int segundos;
	}

	static final class UltimaPartida {
		String modo;
		/** Id del color ganador o null. */
		String ganador;
		boolean empate;
		boolean cancelada;
		int duracion;
		String fecha;
		List<EquipoUltima> equipos = new ArrayList<>();
		String destacado;
		int destacadoCasillas;
	}

	static final class EquipoUltima {
		String color;
		int casillas;
		int puesto;
		int puntos;
		List<String> miembros = new ArrayList<>();
	}

	// ---------------------------------------------------------------- API pública

	/** Suma una ayuda comprada al jugador. La llama el módulo de ayudas al cobrar. */
	public static void registrarAyuda(UUID jugador) {
		dev.venatio.ruta.GrabadoraRuta.ayuda(jugador); // queda marcada en la ruta de la partida
		if (datos == null || jugador == null) return;
		Jugador j = jugador(jugador);
		j.ayudas++;
		guardar();
	}

	/** Temporada en curso. */
	public static int temporada() {
		return datos == null ? 1 : datos.temporada;
	}

	// ---------------------------------------------------------------- ciclo de vida

	static void cargar(MinecraftServer srv) {
		server = srv;
		archivo = FabricLoader.getInstance().getConfigDir().resolve("venatio").resolve("estadisticas.json");
		datos = leer(archivo);
		Venatio.LOG.info("Estadísticas cargadas: {} jugadores, temporada {}", datos.jugadores.size(), datos.temporada);
	}

	/** Espera a que termine la última escritura (al apagar el server). */
	static void cerrar() {
		if (datos != null) guardar();
		try {
			ESCRITOR.submit(() -> {}).get(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			Venatio.LOG.warn("No se pudo esperar a que se guardaran las estadísticas", e);
		}
		datos = null;
		server = null;
	}

	static boolean cargadas() {
		return datos != null;
	}

	static Datos datos() {
		return datos;
	}

	static Path archivo() {
		return archivo;
	}

	private static Datos leer(Path ruta) {
		if (!Files.exists(ruta)) return new Datos();
		try {
			Datos d = GSON.fromJson(Files.readString(ruta, StandardCharsets.UTF_8), Datos.class);
			if (d == null) throw new IllegalStateException("archivo vacío");
			return normalizar(d);
		} catch (Exception e) {
			String sello = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
			Path apartado = ruta.resolveSibling("estadisticas.corrupto-" + sello + ".json");
			Venatio.LOG.error("estadisticas.json está dañado; se aparta como {} y se empieza de cero", apartado.getFileName(), e);
			try {
				Files.move(ruta, apartado, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ex) {
				Venatio.LOG.error("No se pudo apartar el archivo dañado", ex);
			}
			return new Datos();
		}
	}

	/** Rellena lo que falte o venga como null en un archivo editado a mano o de una versión anterior. */
	private static Datos normalizar(Datos d) {
		if (d.temporada < 1) d.temporada = 1;
		if (d.inicioTemporada == null) d.inicioTemporada = LocalDate.now().toString();
		if (d.jugadores == null) d.jugadores = new LinkedHashMap<>();
		d.jugadores.values().removeIf(j -> j == null);
		for (Jugador j : d.jugadores.values()) {
			if (j.nombre == null) j.nombre = "?";
			if (j.victorias == null) j.victorias = new LinkedHashMap<>();
			if (j.temporadas == null) j.temporadas = new LinkedHashMap<>();
			j.temporadas.values().removeIf(t -> t == null);
			if (j.casillaMasRapida != null && j.casillaMasRapida.item == null) j.casillaMasRapida = null;
		}
		if (d.ultimaPartida != null && d.ultimaPartida.equipos == null) d.ultimaPartida.equipos = new ArrayList<>();
		return d;
	}

	/** Serializa ahora (hilo del server) y escribe en segundo plano: temporal + renombrar. */
	static void guardar() {
		if (datos == null || archivo == null) return;
		String json = GSON.toJson(datos);
		Path destino = archivo;
		ESCRITOR.execute(() -> escribir(destino, json));
	}

	private static void escribir(Path destino, String json) {
		try {
			Files.createDirectories(destino.getParent());
			Path temporal = destino.resolveSibling(destino.getFileName() + ".tmp");
			Files.writeString(temporal, json, StandardCharsets.UTF_8);
			try {
				Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			Venatio.LOG.error("No se pudieron guardar las estadísticas en {}", destino, e);
		}
	}

	// ---------------------------------------------------------------- registro de partidas

	static Jugador jugador(UUID id) {
		Jugador j = datos.jugadores.computeIfAbsent(id.toString(), k -> new Jugador());
		ServerPlayer enLinea = server == null ? null : server.getPlayerList().getPlayer(id);
		if (enLinea != null) j.nombre = enLinea.getGameProfile().name();
		return j;
	}

	static Temporada temporadaDe(Jugador j, int temporada) {
		return j.temporadas.computeIfAbsent(String.valueOf(temporada), k -> new Temporada());
	}

	/** Suma la partida a cada participante y la deja como última partida. Las canceladas solo quedan como última. */
	static void registrarPartida(ResumenPartida r) {
		if (datos == null) return;
		datos.ultimaPartida = ultima(r);
		if (!r.cancelada()) {
			// Casillas que consiguió cada jugador en persona: cuántas y la más rápida.
			Map<UUID, Integer> propias = new LinkedHashMap<>();
			Map<UUID, ResumenPartida.Hito> rapidas = new LinkedHashMap<>();
			for (ResumenPartida.Hito h : r.hitos()) {
				if (h.jugador() == null) continue;
				propias.merge(h.jugador(), 1, Integer::sum);
				rapidas.putIfAbsent(h.jugador(), h);
			}
			String modo = r.modo().id();
			r.participantes().forEach((uuid, equipo) -> {
				Jugador j = jugador(uuid);
				String nombre = r.nombre(uuid);
				if (!"?".equals(nombre)) j.nombre = nombre;
				Temporada t = temporadaDe(j, datos.temporada);
				int casillas = propias.getOrDefault(uuid, 0);
				j.partidas++;
				t.partidas++;
				j.casillas += casillas;
				t.casillas += casillas;
				ResumenPartida.Hito rapida = rapidas.get(uuid);
				if (rapida != null && (j.casillaMasRapida == null || rapida.segundo() < j.casillaMasRapida.segundos)) {
					CasillaRapida c = new CasillaRapida();
					c.item = dev.venatio.carton.Objetivo.clave(rapida.icono());
					c.segundos = rapida.segundo();
					j.casillaMasRapida = c;
				}
				if (equipo == r.ganador()) {
					j.victorias.merge(modo, 1, Integer::sum);
					t.victorias++;
					if (r.segundoVictoria() >= 0 && (j.bingoMasRapido == null || r.segundoVictoria() < j.bingoMasRapido)) {
						j.bingoMasRapido = r.segundoVictoria();
					}
				}
				ResumenPartida.Equipo e = r.equipo(equipo);
				if (e != null) t.puntos += Ranking.puntosPorPuesto(e.puesto(), e.casillas());
			});
		}
		guardar();
	}

	private static UltimaPartida ultima(ResumenPartida r) {
		UltimaPartida u = new UltimaPartida();
		u.modo = r.modo().id();
		u.ganador = r.ganador() == null ? null : r.ganador().id();
		u.empate = r.empate();
		u.cancelada = r.cancelada();
		u.duracion = r.duracion();
		u.fecha = LocalDateTime.now().withNano(0).toString();
		for (ResumenPartida.Equipo e : r.equipos()) {
			EquipoUltima q = new EquipoUltima();
			q.color = e.color().id();
			q.casillas = e.casillas();
			q.puesto = e.puesto();
			q.puntos = r.cancelada() ? 0 : Ranking.puntosPorPuesto(e.puesto(), e.casillas());
			for (UUID m : e.miembros()) q.miembros.add(r.nombre(m));
			u.equipos.add(q);
		}
		ResumenPartida.Destacado d = r.destacado();
		if (d != null) {
			u.destacado = r.nombre(d.jugador());
			u.destacadoCasillas = d.casillas();
		}
		return u;
	}

	/** Empieza una temporada nueva: el ranking vuelve a cero; lo de temporadas anteriores se conserva. */
	static int nuevaTemporada() {
		if (datos == null) return 1;
		datos.temporada++;
		datos.inicioTemporada = LocalDate.now().toString();
		guardar();
		return datos.temporada;
	}
}
