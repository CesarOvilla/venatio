package dev.venatio.cliente;

import dev.venatio.juego.ColorEquipo;
import dev.venatio.red.Paquetes;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Tecla B: cartón grande con quién tiene cada casilla y a qué minuto, y los equipos. */
public final class PantallaCarton extends Screen {
	private static final int CELDA = 30;
	private static final int BLANCO = 0xFFFFFFFF;
	private static final int GRIS = 0xFFAAAAAA;

	public PantallaCarton() {
		super(Component.translatable("venatio.pantalla.titulo"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (VenatioCliente.teclaCarton().matches(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		Paquetes.Estado e = EstadoCliente.estado;
		if (e == null || e.casillas().isEmpty()) {
			g.centeredText(font, Component.translatable("venatio.pantalla.sin_partida"), width / 2, height / 2 - 4, BLANCO);
			if (e != null) dibujarEquipos(g, e, width / 2 - 60, height / 2 + 14);
			return;
		}

		int n = e.tamano();
		int lado = n * CELDA;
		int x0 = width / 2 - lado / 2 - 60, y0 = height / 2 - lado / 2;
		g.centeredText(font, title.copy().append(" · ").append(HudCarton.lineaReloj(e)), width / 2, y0 - 16, BLANCO);
		g.fill(x0 - 3, y0 - 3, x0 + lado + 3, y0 + lado + 3, 0xC0000000);

		int hover = -1;
		for (int i = 0; i < e.casillas().size(); i++) {
			Paquetes.CasillaRed c = e.casillas().get(i);
			int cx = x0 + (i % n) * CELDA, cy = y0 + (i / n) * CELDA;
			boolean mia = c.logros().stream().anyMatch(l -> l.equipo() == e.miEquipo());
			int fondo = mia ? 0xD0000000 | HudCarton.color(e.miEquipo())
				: c.logros().isEmpty() ? 0x40FFFFFF : 0x60000000 | HudCarton.color(c.logros().get(0).equipo());
			g.fill(cx + 1, cy + 1, cx + CELDA - 1, cy + CELDA - 1, fondo);
			g.item(c.icono(), cx + (CELDA - 16) / 2, cy + (CELDA - 16) / 2);
			if (mouseX >= cx && mouseX < cx + CELDA && mouseY >= cy && mouseY < cy + CELDA) {
				hover = i;
				g.nextStratum();
				g.outline(cx, cy, CELDA, CELDA, BLANCO);
			}
		}
		dibujarEquipos(g, e, x0 + lado + 16, y0);
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

	private void dibujarEquipos(GuiGraphicsExtractor g, Paquetes.Estado e, int x, int y) {
		g.text(font, Component.translatable("venatio.pantalla.equipos"), x, y, BLANCO, true);
		y += font.lineHeight + 4;
		for (Paquetes.EquipoRed q : e.equipos()) {
			ColorEquipo c = ColorEquipo.porOrdinal(q.color());
			if (c == null) continue;
			Component cabecera = c.nombre().copy();
			if (!e.casillas().isEmpty()) cabecera = cabecera.copy().append(Component.translatable("venatio.pantalla.puntos", q.puntos()).withColor(GRIS));
			g.text(font, cabecera, x, y, BLANCO, true);
			y += font.lineHeight + 1;
			for (String m : q.miembros()) {
				g.text(font, Component.literal("  " + m), x, y, GRIS, false);
				y += font.lineHeight + 1;
			}
			y += 3;
		}
	}
}
