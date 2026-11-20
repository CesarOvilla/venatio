package dev.venatio.cliente;

import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Modo;
import dev.venatio.red.Paquetes;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Tecla B: cartón grande. Se puede ver el cartón de cualquier equipo (botones a la derecha o flechas ← →):
 * las casillas de ese equipo se pintan con su color; los puntitos son de los demás equipos.
 */
public final class PantallaCarton extends Screen {
	private static final int CELDA = 30;
	private static final int BLANCO = 0xFFFFFFFF;
	private static final int GRIS = 0xFFAAAAAA;
	private static final int ANCHO_BOTON = 110;

	/** Equipo cuyo cartón se está viendo; -1 = el mío. */
	private int vista = -1;
	private int equiposConBoton = -1;

	public PantallaCarton() {
		super(Component.translatable("venatio.pantalla.titulo"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		Paquetes.Estado e = EstadoCliente.estado;
		equiposConBoton = e == null ? 0 : e.equipos().size();
		if (e == null || e.casillas().isEmpty()) return;
		int x = xPanel(e), y = y0(e) + 14;
		for (Paquetes.EquipoRed q : e.equipos()) {
			ColorEquipo c = ColorEquipo.porOrdinal(q.color());
			if (c == null) continue;
			addRenderableWidget(Button.builder(
					c.nombre().copy().append(Component.translatable("venatio.pantalla.puntos", q.puntos())),
					b -> vista = c.ordinal())
				.bounds(x, y, ANCHO_BOTON, 18).build());
			y += 21;
		}
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

	private int x0(Paquetes.Estado e) {
		return width / 2 - (e.tamano() * CELDA) / 2 - 60;
	}

	private int y0(Paquetes.Estado e) {
		return height / 2 - (e.tamano() * CELDA) / 2;
	}

	private int xPanel(Paquetes.Estado e) {
		return x0(e) + e.tamano() * CELDA + 16;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		Paquetes.Estado e = EstadoCliente.estado;
		if (e == null || e.casillas().isEmpty()) {
			super.extractRenderState(g, mouseX, mouseY, a);
			g.centeredText(font, Component.translatable("venatio.pantalla.sin_partida"), width / 2, height / 2 - 4, BLANCO);
			return;
		}

		int n = e.tamano();
		int lado = n * CELDA;
		int x0 = x0(e), y0 = y0(e);
		int visto = equipoVisto(e);
		ColorEquipo colorVisto = ColorEquipo.porOrdinal(visto);
		boolean exclusivo = Modo.porId(e.modo()).exclusivo();

		Component titulo = colorVisto == null ? title
			: Component.translatable(visto == e.miEquipo() ? "venatio.pantalla.tu_carton" : "venatio.pantalla.carton_de", colorVisto.nombre());
		g.centeredText(font, titulo.copy().append(" · ").append(HudCarton.lineaReloj(e)), x0 + lado / 2, y0 - 16, BLANCO);
		g.fill(x0 - 3, y0 - 3, x0 + lado + 3, y0 + lado + 3, 0xC0000000);

		int hover = -1;
		for (int i = 0; i < e.casillas().size(); i++) {
			Paquetes.CasillaRed c = e.casillas().get(i);
			int cx = x0 + (i % n) * CELDA, cy = y0 + (i / n) * CELDA;
			boolean delVisto = c.logros().stream().anyMatch(l -> l.equipo() == visto);
			int fondo;
			if (delVisto) fondo = 0xD0000000 | HudCarton.color(visto);
			else if (exclusivo && !c.logros().isEmpty()) fondo = 0x50000000 | HudCarton.color(c.logros().get(0).equipo());
			else fondo = 0x40FFFFFF;
			g.fill(cx + 1, cy + 1, cx + CELDA - 1, cy + CELDA - 1, fondo);
			g.item(c.icono(), cx + (CELDA - 16) / 2, cy + (CELDA - 16) / 2);
			if (!exclusivo) {
				// Puntitos: los demás equipos que también la tienen.
				int px = cx + CELDA - 6;
				for (Paquetes.Logro l : c.logros()) {
					if (l.equipo() == visto) continue;
					g.nextStratum();
					g.fill(px, cy + CELDA - 6, px + 4, cy + CELDA - 2, 0xFF000000 | HudCarton.color(l.equipo()));
					px -= 5;
				}
			}
			if (mouseX >= cx && mouseX < cx + CELDA && mouseY >= cy && mouseY < cy + CELDA) {
				hover = i;
				g.nextStratum();
				g.outline(cx, cy, CELDA, CELDA, BLANCO);
			}
		}

		// Panel derecho: botones de equipos (widgets) e integrantes del equipo que se está viendo.
		int xp = xPanel(e);
		g.text(font, Component.translatable("venatio.pantalla.equipos"), xp, y0, BLANCO, true);
		super.extractRenderState(g, mouseX, mouseY, a);
		int yMiembros = y0 + 14 + e.equipos().size() * 21 + 6;
		for (Paquetes.EquipoRed q : e.equipos()) {
			if (q.color() != visto) continue;
			for (String m : q.miembros()) {
				g.text(font, Component.literal("· " + m), xp + 2, yMiembros, GRIS, false);
				yMiembros += font.lineHeight + 1;
			}
		}
		g.text(font, Component.translatable("venatio.pantalla.cambiar_vista"), xp, yMiembros + 6, GRIS, false);

		Component fase = e.fase() == Paquetes.Estado.JUGANDO ? null : HudCarton.lineaFase(e);
		if (fase != null) g.centeredText(font, fase, x0 + lado / 2, y0 + lado + 8, BLANCO);

		if (hover >= 0) {
			Paquetes.CasillaRed c = e.casillas().get(hover);
			List<Component> lineas = new ArrayList<>();
			lineas.add(c.icono().getHoverName());
			if (c.logros().isEmpty()) {
				lineas.add(Component.translatable("venatio.pantalla.pendiente").withColor(GRIS));
			} else {
				for (Paquetes.Logro l : c.logros()) {
					ColorEquipo eq = ColorEquipo.porOrdinal(l.equipo());
					if (eq != null) lineas.add(Component.translatable("venatio.pantalla.conseguida", eq.nombre(), EstadoCliente.reloj(l.segundo())));
				}
			}
			g.setTooltipForNextFrame(font, lineas, java.util.Optional.empty(), mouseX, mouseY);
		}
	}
}
