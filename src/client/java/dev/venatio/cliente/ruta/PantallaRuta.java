package dev.venatio.cliente.ruta;

import com.mojang.blaze3d.platform.InputConstants;
import dev.venatio.cliente.Arte;
import dev.venatio.cliente.EnPartida;
import dev.venatio.juego.ColorEquipo;
import dev.venatio.ruta.DatosRuta;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pestaña "Ruta": mapa con el terreno explorado, la línea de cada jugador con el color de su equipo y su cabeza al
 * final, e iconos donde consiguió cada casilla, donde murió, los portales, las ayudas y la salida de cada equipo.
 * Rueda para el zoom (hacia el ratón), arrastrar para moverse, botones para cambiar de dimensión. Al pasar el ratón
 * por un icono dice qué pasó ahí y a qué minuto.
 */
public final class PantallaRuta extends Screen {
	private static final int MARGEN = 12;
	private static final int ARRIBA = 30;
	private static final int ABAJO = 28;
	private static final int ANCHO_LEYENDA = 120;
	/** Píxeles de interfaz por bloque: de muy lejos (cabe un recorrido de decenas de miles de bloques) a muy cerca. */
	private static final float ESCALA_MIN = 0.002f, ESCALA_MAX = 6f;
	private static final float PASO_ZOOM = 1.25f;
	private static final int FONDO_MAPA = 0xFF17110C;
	private static final int FONDO_ICONO = 0xF0231A12;
	private static final int OBSIDIANA = 0xFF1A1028, PORTAL = 0xFF8C4BE6, PORTAL_CLARO = 0xFFC79BFF;
	private static final ItemStack CALAVERA = new ItemStack(Items.SKELETON_SKULL);
	private static final ItemStack DIAMANTE = new ItemStack(Items.DIAMOND);
	/** Tipos de icono que no son eventos. */
	private static final int SPAWN = 10, CABEZA = 11;

	private final Screen anterior;
	private final DatosRuta ruta;
	private final List<ItemStack> iconos;
	private final List<Integer> dimensiones = new ArrayList<>();
	/** Color de la línea de cada jugador: el de su equipo, más claro u oscuro si hay varios en el mismo equipo. */
	private final int[] colores;
	private int dim;
	private double centroX, centroZ;
	/** Píxeles de interfaz por bloque. */
	private float escala = 1f;
	private boolean ajustada, arrastrando;
	private int anchoAjustado, altoAjustado;
	private int mx0, my0, mx1, my1, leyendaX;
	private boolean conLeyenda;
	private final LineasRuta lineas = new LineasRuta();

	// Lo que quedó dibujado en el último cuadro (para el ratón y para las pruebas)
	private record Icono(int x, int y, int lado, int tipo, int indice) {}

	private final List<Icono> visibles = new ArrayList<>();
	private final List<int[]> filasLeyenda = new ArrayList<>();
	private int tramos;
	private boolean terrenoVisible;
	private int mosaicosVisibles;
	private List<Component> hover;

	public PantallaRuta(Screen anterior, DatosRuta ruta, List<ItemStack> iconos) {
		super(ruta.enVivo() ? Component.translatable("venatio.ruta.titulo_vivo", EnPartida.reloj(ruta.duracion()))
			: Component.translatable("venatio.ruta.titulo"));
		this.anterior = anterior;
		this.ruta = ruta;
		this.iconos = iconos;
		for (int d = DatosRuta.MUNDO; d <= DatosRuta.END; d++) if (ruta.tieneDimension(d)) dimensiones.add(d);
		dim = dimensiones.isEmpty() ? DatosRuta.MUNDO : dimensiones.getFirst();
		colores = new int[ruta.jugadores().size()];
		Map<Integer, Integer> porEquipo = new HashMap<>();
		for (int k = 0; k < colores.length; k++) {
			int equipo = ruta.jugadores().get(k).equipo();
			int n = porEquipo.merge(equipo, 1, Integer::sum) - 1;
			ColorEquipo c = ColorEquipo.porOrdinal(equipo);
			int rgb = c == null ? 0xC0C0C0 : c.rgb;
			if (n % 3 == 1) rgb = ARGB.srgbLerp(0.4f, rgb, 0xFFFFFF);
			else if (n % 3 == 2) rgb = ARGB.srgbLerp(0.35f, rgb, 0x000000);
			colores[k] = 0xFF000000 | (rgb & 0xFFFFFF);
		}
	}

	public Screen anterior() {
		return anterior;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(anterior);
	}

	@Override
	protected void init() {
		conLeyenda = width >= 360;
		mx0 = MARGEN;
		my0 = ARRIBA;
		mx1 = width - MARGEN - (conLeyenda ? ANCHO_LEYENDA + 10 : 0);
		my1 = height - ABAJO - 6;
		leyendaX = mx1 + 10;
		// Al abrir, y si cambia el tamaño del mapa (ventana o escala de la interfaz), se encuadra todo de nuevo.
		if (!ajustada || mx1 - mx0 != anchoAjustado || my1 - my0 != altoAjustado) {
			ajustar();
			ajustada = true;
			anchoAjustado = mx1 - mx0;
			altoAjustado = my1 - my0;
		}

		int y = height - ABAJO;
		int anchoCerrar = 80;
		int botones = (dimensiones.size() > 1 ? dimensiones.size() : 0) + 1;
		int ancho = Mth.clamp((width - 2 * MARGEN - anchoCerrar - 8) / botones - 4, 40, 74);
		int x = MARGEN;
		if (dimensiones.size() > 1) {
			for (int d : dimensiones) {
				Button b = Button.builder(Component.translatable("venatio.ruta.dim." + d), btn -> elegirDimension(d)).bounds(x, y, ancho, 20).build();
				b.active = d != dim;
				addRenderableWidget(b);
				x += ancho + 4;
			}
		}
		addRenderableWidget(Button.builder(Component.translatable("venatio.ruta.centrar"), b -> ajustar()).bounds(x, y, ancho, 20).build());
		Component cerrar = Component.translatable(anterior != null ? "venatio.ruta.volver" : "venatio.ruta.cerrar");
		addRenderableWidget(Button.builder(cerrar, b -> onClose()).bounds(width - MARGEN - anchoCerrar, y, anchoCerrar, 20).build());
	}

	// ---------------------------------------------------------------- vista

	public void elegirDimension(int d) {
		if (d == dim || !dimensiones.contains(d)) return;
		dim = d;
		ajustar();
		rebuildWidgets();
	}

	/** Encuadra todo lo que hay en la dimensión: recorridos, eventos y salidas; si no hay, el terreno. */
	private void ajustar() {
		double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		boolean hay = false;
		for (DatosRuta.Jugador j : ruta.jugadores()) {
			for (DatosRuta.Punto p : j.puntos()) {
				if (p.dim() != dim) continue;
				minX = Math.min(minX, p.x());
				maxX = Math.max(maxX, p.x());
				minZ = Math.min(minZ, p.z());
				maxZ = Math.max(maxZ, p.z());
				hay = true;
			}
		}
		for (DatosRuta.Spawn s : ruta.spawns()) {
			if (s.dim() != dim) continue;
			minX = Math.min(minX, s.x());
			maxX = Math.max(maxX, s.x());
			minZ = Math.min(minZ, s.z());
			maxZ = Math.max(maxZ, s.z());
			hay = true;
		}
		if (!hay) {
			DatosRuta.Terreno t = ruta.terreno(dim);
			if (t != null) {
				for (long c : t.chunks()) {
					int cx = (int) c, cz = (int) (c >>> 32);
					minX = Math.min(minX, cx * 16);
					maxX = Math.max(maxX, cx * 16 + 16);
					minZ = Math.min(minZ, cz * 16);
					maxZ = Math.max(maxZ, cz * 16 + 16);
					hay = true;
				}
			}
		}
		if (!hay) {
			centroX = centroZ = 0;
			escala = 1f;
			return;
		}
		centroX = (minX + maxX) / 2;
		centroZ = (minZ + maxZ) / 2;
		double ancho = Math.max(96, maxX - minX) * 1.2, alto = Math.max(96, maxZ - minZ) * 1.2;
		escala = Mth.clamp((float) Math.min((mx1 - mx0) / ancho, (my1 - my0) / alto), ESCALA_MIN, ESCALA_MAX);
	}

	private float sx(double x) {
		return (mx0 + mx1) / 2f + (float) ((x - centroX) * escala);
	}

	private float sy(double z) {
		return (my0 + my1) / 2f + (float) ((z - centroZ) * escala);
	}

	private boolean enMapa(double x, double y) {
		return x >= mx0 && x < mx1 && y >= my0 && y < my1;
	}

	/** Zoom alrededor de (x, y): el punto del mundo bajo el ratón se queda donde está. */
	public void zoom(float factor, double x, double y) {
		double wx = centroX + (x - (mx0 + mx1) / 2.0) / escala;
		double wz = centroZ + (y - (my0 + my1) / 2.0) / escala;
		escala = Mth.clamp(escala * factor, ESCALA_MIN, ESCALA_MAX);
		centroX = wx - (x - (mx0 + mx1) / 2.0) / escala;
		centroZ = wz - (y - (my0 + my1) / 2.0) / escala;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (enMapa(x, y) && scrollY != 0) {
			zoom((float) Math.pow(PASO_ZOOM, scrollY), x, y);
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
		if (enMapa(event.x(), event.y())) {
			arrastrando = true;
			return true;
		}
		// Clic en un jugador de la leyenda: centra la vista en el final de su recorrido.
		for (int[] f : filasLeyenda) {
			if (event.x() >= f[0] && event.x() < f[0] + f[2] && event.y() >= f[1] && event.y() < f[1] + f[3]) {
				centrarEn(f[4]);
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (arrastrando) {
			centroX -= dx / escala;
			centroZ -= dy / escala;
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (arrastrando && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			arrastrando = false;
			return true;
		}
		return super.mouseReleased(event);
	}

	private void centrarEn(int jugador) {
		List<DatosRuta.Punto> puntos = ruta.jugadores().get(jugador).puntos();
		if (puntos.isEmpty()) return;
		DatosRuta.Punto ultimo = puntos.getLast();
		if (ultimo.dim() != dim) elegirDimension(ultimo.dim());
		centroX = ultimo.x();
		centroZ = ultimo.z();
		escala = Math.max(escala, 1f);
	}

	// ---------------------------------------------------------------- dibujo

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		Arte.panel(g, 4, 4, width - 8, height - 8);
		super.extractRenderState(g, mouseX, mouseY, a);
		Arte.placa(g, font, title, width / 2, 6, Arte.ICONO_GUIA);

		Arte.fila(g, mx0 - 2, my0 - 2, mx1 - mx0 + 4, my1 - my0 + 4, false);
		g.fill(mx0, my0, mx1, my1, FONDO_MAPA);
		g.enableScissor(mx0, my0, mx1, my1);
		dibujarTerreno(g);
		dibujarLineas(g);
		dibujarIconos(g);
		g.disableScissor();
		textosDelMapa(g);
		if (conLeyenda) leyenda(g);

		hover = enMapa(mouseX, mouseY) ? textoDe(iconoEn(mouseX, mouseY)) : null;
		if (hover != null) g.setTooltipForNextFrame(font, hover, Optional.empty(), mouseX, mouseY);
	}

	private void dibujarTerreno(GuiGraphicsExtractor g) {
		TexturaTerreno t = RutaCliente.textura(ruta, dim);
		mosaicosVisibles = t == null ? 0 : t.dibujar(g, this::sx, this::sy, escala, mx0, my0, mx1, my1);
		terrenoVisible = mosaicosVisibles > 0;
	}

	/** Recorridos: una sombra oscura y encima el color de cada jugador. Se saltan los puntos a menos de 1.5 px del anterior. */
	private void dibujarLineas(GuiGraphicsExtractor g) {
		lineas.limpiar();
		float grosor = Mth.clamp(1.2f + escala * 0.5f, 1.6f, 3f);
		for (int pasada = 0; pasada < 2; pasada++) {
			for (int k = 0; k < ruta.jugadores().size(); k++) {
				int color = pasada == 0 ? 0xA0000000 : colores[k];
				float gr = pasada == 0 ? grosor + 1.6f : grosor;
				float px = 0, py = 0;
				boolean hay = false;
				for (DatosRuta.Punto p : ruta.jugadores().get(k).puntos()) {
					if (p.dim() != dim) {
						hay = false;
						continue;
					}
					float x = sx(p.x() + 0.5), y = sy(p.z() + 0.5);
					if (hay && !p.corte()) {
						float dx = x - px, dy = y - py;
						if (dx * dx + dy * dy < 2.25f) continue;
						if (cruza(px, py, x, y, gr)) {
							lineas.tramo(px, py, x, y, gr, color);
							lineas.punto(x, y, gr, color);
						}
					} else if (enMapa(x, y)) {
						lineas.punto(x, y, gr, color);
					}
					px = x;
					py = y;
					hay = true;
				}
			}
		}
		tramos = lineas.cantidad();
		if (tramos > 0) g.guiRenderState.addGuiElement(lineas.estado(new ScreenRectangle(mx0, my0, mx1 - mx0, my1 - my0)));
	}

	private boolean cruza(float ax, float ay, float bx, float by, float margen) {
		return Math.max(ax, bx) >= mx0 - margen && Math.min(ax, bx) <= mx1 + margen
			&& Math.max(ay, by) >= my0 - margen && Math.min(ay, by) <= my1 + margen;
	}

	/**
	 * Iconos de salidas y eventos, y la cabeza de cada jugador al final de su línea. Los que caerían encimados (varias
	 * casillas en el mismo sitio, o junto a una cabeza) se acomodan alrededor de su sitio para que se vean todos.
	 */
	private void dibujarIconos(GuiGraphicsExtractor g) {
		visibles.clear();
		ocupados.clear();
		// Primero se apartan los lugares de las cabezas: ellas sí van justo donde termina la línea.
		List<int[]> cabezas = new ArrayList<>();
		for (int k = 0; k < ruta.jugadores().size(); k++) {
			DatosRuta.Punto ultimo = null;
			for (DatosRuta.Punto p : ruta.jugadores().get(k).puntos()) if (p.dim() == dim) ultimo = p;
			if (ultimo == null) continue;
			int x = Math.round(sx(ultimo.x() + 0.5)), y = Math.round(sy(ultimo.z() + 0.5));
			if (!cerca(x, y, 8)) continue;
			cabezas.add(new int[]{x, y, k});
			ocupados.add(new int[]{x, y});
		}
		List<DatosRuta.Spawn> spawns = ruta.spawns();
		for (int i = 0; i < spawns.size(); i++) {
			DatosRuta.Spawn s = spawns.get(i);
			Identifier emblema = Arte.emblemaDe(s.equipo());
			if (s.dim() != dim || emblema == null) continue;
			int[] p = lugar(Math.round(sx(s.x() + 0.5)), Math.round(sy(s.z() + 0.5)));
			if (p == null) continue;
			g.blitSprite(RenderPipelines.GUI_TEXTURED, emblema, p[0] - 8, p[1] - 8, 16, 16);
			visibles.add(new Icono(p[0] - 8, p[1] - 8, 16, SPAWN, i));
		}
		// Las casillas primero: son lo más importante y se quedan más cerca de su sitio.
		List<DatosRuta.Evento> eventos = ruta.eventos();
		for (int pasada = 0; pasada < 2; pasada++) {
			for (int i = 0; i < eventos.size(); i++) {
				DatosRuta.Evento e = eventos.get(i);
				if (e.dim() != dim || (e.tipo() == DatosRuta.CASILLA) != (pasada == 0)) continue;
				int[] p = lugar(Math.round(sx(e.x() + 0.5)), Math.round(sy(e.z() + 0.5)));
				if (p == null) continue;
				int lado = icono(g, e.tipo(), e.icono(), e.jugador(), p[0], p[1]);
				visibles.add(new Icono(p[0] - lado / 2, p[1] - lado / 2, lado, e.tipo(), i));
			}
		}
		for (int[] c : cabezas) {
			int x = c[0], y = c[1], k = c[2];
			g.fill(x - 6, y - 6, x + 6, y + 6, 0xFF000000);
			g.fill(x - 5, y - 5, x + 5, y + 5, colores[k]);
			PlayerFaceExtractor.extractRenderState(g, piel(ruta.jugadores().get(k)), x - 4, y - 4, 8);
			visibles.add(new Icono(x - 6, y - 6, 12, CABEZA, k));
		}
	}

	/** Centros de los iconos ya puestos en este cuadro. */
	private final List<int[]> ocupados = new ArrayList<>();

	/** Dónde va un icono que tocaría (x, y): ahí, o recorrido a la derecha si ya hay otro. null si queda fuera del mapa. */
	private int[] lugar(int x, int y) {
		if (!cerca(x, y, 8)) return null;
		// Alrededor del sitio, en dos anillos como mucho: de lejos (muchos iconos juntos) no se van a otra parte del mapa.
		int[] p = {x, y};
		for (int[] d : ALREDEDOR) {
			int cx = x + d[0] * 14, cy = y + d[1] * 14;
			if (libre(cx, cy)) {
				p = new int[]{cx, cy};
				break;
			}
		}
		ocupados.add(p);
		return p;
	}

	private boolean libre(int x, int y) {
		for (int[] o : ocupados) if (Math.abs(o[0] - x) < 13 && Math.abs(o[1] - y) < 13) return false;
		return true;
	}

	/** Desplazamientos (en iconos) que se prueban, del sitio exacto hacia fuera; si no hay hueco, se encima. */
	private static final int[][] ALREDEDOR = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1},
		{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 1}, {-2, 1}, {2, -1}, {-2, -1}, {1, 2}, {-1, 2}, {1, -2}, {-1, -2},
		{2, 2}, {-2, 2}, {2, -2}, {-2, -2}};

	private boolean cerca(int x, int y, int margen) {
		return x >= mx0 - margen && x < mx1 + margen && y >= my0 - margen && y < my1 + margen;
	}

	/** Dibuja el icono de un evento centrado en (x, y); devuelve su lado. */
	private int icono(GuiGraphicsExtractor g, int tipo, int icono, int jugador, int x, int y) {
		switch (tipo) {
			case DatosRuta.CASILLA -> {
				int borde = jugador >= 0 && jugador < colores.length ? colores[jugador] : 0xFFC0C0C0;
				g.fill(x - 7, y - 7, x + 7, y + 7, borde);
				g.fill(x - 6, y - 6, x + 6, y + 6, FONDO_ICONO);
				item(g, icono >= 0 && icono < iconos.size() ? iconos.get(icono) : DIAMANTE, x, y, 0.7f);
				return 14;
			}
			case DatosRuta.MUERTE -> {
				g.fill(x - 6, y - 6, x + 6, y + 6, 0xFF8E2A22);
				g.fill(x - 5, y - 5, x + 5, y + 5, FONDO_ICONO);
				item(g, CALAVERA, x, y, 0.6f);
				return 12;
			}
			case DatosRuta.PORTAL_SALIDA, DatosRuta.PORTAL_LLEGADA -> {
				portal(g, x, y);
				return 12;
			}
			default -> { // ayuda
				g.fill(x - 6, y - 6, x + 6, y + 6, Arte.COBRE);
				g.fill(x - 5, y - 5, x + 5, y + 5, FONDO_ICONO);
				g.blitSprite(RenderPipelines.GUI_TEXTURED, Arte.ICONO_TIENDA, x - 5, y - 5, 10, 10);
				return 12;
			}
		}
	}

	private static void portal(GuiGraphicsExtractor g, int x, int y) {
		g.fill(x - 5, y - 6, x + 5, y + 6, OBSIDIANA);
		g.fill(x - 3, y - 4, x + 3, y + 4, PORTAL);
		g.fill(x - 1, y - 3, x + 1, y + 2, PORTAL_CLARO);
	}

	private static void item(GuiGraphicsExtractor g, ItemStack st, int x, int y, float escala) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(escala, escala);
		g.item(st, -8, -8);
		g.pose().popMatrix();
	}

	private PlayerSkin piel(DatosRuta.Jugador j) {
		Minecraft mc = Minecraft.getInstance();
		PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(j.uuid());
		return info != null ? info.getSkin() : DefaultPlayerSkin.get(j.uuid());
	}

	/** Nombre de la dimensión, ayuda de controles y aviso si no hay nada. */
	private void textosDelMapa(GuiGraphicsExtractor g) {
		Component nombre = Component.translatable("venatio.ruta.dim." + dim).withStyle(ChatFormatting.BOLD);
		g.fill(mx0, my0, mx0 + font.width(nombre) + 8, my0 + 13, 0xA0000000);
		g.text(font, nombre, mx0 + 4, my0 + 3, Arte.ORO, true);
		Component ayuda = Component.translatable("venatio.ruta.ayuda");
		int wa = font.width(ayuda);
		if (wa + 8 < mx1 - mx0) {
			g.fill(mx0, my1 - 13, mx0 + wa + 8, my1, 0xA0000000);
			g.text(font, ayuda, mx0 + 4, my1 - 10, Arte.GRIS, true);
		}
		if (tramos == 0 && visibles.isEmpty() && !terrenoVisible) {
			Component aviso = Component.translatable(dimensiones.isEmpty() ? "venatio.ruta.vacia" : "venatio.ruta.sin_datos");
			g.centeredText(font, aviso, (mx0 + mx1) / 2, (my0 + my1) / 2 - 4, Arte.GRIS);
		}
	}

	/** Columna derecha: jugadores (clic para ir al final de su recorrido) y qué es cada icono. */
	private void leyenda(GuiGraphicsExtractor g) {
		int x = leyendaX, y = my0, w = ANCHO_LEYENDA;
		Arte.fila(g, x - 2, y - 2, w + 4, my1 - my0 + 4, false);
		filasLeyenda.clear();
		g.text(font, Component.translatable("venatio.ruta.jugadores").withStyle(ChatFormatting.BOLD), x + 4, y + 4, Arte.ORO, true);
		y += 17;
		int altoIconos = 16 + 5 * 13;
		boolean caben = my1 - my0 > 17 + 13 * Math.min(4, ruta.jugadores().size()) + altoIconos + 6;
		int limite = caben ? my1 - altoIconos - 6 : my1 - 2;
		for (int k = 0; k < ruta.jugadores().size(); k++) {
			if (y + 12 > limite) break;
			DatosRuta.Jugador j = ruta.jugadores().get(k);
			g.fill(x + 4, y + 4, x + 12, y + 6, colores[k]);
			PlayerFaceExtractor.extractRenderState(g, piel(j), x + 15, y, 9);
			g.text(font, recortar(j.nombre(), w - 32), x + 28, y + 1, colores[k], true);
			filasLeyenda.add(new int[]{x, y, w, 12, k});
			y += 13;
		}
		if (!caben) return;
		y = my1 - altoIconos;
		g.text(font, Component.translatable("venatio.ruta.leyenda").withStyle(ChatFormatting.BOLD), x + 4, y + 2, Arte.ORO, true);
		y += 16;
		int cx = x + 11;
		icono(g, DatosRuta.CASILLA, iconos.isEmpty() ? -1 : 0, -1, cx, y + 4);
		g.text(font, Component.translatable("venatio.ruta.leyenda.casilla"), x + 22, y, Arte.CREMA, true);
		y += 13;
		icono(g, DatosRuta.MUERTE, -1, -1, cx, y + 4);
		g.text(font, Component.translatable("venatio.ruta.leyenda.muerte"), x + 22, y, Arte.CREMA, true);
		y += 13;
		portal(g, cx, y + 4);
		g.text(font, Component.translatable("venatio.ruta.leyenda.portal"), x + 22, y, Arte.CREMA, true);
		y += 13;
		icono(g, DatosRuta.AYUDA, -1, -1, cx, y + 4);
		g.text(font, Component.translatable("venatio.ruta.leyenda.ayuda"), x + 22, y, Arte.CREMA, true);
		y += 13;
		int equipo = ruta.spawns().isEmpty() ? 0 : ruta.spawns().getFirst().equipo();
		Identifier emblema = Arte.emblemaDe(equipo);
		if (emblema != null) g.blitSprite(RenderPipelines.GUI_TEXTURED, emblema, cx - 6, y - 2, 12, 12);
		g.text(font, Component.translatable("venatio.ruta.leyenda.spawn"), x + 22, y, Arte.CREMA, true);
	}

	private String recortar(String texto, int ancho) {
		if (font.width(texto) <= ancho) return texto;
		return font.plainSubstrByWidth(texto, Math.max(0, ancho - font.width("…"))) + "…";
	}

	// ---------------------------------------------------------------- ratón sobre los iconos

	private Icono iconoEn(double x, double y) {
		for (int i = visibles.size() - 1; i >= 0; i--) {
			Icono c = visibles.get(i);
			if (x >= c.x && x < c.x + c.lado && y >= c.y && y < c.y + c.lado) return c;
		}
		return null;
	}

	private List<Component> textoDe(Icono c) {
		if (c == null) return null;
		List<Component> lineas = new ArrayList<>();
		switch (c.tipo) {
			case SPAWN -> {
				ColorEquipo e = ColorEquipo.porOrdinal(ruta.spawns().get(c.indice).equipo());
				lineas.add(Component.translatable("venatio.ruta.spawn", e == null ? Component.literal("?") : e.nombre()));
			}
			case CABEZA -> {
				DatosRuta.Jugador j = ruta.jugadores().get(c.indice);
				lineas.add(Component.translatable("venatio.ruta.final", nombre(c.indice), EnPartida.reloj(j.puntos().getLast().segundo())));
			}
			default -> {
				DatosRuta.Evento e = ruta.eventos().get(c.indice);
				String reloj = EnPartida.reloj(e.segundo());
				MutableComponent quien = nombre(e.jugador());
				lineas.add(switch (e.tipo()) {
					case DatosRuta.CASILLA -> Component.translatable("venatio.ruta.evento.casilla", quien,
						e.icono() >= 0 && e.icono() < iconos.size() ? iconos.get(e.icono()).getHoverName() : Component.literal("?"), reloj);
					case DatosRuta.MUERTE -> Component.translatable("venatio.ruta.evento.muerte", quien, reloj);
					case DatosRuta.PORTAL_SALIDA -> Component.translatable("venatio.ruta.evento.portal_salida", quien,
						Component.translatable("venatio.ruta.dim." + Math.max(0, e.extra())), reloj);
					case DatosRuta.PORTAL_LLEGADA -> Component.translatable("venatio.ruta.evento.portal_llegada", quien,
						Component.translatable("venatio.ruta.dim." + Math.max(0, e.extra())), reloj);
					default -> Component.translatable("venatio.ruta.evento.ayuda", quien, reloj);
				});
			}
		}
		return lineas;
	}

	private MutableComponent nombre(int jugador) {
		if (jugador < 0 || jugador >= ruta.jugadores().size()) return Component.literal("?");
		return Component.literal(ruta.jugadores().get(jugador).nombre()).withColor(colores[jugador]);
	}

	// ---------------------------------------------------------------- consultas (pruebas)

	public int dimension() {
		return dim;
	}

	public List<Integer> dimensiones() {
		return List.copyOf(dimensiones);
	}

	public float escala() {
		return escala;
	}

	/** Cuadriláteros de línea (tramos y juntas, con su sombra) del último cuadro. */
	public int tramos() {
		return tramos;
	}

	public boolean terrenoVisible() {
		return terrenoVisible;
	}

	/** Iconos del último cuadro de un tipo de evento (DatosRuta.CASILLA…), SPAWN = 10 o CABEZA = 11. */
	public int iconos(int tipo) {
		int n = 0;
		for (Icono c : visibles) if (c.tipo == tipo) n++;
		return n;
	}

	/** Centro en pantalla del primer icono visible de ese tipo, o null. */
	public int[] centroDeIcono(int tipo) {
		for (Icono c : visibles) if (c.tipo == tipo) return new int[]{c.x + c.lado / 2, c.y + c.lado / 2};
		return null;
	}

	/** Centro del mapa en pantalla. */
	public int[] centroDelMapa() {
		return new int[]{(mx0 + mx1) / 2, (my0 + my1) / 2};
	}

	/** Texto del tooltip del último cuadro, o null. */
	public String textoHover() {
		return hover == null || hover.isEmpty() ? null : hover.getFirst().getString();
	}

	/** Píxeles con color del terreno de la dimensión vista (0 si no hay). */
	public int pixelesTerreno() {
		TexturaTerreno t = RutaCliente.textura(ruta, dim);
		return t == null ? 0 : t.pintados;
	}

	/** Mosaicos de terreno de la dimensión vista, y cuántos se dibujaron en el último cuadro. */
	public int[] mosaicos() {
		TexturaTerreno t = RutaCliente.textura(ruta, dim);
		return new int[]{t == null ? 0 : t.mosaicos(), mosaicosVisibles};
	}

	/** ¿Todos los puntos del recorrido de esta dimensión caen dentro del mapa con la vista actual? */
	public boolean recorridoCompletoVisible() {
		for (DatosRuta.Jugador j : ruta.jugadores()) {
			for (DatosRuta.Punto p : j.puntos()) {
				if (p.dim() == dim && !enMapa(sx(p.x() + 0.5), sy(p.z() + 0.5))) return false;
			}
		}
		return true;
	}

	/** Centro en pantalla de la fila de un jugador en la leyenda (clic ahí: va al final de su recorrido), o null. */
	public int[] filaDeLeyenda(String nombre) {
		for (int[] f : filasLeyenda) {
			if (ruta.jugadores().get(f[4]).nombre().equals(nombre)) return new int[]{f[0] + f[2] / 2, f[1] + f[3] / 2};
		}
		return null;
	}
}
