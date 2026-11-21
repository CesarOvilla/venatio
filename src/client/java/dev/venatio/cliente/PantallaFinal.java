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
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pantalla final: línea de tiempo de la partida (qué casilla, quién, de qué equipo y a qué minuto) y marcador final.
 * Se abre sola al terminar; Esc la cierra y B la vuelve a abrir mientras dure el final.
 */
public final class PantallaFinal extends Screen {
	private static final int BLANCO = Arte.CREMA;
	private static final int GRIS = Arte.GRIS;
	private static final int GRIS_OSCURO = Arte.GRIS_OSCURO;
	private static final int ORO = Arte.ORO;
	/** Borde del marco más un respiro. */
	private static final int MARGEN = 14;
	private static final int FILA = 18;
	/** Arriba va el banner del resultado; aquí empieza el marco, con el subtítulo, los títulos de las columnas y las listas. */
	private static final int ARRIBA = 48;
	/** Alto reservado abajo: línea de ayuda y botones. */
	private static final int ABAJO = 46;
	/** Las filas aparecen una tras otra al abrir. */
	private static final long ESPERA_FILAS = 150, PASO_FILA = 40, DURA_FILA = 220;

	private final PaquetesStats.FinalPartida f;
	private int scroll;
	/** Momento en que se abrió, para la entrada escalonada; -1 hasta el primer init. */
	private long abierta = -1;
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
		if (abierta < 0) abierta = Animaciones.ahora();
		int util = width - 2 * MARGEN - 10;
		listaAncho = Math.min(300, util * 3 / 5);
		listaX = MARGEN;
		listaY = ARRIBA + 35;
		listaAlto = Math.max(FILA, height - ABAJO - 2 - listaY);
		marcadorX = listaX + listaAncho + 10;
		marcadorAncho = width - marcadorX - MARGEN;
		scroll = Mth.clamp(scroll, 0, maxScroll());

		int ancho = 110;
		int y = height - 32;
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
		Arte.panel(g, 4, ARRIBA, width - 8, height - ARRIBA - 4);
		super.extractRenderState(g, mouseX, mouseY, a);
		cabecera(g);
		PaquetesStats.Hito hover = lineaDeTiempo(g, mouseX, mouseY);
		marcador(g);
		g.centeredText(font, Component.translatable("venatio.stats.final.ayuda"), width / 2, height - 44, GRIS_OSCURO);
		if (hover != null) tooltip(g, hover, mouseX, mouseY);

		// Confeti por encima de todo, mientras dure el del final de esta partida.
		Animaciones.Banner b = Animaciones.banner;
		if (b != null && b.final_()) {
			g.nextStratum();
			Arte.confeti(g, width, height, b.inicio(), b.equipo(), HudEfectos.piezas(b, EstadoCliente.estado));
		}
	}

	/** Banner con el resultado (entra desde arriba al terminar la partida) y, debajo, modo, duración y jugador destacado. */
	private void cabecera(GuiGraphicsExtractor g) {
		int miEquipo = EstadoCliente.estado == null ? -1 : EstadoCliente.estado.miEquipo();
		int equipo = f.ganador() >= 0 ? f.ganador() : -1;
		Component[] textos = Arte.textosBanner(true, equipo, miEquipo, f.empate() ? -2 : -3);
		Animaciones.Banner b = Animaciones.banner;
		// Si se abre más tarde con la B, el banner ya está en su sitio.
		long t = b != null && b.final_() ? Animaciones.ahora() - b.inicio() : 10_000;
		Arte.banner(g, font, width, t, 0, equipo, textos, 2, true);

		MutableComponent sub = Component.translatable("venatio.stats.final.subtitulo", Modo.porId(f.modo()).nombre(), EstadoCliente.reloj(f.duracion()));
		if (!f.destacado().isEmpty()) {
			sub.append(" · ").append(Component.translatable("venatio.stats.final.destacado", f.destacado(), f.destacadoCasillas()));
		}
		g.centeredText(font, sub, width / 2, ARRIBA + 8, GRIS);
	}

	/** Cuánto ha entrado la fila k (0 = todavía no, 1 = en su sitio). */
	private float entrada(int k, long espera, long paso) {
		long t = Animaciones.ahora() - abierta - espera - Math.min(k, 12) * paso;
		return Mth.clamp((float) t / DURA_FILA, 0f, 1f);
	}

	/** Dibuja la lista y devuelve el hito bajo el ratón, si hay. */
	private PaquetesStats.Hito lineaDeTiempo(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Arte.icono(g, Arte.ICONO_RELOJ, listaX, listaY - 19);
		g.text(font, Component.translatable("venatio.stats.final.linea_tiempo").withStyle(ChatFormatting.BOLD), listaX + 19, listaY - 14, ORO, true);
		Arte.fila(g, listaX - 2, listaY - 2, listaAncho + 4, listaAlto + 4, false);
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
			// Escalonado: cada fila entra desde la izquierda un poco después que la anterior.
			float entra = entrada(i, ESPERA_FILAS, PASO_FILA);
			if (entra <= 0.03f) continue;
			PaquetesStats.Hito h = hitos.get(i);
			int dx = -Math.round(16 * (1 - Animaciones.suaveFuera(entra)));
			int x = listaX + dx;
			int y = listaY + (i - scroll) * FILA;
			int rgb = HudCarton.color(h.equipo());
			boolean encima = mouseX >= listaX && mouseX < listaX + anchoFila && mouseY >= y && mouseY < y + FILA
				&& mouseY < listaY + listaAlto;
			if (encima) hover = h;
			if (encima) g.fill(listaX, y, listaX + anchoFila, y + FILA, 0x30FFFFFF);
			else if (i % 2 == 0) g.fill(listaX, y, listaX + anchoFila, y + FILA, ARGB.color(entra * 0.07f, 0xFFFFFF));

			String minuto = EstadoCliente.reloj(h.segundo());
			g.text(font, minuto, x + 30 - font.width(minuto), y + 5, ARGB.color(entra, GRIS & 0xFFFFFF), false);
			g.fill(x + 34, y + 2, x + 36, y + FILA - 2, ARGB.color(entra, rgb));
			if (entra > 0.4f) {
				float escala = Animaciones.rebote((entra - 0.4f) / 0.6f);
				g.pose().pushMatrix();
				g.pose().translate(x + 47, y + 9);
				g.pose().scale(escala, escala);
				g.item(h.icono(), -8, -8);
				g.pose().popMatrix();
			}

			int anchoJugador = Math.min(font.width(h.jugador()), anchoFila / 3);
			int xTexto = x + 58;
			int libre = listaX + anchoFila - 4 - anchoJugador - 6 - (listaX + 58);
			g.text(font, recortar(h.icono().getHoverName().getString(), libre), xTexto, y + 5, ARGB.color(entra, BLANCO & 0xFFFFFF), false);
			g.text(font, recortar(h.jugador(), anchoJugador), x + anchoFila - 4 - anchoJugador, y + 5, ARGB.color(entra, rgb), true);
		}
		g.disableScissor();

		if (conBarra) {
			int x = listaX + listaAncho - 4;
			g.fill(x, listaY, x + 4, listaY + listaAlto, 0x40FFFFFF);
			int alto = Math.max(10, listaAlto * filasVisibles() / hitos.size());
			int y = listaY + (listaAlto - alto) * scroll / Math.max(1, maxScroll());
			g.fill(x, y, x + 4, y + alto, Arte.COBRE);
		}
		return hover;
	}

	private void marcador(GuiGraphicsExtractor g) {
		int x = marcadorX;
		Arte.icono(g, Arte.ICONO_TROFEO, x, listaY - 19);
		g.text(font, Component.translatable("venatio.stats.final.marcador").withStyle(ChatFormatting.BOLD), x + 19, listaY - 14, ORO, true);
		int y = listaY;
		Arte.fila(g, x - 2, y - 2, marcadorAncho + 4, listaAlto + 4, false);
		int k = 0;
		for (PaquetesStats.EquipoFinal e : f.equipos()) {
			ColorEquipo c = ColorEquipo.porOrdinal(e.color());
			if (c == null) continue;
			if (y + 22 > listaY + listaAlto) break;
			// Los equipos también entran de uno en uno, del primero al último.
			float entra = entrada(k++, ESPERA_FILAS + 100, 110);
			if (entra > 0.03f) {
				int dx = Math.round(16 * (1 - Animaciones.suaveFuera(entra)));
				int xe = x + 2 + dx;
				Arte.sprite(g, Arte.emblemaDe(e.color()), xe, y + 2, 16, 16, ARGB.color(entra, 0xFFFFFF));
				g.text(font, Component.literal(e.puesto() + ".º ").append(c.nombre()), xe + 20, y + 1, ARGB.color(entra, BLANCO & 0xFFFFFF), true);
				Component casillas = Component.translatable("venatio.stats.final.casillas", e.casillas(), f.total());
				g.text(font, casillas, x + dx + marcadorAncho - 2 - font.width(casillas), y + 1, ARGB.color(entra, BLANCO & 0xFFFFFF), true);
				Component puntos = f.cuenta() ? Component.translatable("venatio.stats.final.puntos", e.puntos())
					: Component.translatable("venatio.stats.final.no_cuenta");
				String miembros = String.join(", ", e.miembros());
				int anchoPuntos = font.width(puntos);
				g.text(font, recortar(miembros, marcadorAncho - anchoPuntos - 30), xe + 20, y + 11, ARGB.color(entra, GRIS & 0xFFFFFF), false);
				g.text(font, puntos, x + dx + marcadorAncho - 2 - anchoPuntos, y + 11,
					ARGB.color(entra, (f.cuenta() && e.puntos() > 0 ? ORO : GRIS) & 0xFFFFFF), false);
			}
			y += 25;
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
