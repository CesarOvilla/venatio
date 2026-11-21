package dev.venatio.cliente;

import com.mojang.blaze3d.platform.InputConstants;
import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Modo;
import dev.venatio.red.Paquetes;
import dev.venatio.red.PaquetesVariantes;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Draft (variante): el cartón completo y, a la derecha, el reloj, cuántos vetos le quedan a mi equipo y la lista de quién
 * vetó qué. Clic en una casilla la elige; otro clic (o el botón Vetar) la veta. Se abre sola al empezar el draft, la B
 * la vuelve a abrir y se cierra sola cuando termina.
 */
public final class PantallaDraft extends Screen {
	private static final int CELDA = 30;
	private static final int DENTRO = Arte.BORDE_CARTON + 1;
	private static final int ANCHO_COLUMNA = 168;
	private static final int ALTO_CABECERA = 24;
	private static final int ALTO_PIE = 30;
	private static final int FILA_VETO = 19;

	/** Casilla elegida para vetar (falta confirmar), o -1. */
	private int elegida = -1;
	private Button botonVetar;
	private long abierta = -1;

	public PantallaDraft() {
		super(Component.translatable("venatio.draft.titulo"));
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
		if (e == null || e.casillas().isEmpty()) return;
		botonVetar = addRenderableWidget(Button.builder(Component.translatable("venatio.draft.boton"), b -> confirmar())
			.bounds(xPanel(e), y0(e) + 36, 70, 20).build());
		actualizarBoton();
	}

	@Override
	public void tick() {
		if (!VariantesCliente.enDraft()) {
			onClose();
			return;
		}
		actualizarBoton();
	}

	private void actualizarBoton() {
		if (botonVetar == null) return;
		Paquetes.Estado e = EstadoCliente.estado;
		if (elegida >= 0 && e != null && !elegible(e, elegida)) elegida = -1;
		botonVetar.active = elegida >= 0 && e != null && quedan(e) > 0;
	}

	/** Vetos que le quedan a mi equipo (0 si no juego). */
	private static int quedan(Paquetes.Estado e) {
		PaquetesVariantes.Draft d = e.variantes().draft();
		if (d == null || e.miEquipo() < 0) return 0;
		return Math.max(0, d.vetos() - d.usados(e.miEquipo()));
	}

	private static boolean elegible(Paquetes.Estado e, int indice) {
		PaquetesVariantes.Draft d = e.variantes().draft();
		return d != null && d.enCurso() && indice >= 0 && indice < e.casillas().size() && d.vetoDe(indice) == null;
	}

	private void confirmar() {
		Paquetes.Estado e = EstadoCliente.estado;
		if (e == null || elegida < 0 || quedan(e) <= 0 || !elegible(e, elegida)) return;
		ClientPlayNetworking.send(new PaquetesVariantes.Vetar(elegida));
		elegida = -1;
		actualizarBoton();
	}

	// ---------------------------------------------------------------- geometría

	private int x0(Paquetes.Estado e) {
		return (width - (e.tamano() * CELDA + 16 + ANCHO_COLUMNA)) / 2;
	}

	private int y0(Paquetes.Estado e) {
		return (height - (e.tamano() * CELDA + ALTO_CABECERA + ALTO_PIE)) / 2 + ALTO_CABECERA;
	}

	private int xPanel(Paquetes.Estado e) {
		return x0(e) + e.tamano() * CELDA + 16;
	}

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

	/** Casilla elegida y todavía sin confirmar (pruebas). */
	public int elegida() {
		return elegida;
	}

	// ---------------------------------------------------------------- entrada

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		Paquetes.Estado e = EstadoCliente.estado;
		int i = event.button() == InputConstants.MOUSE_BUTTON_LEFT ? casillaEn(event.x(), event.y()) : -1;
		if (i < 0 || e == null || quedan(e) <= 0 || !elegible(e, i)) return false;
		if (i == elegida) confirmar();
		else {
			elegida = i;
			Sonidos.tocar(Sonidos.MARCA, 1.3f);
			actualizarBoton();
		}
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (VenatioCliente.teclaCarton().matches(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	// ---------------------------------------------------------------- dibujo

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		Paquetes.Estado e = EstadoCliente.estado;
		PaquetesVariantes.Draft d = e == null ? null : e.variantes().draft();
		if (e == null || e.casillas().isEmpty() || d == null) {
			super.extractRenderState(g, mouseX, mouseY, a);
			return;
		}
		int n = e.tamano(), lado = n * CELDA;
		int x0 = x0(e), y0 = y0(e), xp = xPanel(e);
		int mio = e.miEquipo();
		boolean exclusivo = Modo.porId(e.modo()).exclusivo();

		Arte.panel(g, x0 - DENTRO - 10, y0 - ALTO_CABECERA - 10, lado + 16 + ANCHO_COLUMNA + DENTRO + 20, lado + ALTO_CABECERA + ALTO_PIE + 20);
		Arte.placa(g, font, title, x0 + lado / 2, y0 - ALTO_CABECERA - 4, Arte.ICONO_VETO);
		Arte.icono(g, Arte.ICONO_RELOJ, xp, y0 - ALTO_CABECERA - 2);
		int segundos = VariantesCliente.segundosDraft();
		g.text(font, Component.translatable("venatio.draft.quedan", EstadoCliente.reloj(segundos)), xp + 19, y0 - ALTO_CABECERA + 2,
			segundos <= 10 ? Arte.ROJO : Arte.CREMA, true);

		// Cartón completo: las casillas entran en diagonal al abrir.
		Arte.marcoCarton(g, x0 - DENTRO, y0 - DENTRO, lado - 1 + 2 * DENTRO, lado - 1 + 2 * DENTRO);
		long entrada = Animaciones.ahora() - abierta;
		int hover = -1;
		for (int i = 0; i < e.casillas().size(); i++) {
			Paquetes.CasillaRed c = e.casillas().get(i);
			int cx = x0 + (i % n) * CELDA, cy = y0 + (i / n) * CELDA;
			Arte.fondoCasilla(g, c, i, cx, cy, CELDA - 1, mio, exclusivo);
			long retraso = (i % n + i / n) * 18L;
			Arte.itemCasilla(g, c, i, cx, cy, CELDA - 1, mio, entrada < retraso ? 0f : entrada > retraso + 160 ? 1f : Animaciones.rebote((entrada - retraso) / 160f));
			if (mouseX >= cx && mouseX < cx + CELDA && mouseY >= cy && mouseY < cy + CELDA) hover = i;
		}
		g.nextStratum();
		boolean puedo = quedan(e) > 0;
		for (int i = 0; i < e.casillas().size(); i++) {
			int cx = x0 + (i % n) * CELDA, cy = y0 + (i / n) * CELDA;
			Arte.encimaCasilla(g, e.casillas().get(i), i, cx, cy, CELDA - 1, mio, exclusivo);
			PaquetesVariantes.Veto v = d.vetoDe(i);
			if (v != null) g.outline(cx, cy, CELDA - 1, CELDA - 1, 0xFF000000 | Arte.color(v.equipo()));
			if (i == elegida) {
				g.outline(cx - 1, cy - 1, CELDA + 1, CELDA + 1, Arte.ROJO);
				g.outline(cx, cy, CELDA - 1, CELDA - 1, Arte.ROJO);
			} else if (i == hover && puedo && elegible(e, i)) {
				g.outline(cx - 1, cy - 1, CELDA + 1, CELDA + 1, Arte.ORO_CLARO);
			}
		}

		// Columna derecha: lo que puede hacer mi equipo, la elegida y la lista de vetos.
		Component estado = mio < 0 ? Component.translatable("venatio.draft.espectador")
			: puedo ? Component.translatable("venatio.draft.puedes", cuantas(quedan(e)))
			: Component.translatable("venatio.draft.ya_vetaste");
		g.textWithWordWrap(font, estado, xp, y0, ANCHO_COLUMNA, puedo ? Arte.AMARILLO : Arte.CREMA);
		if (elegida >= 0) {
			Component nombre = e.casillas().get(elegida).icono().getHoverName();
			g.text(font, Component.translatable("venatio.draft.elegida", ""), xp, y0 + 24, Arte.GRIS, false);
			g.text(font, recortar(nombre.getString(), ANCHO_COLUMNA - 76), xp + 76, y0 + 42, Arte.CREMA, true);
		}
		super.extractRenderState(g, mouseX, mouseY, a);
		PaquetesVariantes.Veto vetoHover = listaVetos(g, e, d, xp, y0 + 64, y0 + lado + 4, mouseX, mouseY);
		g.textWithWordWrap(font, Component.translatable("venatio.draft.ayuda"), x0 - DENTRO, y0 + lado + DENTRO + 4,
			lado + 16 + ANCHO_COLUMNA, Arte.GRIS);

		if (vetoHover != null) {
			g.setTooltipForNextFrame(font, lineasVeto(vetoHover), Optional.empty(), mouseX, mouseY);
		} else if (hover >= 0) {
			g.setTooltipForNextFrame(font, lineasCasilla(e, d, hover, puedo), Optional.empty(), mouseX, mouseY);
		}
	}

	/** Vetos, del más nuevo al más viejo: emblema, lo que pedía → lo que pide ahora y quién. Devuelve el que está bajo el ratón. */
	private PaquetesVariantes.Veto listaVetos(GuiGraphicsExtractor g, Paquetes.Estado e, PaquetesVariantes.Draft d, int x, int y, int yMax,
											  int mouseX, int mouseY) {
		g.text(font, Component.translatable("venatio.draft.vetos"), x, y, Arte.ORO, true);
		y += 12;
		if (d.vetados().isEmpty()) {
			g.text(font, Component.translatable("venatio.draft.sin_vetos"), x + 2, y + 4, Arte.GRIS, false);
			return null;
		}
		PaquetesVariantes.Veto hover = null;
		List<PaquetesVariantes.Veto> lista = new ArrayList<>(d.vetados());
		java.util.Collections.reverse(lista);
		for (PaquetesVariantes.Veto v : lista) {
			if (y + FILA_VETO > yMax) break;
			Arte.fila(g, x, y, ANCHO_COLUMNA, FILA_VETO - 1, false);
			Arte.emblema(g, v.equipo(), x + 1, y + 1);
			g.item(v.antes(), x + 19, y + 1);
			g.text(font, "→", x + 37, y + 5, Arte.GRIS, false);
			g.item(v.despues(), x + 45, y + 1);
			g.text(font, recortar(v.jugador(), ANCHO_COLUMNA - 66), x + 64, y + 5, 0xFF000000 | Arte.color(v.equipo()), true);
			if (mouseX >= x && mouseX < x + ANCHO_COLUMNA && mouseY >= y && mouseY < y + FILA_VETO - 1) hover = v;
			y += FILA_VETO;
		}
		return hover;
	}

	private static List<Component> lineasVeto(PaquetesVariantes.Veto v) {
		List<Component> lineas = new ArrayList<>();
		lineas.add(Component.translatable("venatio.draft.fila", v.jugador(), v.antes().getHoverName()));
		lineas.add(Component.translatable("venatio.draft.ahora", v.despues().getHoverName()).withColor(Arte.ORO));
		return lineas;
	}

	private List<Component> lineasCasilla(Paquetes.Estado e, PaquetesVariantes.Draft d, int i, boolean puedo) {
		List<Component> lineas = new ArrayList<>();
		lineas.add(e.casillas().get(i).icono().getHoverName());
		PaquetesVariantes.Veto v = d.vetoDe(i);
		if (v != null) {
			ColorEquipo c = ColorEquipo.porOrdinal(v.equipo());
			lineas.add(Component.translatable("venatio.draft.vetada_por", v.jugador(), c == null ? "?" : c.nombre()).withColor(Arte.ROJO));
			lineas.add(Component.translatable("venatio.draft.antes", v.antes().getHoverName()).withColor(Arte.GRIS));
			lineas.add(Component.translatable("venatio.draft.no_se_puede").withColor(Arte.GRIS));
		} else if (puedo) {
			lineas.add(Component.translatable(i == elegida ? "venatio.draft.clic_confirmar" : "venatio.draft.clic_elegir").withColor(Arte.AMARILLO));
		}
		return lineas;
	}

	private static Component cuantas(int n) {
		return n == 1 ? Component.translatable("venatio.draft.una") : Component.translatable("venatio.draft.varias", n);
	}

	private String recortar(String texto, int ancho) {
		if (ancho <= 0) return "";
		if (font.width(texto) <= ancho) return texto;
		return font.plainSubstrByWidth(texto, Math.max(0, ancho - font.width("…"))) + "…";
	}
}
