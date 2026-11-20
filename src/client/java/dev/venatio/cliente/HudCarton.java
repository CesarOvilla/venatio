package dev.venatio.cliente;

import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Modo;
import dev.venatio.red.Paquetes;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

/** Cartón en la esquina superior izquierda, con reloj y marcador debajo. */
public final class HudCarton {
	static final int CELDA = 18;
	private static final int MARGEN = 4;
	private static final int BLANCO = 0xFFFFFFFF;
	private static final int GRIS = 0xFFAAAAAA;

	private HudCarton() {}

	static void dibujar(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		Paquetes.Estado e = EstadoCliente.estado;
		if (e == null || mc.player == null) return;
		Font font = mc.font;

		if (e.fase() == Paquetes.Estado.LOBBY || e.casillas().isEmpty()) {
			dibujarLobby(g, font, e);
			return;
		}

		int n = e.tamano();
		int lado = n * CELDA + 4;
		int x0 = MARGEN, y0 = MARGEN;
		g.fill(x0, y0, x0 + lado, y0 + lado, 0x90000000);
		boolean exclusivo = Modo.porId(e.modo()).exclusivo();
		long ahora = Util.getMillis();

		for (int i = 0; i < e.casillas().size(); i++) {
			Paquetes.CasillaRed c = e.casillas().get(i);
			int cx = x0 + 2 + (i % n) * CELDA, cy = y0 + 2 + (i / n) * CELDA;
			boolean mia = c.logros().stream().anyMatch(l -> l.equipo() == e.miEquipo());
			g.fill(cx, cy, cx + CELDA - 1, cy + CELDA - 1, mia ? 0xC0000000 | color(e.miEquipo()) : 0x30FFFFFF);
			g.item(c.icono(), cx + 1, cy + 1);
			if (!mia && !c.logros().isEmpty()) {
				if (exclusivo) {
					// Lockout: la tomó otro equipo; se tapa con su color.
					g.nextStratum();
					g.fill(cx, cy, cx + CELDA - 1, cy + CELDA - 1, 0xB0000000 | color(c.logros().get(0).equipo()));
				} else {
					// Puntitos de los otros equipos que ya la tienen.
					int px = cx + CELDA - 5;
					g.nextStratum();
					for (Paquetes.Logro l : c.logros()) {
						g.fill(px, cy + CELDA - 5, px + 3, cy + CELDA - 2, 0xFF000000 | color(l.equipo()));
						px -= 4;
					}
				}
			}
			Long inicio = EstadoCliente.animaciones.get(i);
			if (inicio != null) {
				long t = ahora - inicio;
				if (t > EstadoCliente.DURACION_ANIMACION) {
					EstadoCliente.animaciones.remove(i);
				} else {
					int alfa = (int) (255 * (1 - (double) t / EstadoCliente.DURACION_ANIMACION));
					int crece = (int) (t / 150) % 3;
					g.nextStratum();
					g.outline(cx - 1 - crece, cy - 1 - crece, CELDA + 1 + 2 * crece, CELDA + 1 + 2 * crece, (alfa << 24) | 0xFFFFFF);
				}
			}
		}

		List<Component> lineas = new ArrayList<>();
		lineas.add(lineaReloj(e));
		lineas.add(marcador(e));
		Component fase = lineaFase(e);
		if (fase != null) lineas.add(fase);
		int y = y0 + lado + 3;
		for (Component l : lineas) {
			g.text(font, l, x0, y, BLANCO, true);
			y += font.lineHeight + 1;
		}
	}

	private static void dibujarLobby(GuiGraphicsExtractor g, Font font, Paquetes.Estado e) {
		List<Component> lineas = new ArrayList<>();
		lineas.add(Component.translatable("venatio.hud.lobby"));
		ColorEquipo mio = ColorEquipo.porOrdinal(e.miEquipo());
		lineas.add(mio != null ? Component.translatable("venatio.hud.tu_equipo", mio.nombre())
			: Component.translatable("venatio.hud.sin_equipo"));
		lineas.add(Component.translatable("venatio.hud.modo", Modo.porId(e.modo()).nombre()));
		int ancho = 0;
		for (Component l : lineas) ancho = Math.max(ancho, font.width(l));
		int alto = lineas.size() * (font.lineHeight + 1) + 4;
		g.fill(MARGEN, MARGEN, MARGEN + ancho + 6, MARGEN + alto, 0x90000000);
		int y = MARGEN + 3;
		for (Component l : lineas) {
			g.text(font, l, MARGEN + 3, y, BLANCO, true);
			y += font.lineHeight + 1;
		}
	}

	static Component lineaReloj(Paquetes.Estado e) {
		int s = EstadoCliente.segundos();
		MutableComponent modo = Modo.porId(e.modo()).nombre();
		if (e.fase() == Paquetes.Estado.JUGANDO && e.limite() > 0) {
			return Component.translatable("venatio.hud.restante", EstadoCliente.reloj(e.limite() - s)).append(" · ").append(modo);
		}
		return Component.literal(EstadoCliente.reloj(e.fase() == Paquetes.Estado.CUENTA_ATRAS ? 0 : s) + " · ").append(modo);
	}

	static Component marcador(Paquetes.Estado e) {
		MutableComponent m = Component.empty();
		for (Paquetes.EquipoRed q : e.equipos()) {
			ColorEquipo c = ColorEquipo.porOrdinal(q.color());
			if (c == null) continue;
			m.append(Component.literal("■ " + q.puntos() + "  ").withStyle(c.formato));
		}
		return m;
	}

	static Component lineaFase(Paquetes.Estado e) {
		return switch (e.fase()) {
			case Paquetes.Estado.PREPARANDO -> Component.translatable("venatio.hud.preparando", e.progreso());
			case Paquetes.Estado.CUENTA_ATRAS -> Component.translatable("venatio.hud.cuenta", e.segundos());
			case Paquetes.Estado.FINAL -> {
				ColorEquipo g = ColorEquipo.porOrdinal(e.ganador());
				yield g != null ? Component.translatable("venatio.hud.ganador", g.nombre())
					: Component.translatable(e.ganador() == -2 ? "venatio.hud.empate" : "venatio.hud.cancelada");
			}
			default -> Component.translatable("venatio.hud.ver_carton").withColor(GRIS);
		};
	}

	static int color(int ordinal) {
		ColorEquipo c = ColorEquipo.porOrdinal(ordinal);
		return c == null ? 0x808080 : c.rgb;
	}
}
