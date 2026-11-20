package dev.venatio.cliente;

import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Modo;
import dev.venatio.red.PaquetesStats;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pantalla final: línea de tiempo de la partida (qué casilla, quién, de qué equipo y a qué minuto) y marcador final.
 * Se abre sola al terminar; Esc la cierra y B la vuelve a abrir mientras dure el final.
 */
public final class PantallaFinal extends Screen {
	private static final int BLANCO = 0xFFFFFFFF;
	private static final int GRIS = 0xFFAAAAAA;
	private static final int GRIS_OSCURO = 0xFF777777;
	private static final int ORO = 0xFFF2B84B;
	private static final int FONDO = 0xC0101010;
	private static final int MARGEN = 10;
	private static final int FILA = 18;
	private static final int ARRIBA = 40;
	/** Alto reservado abajo: línea de ayuda y botones. */
	private static final int ABAJO = 42;

	private final PaquetesStats.FinalPartida f;
	private int scroll;
	// Zonas calculadas en init()
	private int listaX, listaY, listaAncho, listaAlto, marcadorX, marcadorAncho;

	public PantallaFinal(PaquetesStats.FinalPartida f) {
		super(Component.translatable("venatio.stats.final.titulo"));
		this.f = f;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		int util = width - 3 * MARGEN;
		listaAncho = Math.min(300, util * 3 / 5);
		listaX = MARGEN;
		listaY = ARRIBA + 14;
		listaAlto = Math.max(FILA, height - ABAJO - 4 - listaY);
		marcadorX = listaX + listaAncho + MARGEN;
		marcadorAncho = width - marcadorX - MARGEN;
		scroll = Mth.clamp(scroll, 0, maxScroll());

		int ancho = 110;
		int y = height - 26;
		addRenderableWidget(Button.builder(Component.translatable("venatio.stats.final.ver_carton"),
			b -> minecraft.gui.setScreen(new PantallaCarton())).bounds(width / 2 - ancho - 4, y, ancho, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("venatio.stats.final.cerrar"),
			b -> onClose()).bounds(width / 2 + 4, y, ancho, 20).build());
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
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (x >= listaX && x < listaX + listaAncho && y >= listaY && y < listaY + listaAlto && scrollY != 0) {
			scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, maxScroll());
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	private int filasVisibles() {
		return Math.max(1, listaAlto / FILA);
	}

	private int maxScroll() {
		return Math.max(0, f.hitos().size() - filasVisibles());
	}

	// ---------------------------------------------------------------- dibujo

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		cabecera(g);
		PaquetesStats.Hito hover = lineaDeTiempo(g, mouseX, mouseY);
		marcador(g);
		g.centeredText(font, Component.translatable("venatio.stats.final.ayuda"), width / 2, height - 38, GRIS_OSCURO);
		if (hover != null) tooltip(g, hover, mouseX, mouseY);
	}

	private void cabecera(GuiGraphicsExtractor g) {
		ColorEquipo ganador = ColorEquipo.porOrdinal(f.ganador());
		Component resultado = ganador != null ? Component.translatable("venatio.final.gana", ganador.nombre())
			: Component.translatable(f.empate() ? "venatio.final.empate" : "venatio.final.cancelada");
		g.pose().pushMatrix();
		g.pose().translate(width / 2f, 8);
		g.pose().scale(1.5f, 1.5f);
		g.centeredText(font, resultado, 0, 0, ORO);
		g.pose().popMatrix();

		MutableComponent sub = Component.translatable("venatio.stats.final.subtitulo", Modo.porId(f.modo()).nombre(), EstadoCliente.reloj(f.duracion()));
		if (!f.destacado().isEmpty()) {
			sub.append(" · ").append(Component.translatable("venatio.stats.final.destacado", f.destacado(), f.destacadoCasillas()));
		}
		g.centeredText(font, sub, width / 2, 24, GRIS);
	}

	/** Dibuja la lista y devuelve el hito bajo el ratón, si hay. */
	private PaquetesStats.Hito lineaDeTiempo(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		g.text(font, Component.translatable("venatio.stats.final.linea_tiempo").withStyle(ChatFormatting.BOLD), listaX, ARRIBA, BLANCO, true);
		g.fill(listaX - 2, listaY - 2, listaX + listaAncho + 2, listaY + listaAlto + 2, FONDO);
		List<PaquetesStats.Hito> hitos = f.hitos();
		if (hitos.isEmpty()) {
			g.centeredText(font, Component.translatable("venatio.stats.final.sin_casillas"), listaX + listaAncho / 2, listaY + 8, GRIS);
			return null;
		}

		boolean conBarra = hitos.size() > filasVisibles();
		int anchoFila = listaAncho - (conBarra ? 6 : 0);
		PaquetesStats.Hito hover = null;
		g.enableScissor(listaX, listaY, listaX + listaAncho, listaY + listaAlto);
		for (int i = scroll; i < hitos.size() && i < scroll + filasVisibles() + 1; i++) {
			PaquetesStats.Hito h = hitos.get(i);
			int y = listaY + (i - scroll) * FILA;
			int color = 0xFF000000 | HudCarton.color(h.equipo());
			boolean encima = mouseX >= listaX && mouseX < listaX + anchoFila && mouseY >= y && mouseY < y + FILA
				&& mouseY < listaY + listaAlto;
			if (encima) hover = h;
			if (encima) g.fill(listaX, y, listaX + anchoFila, y + FILA, 0x30FFFFFF);
			else if (i % 2 == 0) g.fill(listaX, y, listaX + anchoFila, y + FILA, 0x18FFFFFF);

			String minuto = EstadoCliente.reloj(h.segundo());
			g.text(font, minuto, listaX + 30 - font.width(minuto), y + 5, GRIS, false);
			g.fill(listaX + 34, y + 2, listaX + 36, y + FILA - 2, color);
			g.item(h.icono(), listaX + 39, y + 1);

			Component jugador = Component.literal(h.jugador()).withColor(HudCarton.color(h.equipo()));
			int anchoJugador = Math.min(font.width(jugador), anchoFila / 3);
			int xTexto = listaX + 58;
			int libre = listaX + anchoFila - 4 - anchoJugador - 6 - xTexto;
			g.text(font, recortar(h.icono().getHoverName().getString(), libre), xTexto, y + 5, BLANCO, false);
			g.text(font, recortar(h.jugador(), anchoJugador), listaX + anchoFila - 4 - anchoJugador, y + 5, 0xFF000000 | HudCarton.color(h.equipo()), true);
		}
		g.disableScissor();

		if (conBarra) {
			int x = listaX + listaAncho - 4;
			g.fill(x, listaY, x + 4, listaY + listaAlto, 0x40FFFFFF);
			int alto = Math.max(10, listaAlto * filasVisibles() / hitos.size());
			int y = listaY + (listaAlto - alto) * scroll / Math.max(1, maxScroll());
			g.fill(x, y, x + 4, y + alto, 0xC0FFFFFF);
		}
		return hover;
	}

	private void marcador(GuiGraphicsExtractor g) {
		int x = marcadorX, y = ARRIBA;
		g.text(font, Component.translatable("venatio.stats.final.marcador").withStyle(ChatFormatting.BOLD), x, y, BLANCO, true);
		y = listaY;
		g.fill(x - 2, y - 2, x + marcadorAncho + 2, listaY + listaAlto + 2, FONDO);
		for (PaquetesStats.EquipoFinal e : f.equipos()) {
			ColorEquipo c = ColorEquipo.porOrdinal(e.color());
			if (c == null) continue;
			if (y + font.lineHeight * 2 > listaY + listaAlto) break;
			g.fill(x, y + 1, x + 3, y + 2 * (font.lineHeight + 1) - 1, 0xFF000000 | c.rgb);
			g.text(font, Component.literal(e.puesto() + ".º ").append(c.nombre()), x + 6, y + 1, BLANCO, true);
			Component casillas = Component.translatable("venatio.stats.final.casillas", e.casillas(), f.total());
			g.text(font, casillas, x + marcadorAncho - font.width(casillas), y + 1, BLANCO, true);
			y += font.lineHeight + 1;
			Component puntos = f.cuenta() ? Component.translatable("venatio.stats.final.puntos", e.puntos())
				: Component.translatable("venatio.stats.final.no_cuenta");
			String miembros = String.join(", ", e.miembros());
			int anchoPuntos = font.width(puntos);
			g.text(font, recortar(miembros, marcadorAncho - anchoPuntos - 14), x + 6, y + 1, GRIS, false);
			g.text(font, puntos, x + marcadorAncho - anchoPuntos, y + 1, f.cuenta() && e.puntos() > 0 ? ORO : GRIS, false);
			y += font.lineHeight + 6;
		}
	}

	private void tooltip(GuiGraphicsExtractor g, PaquetesStats.Hito h, int mouseX, int mouseY) {
		List<Component> lineas = new ArrayList<>();
		lineas.add(h.icono().getHoverName());
		ColorEquipo c = ColorEquipo.porOrdinal(h.equipo());
		lineas.add(Component.translatable("venatio.stats.final.de_equipo", h.jugador(), c == null ? Component.literal("?") : c.nombre())
			.withColor(GRIS));
		lineas.add(Component.translatable("venatio.stats.final.minuto", EstadoCliente.reloj(h.segundo())).withColor(GRIS));
		g.setTooltipForNextFrame(font, lineas, Optional.empty(), mouseX, mouseY);
	}

	private String recortar(String texto, int ancho) {
		if (ancho <= 0) return "";
		if (font.width(texto) <= ancho) return texto;
		return font.plainSubstrByWidth(texto, Math.max(0, ancho - font.width("…"))) + "…";
	}
}
