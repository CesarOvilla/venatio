package dev.venatio.cliente;

import dev.venatio.cliente.fichas.PantallaFicha;
import dev.venatio.cliente.fichas.PantallaItemsPosibles;
import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Modo;
import dev.venatio.red.Paquetes;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tecla B: cartón grande. Se puede ver el cartón de cualquier equipo (botones a la derecha o flechas ← →):
 * las casillas de ese equipo se pintan con su color; los puntitos son de los demás equipos.
 */
public final class PantallaCarton extends Screen {
	private static final int CELDA = 30;
	/** Separación entre el marco del cartón y las casillas. */
	private static final int DENTRO = Arte.BORDE_CARTON + 1;
	private static final int ANCHO_BOTON = 110;
	/** Columna derecha: emblema y botón de cada equipo. */
	private static final int ANCHO_COLUMNA = 18 + ANCHO_BOTON;
	private static final int ALTO_CABECERA = 24;
	private static final int ALTO_PIE = 26;

	/** Equipo cuyo cartón se está viendo; -1 = el mío. */
	private int vista = EspectadorCliente.equipoVisto(-1);
	private int equiposConBoton = -1;
	/** Momento en que se abrió, para la entrada de las casillas; -1 hasta el primer init. */
	private long abierta = -1;

	public PantallaCarton() {
		super(Component.translatable("venatio.pantalla.titulo"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		if (abierta < 0) {
			abierta = Animaciones.ahora();
			Sonidos.tocar(Sonidos.CARTON_ABRIR);
		}
		Paquetes.Estado e = EstadoCliente.estado;
		equiposConBoton = e == null ? 0 : e.equipos().size();
		if (e == null || e.casillas().isEmpty()) {
			addRenderableWidget(Button.builder(Component.translatable("venatio.fichas.boton_items_posibles"),
				b -> minecraft.gui.setScreen(new PantallaItemsPosibles(this, itemsDelCarton()))).bounds(width / 2 - 70, height / 2 + 14, 140, 20).build());
			return;
		}
		int lado = e.tamano() * CELDA;
		addRenderableWidget(Button.builder(Component.translatable("venatio.fichas.boton_items_posibles"),
			b -> minecraft.gui.setScreen(new PantallaItemsPosibles(this, itemsDelCarton())))
			.bounds(x0(e) + lado / 2 - 70, y0(e) + lado + DENTRO + 3, 140, 20).build());
		int x = xPanel(e) + 18, y = y0(e) + 12;
		for (Paquetes.EquipoRed q : e.equipos()) {
			ColorEquipo c = ColorEquipo.porOrdinal(q.color());
			if (c == null) continue;
			// El nombre va en blanco (el color ya lo da el emblema): sobre el gris del botón, el azul o el morado no se leen.
			addRenderableWidget(Button.builder(
					Component.translatable("venatio.equipo." + c.id()).append(q.puntos() == 1
						? Component.translatable("venatio.pantalla.punto") : Component.translatable("venatio.pantalla.puntos", q.puntos())),
					b -> vista = c.ordinal())
				.bounds(x, y, ANCHO_BOTON, 18).build());
			y += 21;
		}
	}

	private static Set<Item> itemsDelCarton() {
		Set<Item> items = new HashSet<>();
		if (EstadoCliente.estado != null) {
			Paquetes.Estado e = EstadoCliente.estado;
			for (int i = 0; i < e.casillas().size(); i++) {
				Paquetes.CasillaRed c = e.casillas().get(i);
				if (!c.esReto() && !VariantesCliente.tapada(e, i, -1)) items.add(c.icono().getItem());
			}
		}
		return items;
	}

	/** Casilla bajo el ratón, o -1. Misma geometría que extractRenderState. */
	private int casillaEn(double mx, double my) {
		Paquetes.Estado e = EstadoCliente.estado;
		if (e == null || e.casillas().isEmpty()) return -1;
		int n = e.tamano(), lado = n * CELDA, x0 = x0(e), y0 = y0(e);
		if (mx < x0 || my < y0 || mx >= x0 + lado || my >= y0 + lado) return -1;
		int i = (int) ((my - y0) / CELDA) * n + (int) ((mx - x0) / CELDA);
		return i < e.casillas().size() ? i : -1;
	}

	/** Centro (en coordenadas de interfaz) de una casilla, para las pruebas. */
	public int[] centroDe(int indice) {
		Paquetes.Estado e = EstadoCliente.estado;
		int n = e.tamano();
		return new int[]{x0(e) + (indice % n) * CELDA + CELDA / 2, y0(e) + (indice / n) * CELDA + CELDA / 2};
	}

	/** Clic en una casilla: abre su ficha (cómo se consigue). */
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		int i = event.button() == 0 ? casillaEn(event.x(), event.y()) : -1;
		if (i < 0 || VariantesCliente.tapada(EstadoCliente.estado, i, equipoVisto(EstadoCliente.estado))) return false;
		Paquetes.CasillaRed c = EstadoCliente.estado.casillas().get(i);
		minecraft.gui.setScreen(c.esReto() ? PantallaFicha.deReto(this, c.reto()) : new PantallaFicha(this, c.icono().getItem()));
		return true;
	}

	@Override
	public void tick() {
		Paquetes.Estado e = EstadoCliente.estado;
		// Los puntos de los botones cambian durante la partida: se reconstruyen cada segundo o si cambian los equipos.
		if (e != null && (e.equipos().size() != equiposConBoton || minecraft.player != null && minecraft.player.tickCount % 20 == 0)) {
			rebuildWidgets();
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (VenatioCliente.teclaCarton().matches(event)) {
			onClose();
			return true;
		}
		if (event.isLeft() || event.isRight()) {
			cambiarVista(event.isRight() ? 1 : -1);
			return true;
		}
		return super.keyPressed(event);
	}

	private void cambiarVista(int paso) {
		Paquetes.Estado e = EstadoCliente.estado;
		if (e == null || e.equipos().isEmpty()) return;
		List<Integer> orden = new ArrayList<>();
		for (Paquetes.EquipoRed q : e.equipos()) orden.add(q.color());
		int actual = orden.indexOf(equipoVisto(e));
		vista = orden.get(Math.floorMod(actual + paso, orden.size()));
	}

	private int equipoVisto(Paquetes.Estado e) {
		if (vista >= 0) return vista;
		if (e.miEquipo() >= 0) return e.miEquipo();
		return e.equipos().isEmpty() ? -1 : e.equipos().get(0).color();
	}

	/** Esquina de la primera casilla. El conjunto (cabecera, cartón, columna de equipos y pie) va centrado. */
	private int x0(Paquetes.Estado e) {
		return (width - (e.tamano() * CELDA + 16 + extra(e) + ANCHO_COLUMNA)) / 2;
	}

	/** Con relevo, entre el cartón y los equipos va la columna con la cara del dueño de cada fila. */
	private static int extra(Paquetes.Estado e) {
		return VariantesCliente.relevo(e) ? VariantesCliente.COLUMNA_PANTALLA : 0;
	}

	private int y0(Paquetes.Estado e) {
		return (height - (e.tamano() * CELDA + ALTO_CABECERA + ALTO_PIE)) / 2 + ALTO_CABECERA;
	}

	private int xPanel(Paquetes.Estado e) {
		return x0(e) + e.tamano() * CELDA + 16 + extra(e);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		Paquetes.Estado e = EstadoCliente.estado;
		if (e == null || e.casillas().isEmpty()) {
			Component aviso = Component.translatable("venatio.pantalla.sin_partida");
			int medio = Math.max(90, font.width(aviso) / 2 + 16);
			Arte.panel(g, width / 2 - medio, height / 2 - 34, medio * 2, 78);
			Arte.placa(g, font, title, width / 2, height / 2 - 44, Arte.ICONO_BINGO);
			g.centeredText(font, aviso, width / 2, height / 2 - 6, Arte.CREMA);
			super.extractRenderState(g, mouseX, mouseY, a);
			return;
		}

		int n = e.tamano();
		int lado = n * CELDA;
		int x0 = x0(e), y0 = y0(e);
		int visto = equipoVisto(e);
		ColorEquipo colorVisto = ColorEquipo.porOrdinal(visto);
		boolean exclusivo = Modo.porId(e.modo()).exclusivo();
		int xp = xPanel(e);

		// Marco de la pantalla, placa con el título y, a su derecha, reloj y modo.
		Arte.panel(g, x0 - DENTRO - 10, y0 - ALTO_CABECERA - 10, lado + 16 + extra(e) + ANCHO_COLUMNA + DENTRO + 20, lado + ALTO_CABECERA + ALTO_PIE + 20);
		Component titulo = colorVisto == null ? title
			: Component.translatable(visto == e.miEquipo() ? "venatio.pantalla.tu_carton" : "venatio.pantalla.carton_de", colorVisto.nombre());
		Arte.placa(g, font, titulo, x0 + lado / 2, y0 - ALTO_CABECERA - 4, Arte.ICONO_BINGO);
		Arte.icono(g, Arte.ICONO_RELOJ, xp, y0 - ALTO_CABECERA - 2);
		g.text(font, HudCarton.lineaReloj(e), xp + 19, y0 - ALTO_CABECERA + 2, Arte.CREMA, true);

		// Cartón: las casillas entran en diagonal al abrir.
		Arte.marcoCarton(g, x0 - DENTRO, y0 - DENTRO, lado - 1 + 2 * DENTRO, lado - 1 + 2 * DENTRO);
		long entrada = Animaciones.ahora() - abierta;
		int hover = -1;
		for (int i = 0; i < e.casillas().size(); i++) {
			Paquetes.CasillaRed c = e.casillas().get(i);
			int cx = x0 + (i % n) * CELDA, cy = y0 + (i / n) * CELDA;
			Arte.fondoCasilla(g, c, i, cx, cy, CELDA - 1, visto, exclusivo);
			long retraso = (i % n + i / n) * 18L;
			Arte.itemCasilla(g, c, i, cx, cy, CELDA - 1, visto, entrada < retraso ? 0f : entrada > retraso + 160 ? 1f : Animaciones.rebote((entrada - retraso) / 160f));
			if (mouseX >= cx && mouseX < cx + CELDA && mouseY >= cy && mouseY < cy + CELDA) hover = i;
		}
		int filaHover = VariantesCliente.relevo(e)
			? VariantesCliente.dibujarCaras(g, e, visto, x0 + lado + DENTRO + 3, y0, CELDA, 16, mouseX, mouseY) : -1;
		g.nextStratum();
		for (int i = 0; i < e.casillas().size(); i++) {
			int cx = x0 + (i % n) * CELDA, cy = y0 + (i / n) * CELDA;
			Arte.encimaCasilla(g, e.casillas().get(i), i, cx, cy, CELDA - 1, visto, exclusivo);
			if (i == hover) g.outline(cx - 1, cy - 1, CELDA + 1, CELDA + 1, Arte.ORO_CLARO);
		}

		// Columna derecha: emblema y botón de cada equipo, y los integrantes del que se está viendo.
		g.text(font, Component.translatable("venatio.pantalla.equipos"), xp, y0, Arte.ORO, true);
		int ye = y0 + 12;
		for (Paquetes.EquipoRed q : e.equipos()) {
			if (ColorEquipo.porOrdinal(q.color()) == null) continue;
			Arte.emblema(g, q.color(), xp, ye + 1);
			if (q.color() == visto) g.outline(xp + 17, ye - 1, ANCHO_BOTON + 2, 20, Arte.ORO);
			ye += 21;
		}
		super.extractRenderState(g, mouseX, mouseY, a);
		int yMiembros = ye + 4;
		for (Paquetes.EquipoRed q : e.equipos()) {
			if (q.color() != visto) continue;
			for (String m : q.miembros()) {
				String filas = VariantesCliente.relevo(e) ? VariantesCliente.filasDe(e, visto, m) : "";
				g.text(font, filas.isEmpty() ? Component.literal("· " + m) : Component.translatable("venatio.relevo.filas_de", m, filas),
					xp + 2, yMiembros, Arte.CREMA, false);
				yMiembros += font.lineHeight + 1;
			}
		}
		g.textWithWordWrap(font, Component.translatable("venatio.pantalla.cambiar_vista"), xp, yMiembros + 5, ANCHO_COLUMNA, Arte.GRIS);

		Component fase = e.fase() == Paquetes.Estado.JUGANDO ? null : HudCarton.lineaFase(e);
		if (fase != null) g.textWithWordWrap(font, fase, xp, y0 + lado + DENTRO + 4, ANCHO_COLUMNA, Arte.AMARILLO);

		if (filaHover >= 0) g.setTooltipForNextFrame(font, VariantesCliente.textoFila(e, visto, filaHover), mouseX, mouseY);
		if (hover >= 0 && VariantesCliente.tapada(e, hover, visto)) {
			g.setTooltipForNextFrame(font, tooltipTapada(e, e.casillas().get(hover), hover, visto, colorVisto), java.util.Optional.empty(), mouseX, mouseY);
		} else if (hover >= 0) {
			Paquetes.CasillaRed c = e.casillas().get(hover);
			List<Component> lineas = new ArrayList<>();
			lineas.add(c.icono().getHoverName());
			lineasVariantes(e, hover, visto, lineas);
			if (c.esReto()) lineas.add(Component.translatable("venatio.reto.etiqueta").withColor(Arte.COBRE));
			if (c.cantidad() > 1 && colorVisto != null && c.logros().stream().noneMatch(l -> l.equipo() == visto)) {
				Component quien = visto == e.miEquipo() ? Component.translatable("venatio.carton.progreso.tuyo", c.progresoDe(visto), c.cantidad())
					: Component.translatable("venatio.carton.progreso.de", colorVisto.nombre(), c.progresoDe(visto), c.cantidad());
				lineas.add(quien.copy().withColor(Arte.ORO));
			}
			if (c.logros().isEmpty()) {
				lineas.add(Component.translatable("venatio.pantalla.pendiente").withColor(Arte.GRIS));
			} else {
				for (Paquetes.Logro l : c.logros()) {
					ColorEquipo eq = ColorEquipo.porOrdinal(l.equipo());
					if (eq != null) lineas.add(Component.translatable("venatio.pantalla.conseguida", eq.nombre(), EstadoCliente.reloj(l.segundo())));
				}
			}
			lineas.add(Component.translatable(c.esReto() ? "venatio.reto.clic_para_ver" : "venatio.fichas.clic_para_ver").withColor(Arte.GRIS));
			g.setTooltipForNextFrame(font, lineas, java.util.Optional.empty(), mouseX, mouseY);
		}
	}

	/** Casilla secreta y dueño de la fila (relevo), debajo del nombre de la casilla. */
	private static void lineasVariantes(Paquetes.Estado e, int indice, int visto, List<Component> lineas) {
		if (indice == VariantesCliente.secreta(visto)) {
			lineas.add(visto == e.miEquipo() && e.fase() != Paquetes.Estado.FINAL
				? Component.translatable("venatio.secreta.tuya").withColor(Arte.ORO)
				: Component.translatable("venatio.secreta.de", ColorEquipo.porOrdinal(visto).nombre()).withColor(Arte.ORO));
		}
		String dueno = VariantesCliente.duenoDeFila(e, visto, indice / e.tamano());
		if (dueno != null) lineas.add(VariantesCliente.textoFila(e, visto, indice / e.tamano()).copy().withColor(Arte.COBRE));
	}

	/** Tooltip de una casilla tapada: no dice qué pide, solo cómo destaparla y quién la tiene. */
	private static List<Component> tooltipTapada(Paquetes.Estado e, Paquetes.CasillaRed c, int indice, int visto, ColorEquipo colorVisto) {
		List<Component> lineas = new ArrayList<>();
		lineas.add(Component.translatable("venatio.oculto.tapada"));
		boolean paraMi = VariantesCliente.tapada(e, indice, e.miEquipo());
		lineas.add((paraMi || colorVisto == null ? Component.translatable("venatio.oculto.como")
			: Component.translatable("venatio.oculto.de_otro", colorVisto.nombre())).withColor(Arte.GRIS));
		lineasVariantes(e, indice, visto, lineas);
		for (Paquetes.Logro l : c.logros()) {
			ColorEquipo eq = ColorEquipo.porOrdinal(l.equipo());
			if (eq != null) lineas.add(Component.translatable("venatio.pantalla.conseguida", eq.nombre(), EstadoCliente.reloj(l.segundo())));
		}
		return lineas;
	}
}
