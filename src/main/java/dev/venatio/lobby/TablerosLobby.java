package dev.venatio.lobby;

import com.mojang.math.Transformation;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tableros de texto flotante del lobby (ranking, partida, equipos, última partida), más el título
 * y el rótulo de cada zona de equipo. Son entidades TextDisplay marcadas con la etiqueta ETIQUETA.
 * La firma pública no debe cambiar.
 */
public final class TablerosLobby {
	public enum Tablero { RANKING, PARTIDA, EQUIPOS, ULTIMA_PARTIDA }

	static final String ETIQUETA = "venatio_tablero";

	/** Separación entre el texto y la pared de atrás. */
	private static final double DESPEGUE = 0.05;
	private static final int FONDO_TABLERO = 0xB0101010;
	private static final int FONDO_ROTULO = 0x60000000;
	private static final int COLOR_TITULO = 0xF2B84B;
	/**
	 * Ancho de línea de los tableros en píxeles de texto. La regla bajo el título mide casi eso
	 * (48 espacios de 4 px), así el fondo sale del mismo ancho diga lo que diga el tablero.
	 */
	private static final int ANCHO_LINEA = 200;
	private static final Component REGLA = Component.literal(" ".repeat(48)).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.STRIKETHROUGH);

	/**
	 * Dónde va un tablero. El texto crece hacia arriba desde pos y mide, en bloques,
	 * 5 × escala de ancho y lineas × escala / 4 de alto. yaw: hacia dónde mira (0 = sur, 90 = oeste).
	 */
	private record Ficha(Vec3 pos, float yaw, float escala, int lineas) {}

	private static final Map<Tablero, Ficha> FICHAS = new EnumMap<>(Tablero.class);
	static {
		double z = TrazadoLobby.Z_FACHADA + 1 + DESPEGUE;
		FICHAS.put(Tablero.RANKING, new Ficha(new Vec3(0.5, 68, z), 0f, 2.4f, 12));
		FICHAS.put(Tablero.PARTIDA, new Ficha(new Vec3(-14.5, 68, z), 0f, 1.5f, 10));
		FICHAS.put(Tablero.EQUIPOS, new Ficha(new Vec3(15.5, 68, z), 0f, 1.5f, 10));
		FICHAS.put(Tablero.ULTIMA_PARTIDA, new Ficha(new Vec3(TrazadoLobby.X_MURO_ESTE - DESPEGUE, 66.5, 0.5), 90f, 1.5f, 10));
	}

	private static final Map<Tablero, Component> contenido = new EnumMap<>(Tablero.class);
	private static final Map<Tablero, Display.TextDisplay> entidades = new EnumMap<>(Tablero.class);
	private static final Map<Tablero, UUID> ids = new EnumMap<>(Tablero.class);
	private static final Map<UUID, Tablero> porId = new HashMap<>();
	/** Título y rótulos: textos fijos de este arranque. */
	private static final Set<UUID> fijos = new HashSet<>();
	private static ServerLevel nivel;
	private static boolean eventos;

	private TablerosLobby() {}

	/** Se llama al arrancar el server, después de ConstructorLobby.construir. */
	public static void crear(ServerLevel nivelLobby) {
		registrarEventos();
		nivel = nivelLobby;
		contenido.clear();
		entidades.clear();
		ids.clear();
		porId.clear();
		fijos.clear();

		// Los de un arranque anterior que ya estén cargados se van ahora; el resto, al cargarse (ver registrarEventos).
		List<Entity> viejos = new ArrayList<>();
		for (Entity e : nivelLobby.getAllEntities()) {
			if (e != null && e.entityTags().contains(ETIQUETA)) viejos.add(e);
		}
		viejos.forEach(Entity::discard);

		iniciar(Tablero.RANKING, "Ranking", "Todavía no hay partidas");
		iniciar(Tablero.PARTIDA, "Próxima partida", "Modo: 5 en línea");
		iniciar(Tablero.EQUIPOS, "Equipos", "Párate en una zona de color", "para unirte");
		iniciar(Tablero.ULTIMA_PARTIDA, "Última partida", "—");

		for (Tablero t : Tablero.values()) {
			Ficha f = FICHAS.get(t);
			Display.TextDisplay d = nuevo(nivelLobby, f.pos(), f.yaw(), f.escala(), Display.BillboardConstraints.FIXED, FONDO_TABLERO);
			d.setText(contenido.get(t));
			ids.put(t, d.getUUID());
			porId.put(d.getUUID(), t);
			entidades.put(t, d);
			nivelLobby.addFreshEntity(d);
		}

		// Título sobre el ranking, sin fondo: lo respalda el panel de pizarra de la fachada.
		fijo(nivelLobby, new Vec3(0.5, 77.5, TrazadoLobby.Z_FACHADA + 1 + DESPEGUE), 8f, Display.BillboardConstraints.FIXED, 0,
			Component.literal("VENATIO").withStyle(ChatFormatting.BOLD).withColor(COLOR_TITULO));

		// Rótulo sobre cada zona; este sí gira con la cámara para leerse desde cualquier lado.
		for (TrazadoLobby.Zona zona : TrazadoLobby.ZONAS) {
			fijo(nivelLobby, new Vec3(zona.cx() + 0.5, TrazadoLobby.Y_PISO + 5, zona.cz() + 0.5), 1.25f, Display.BillboardConstraints.VERTICAL, FONDO_ROTULO,
				Component.literal("Equipo " + zona.nombre()).withStyle(ChatFormatting.BOLD).withColor(zona.equipo().rgb));
		}
	}

	/**
	 * Reemplaza el contenido de un tablero. Solo cambia el texto de la entidad existente; si no está
	 * cargada, el texto se guarda y se le pone cuando cargue. Las líneas que no caben se recortan.
	 */
	public static void actualizar(Tablero tablero, Component titulo, List<Component> lineas) {
		if (tablero == null) return;
		Component texto = componer(FICHAS.get(tablero).lineas(), titulo, lineas);
		contenido.put(tablero, texto);
		Display.TextDisplay d = entidad(tablero);
		if (d != null) d.setText(texto);
	}

	private static void iniciar(Tablero t, String titulo, String... lineas) {
		List<Component> l = new ArrayList<>();
		for (String s : lineas) l.add(Component.literal(s));
		contenido.put(t, componer(FICHAS.get(t).lineas(), Component.literal(titulo), l));
	}

	/**
	 * Título, regla y líneas en un solo texto, siempre con el mismo número de renglones:
	 * como el texto crece hacia arriba, así el título no se mueve y el fondo no cambia de alto.
	 */
	private static Component componer(int renglones, Component titulo, List<Component> lineas) {
		MutableComponent texto = Component.empty();
		texto.append(Component.empty().withStyle(ChatFormatting.BOLD).withColor(COLOR_TITULO).append(titulo == null ? Component.empty() : titulo));
		texto.append("\n").append(REGLA);
		int hay = lineas == null ? 0 : lineas.size();
		int cupo = renglones - 2;
		for (int i = 0; i < cupo; i++) {
			texto.append("\n");
			Component linea = i < hay ? lineas.get(i) : null;
			if (i == cupo - 1 && hay > cupo) {
				texto.append(Component.literal("…").withStyle(ChatFormatting.GRAY));
			} else if (linea == null || linea.getString().isEmpty()) {
				texto.append(" ");
			} else {
				texto.append(linea);
			}
		}
		return texto;
	}

	private static Display.TextDisplay entidad(Tablero t) {
		Display.TextDisplay d = entidades.get(t);
		if (d != null && !d.isRemoved()) return d;
		UUID id = ids.get(t);
		if (id == null || nivel == null) return null;
		if (nivel.getEntity(id) instanceof Display.TextDisplay cargada) {
			entidades.put(t, cargada);
			return cargada;
		}
		return null;
	}

	private static void fijo(ServerLevel nivelLobby, Vec3 pos, float escala, Display.BillboardConstraints giro, int fondo, Component texto) {
		Display.TextDisplay d = nuevo(nivelLobby, pos, 0f, escala, giro, fondo);
		d.setText(texto);
		fijos.add(d.getUUID());
		nivelLobby.addFreshEntity(d);
	}

	private static Display.TextDisplay nuevo(ServerLevel nivelLobby, Vec3 pos, float yaw, float escala, Display.BillboardConstraints giro, int fondo) {
		Display.TextDisplay d = new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, nivelLobby);
		d.snapTo(pos.x, pos.y, pos.z, yaw, 0f);
		d.setTransformation(new Transformation(null, null, new Vector3f(escala, escala, escala), null));
		d.setBillboardConstraints(giro);
		d.setBrightnessOverride(Brightness.FULL_BRIGHT);
		// Alcance amplio: con el valor por defecto dejan de verse a media plaza si el cliente baja la distancia de entidades.
		d.setViewRange(4f);
		d.setLineWidth(ANCHO_LINEA);
		d.setBackgroundColor(fondo);
		d.setFlags(Display.TextDisplay.FLAG_SHADOW);
		d.addTag(ETIQUETA);
		return d;
	}

	private static void registrarEventos() {
		if (eventos) return;
		eventos = true;
		// Los tableros se guardan con el mundo. Al cargarse del disco: los de este arranque recuperan su texto
		// (pudo cambiar mientras estaban descargados) y los de arranques anteriores no entran.
		ServerEntityEvents.ALLOW_LOAD.register((entidad, nivelEntidad, razon, deDisco) -> {
			if (!deDisco || nivelEntidad != nivel || !entidad.entityTags().contains(ETIQUETA)) return true;
			Tablero t = porId.get(entidad.getUUID());
			if (t == null) return fijos.contains(entidad.getUUID());
			if (entidad instanceof Display.TextDisplay d) {
				entidades.put(t, d);
				Component texto = contenido.get(t);
				if (texto != null) d.setText(texto);
			}
			return true;
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			nivel = null;
			entidades.clear();
		});
	}
}
