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
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;

/** Cartón en la esquina superior izquierda (o el item buscado en Rastreo), con reloj y marcador debajo. */
public final class HudCarton {
	static final int CELDA = 18;
	private static final int MARGEN = 4;
	/** Separación entre el marco y las casillas. */
	private static final int DENTRO = Arte.BORDE_CARTON + 1;
	private static final int ANCHO_RASTREO = 156;
	private static final int ALTO_RASTREO = 54;

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

		// Entrada: el cartón se desliza desde la izquierda y las casillas aparecen en diagonal.
		long entrada = Animaciones.tiempoEntrada();
		boolean rastreo = Modo.porId(e.modo()).esRastreo();
		int ancho = rastreo ? anchoRastreo(font, e) : lado(e);
		int dx = entrada >= 300 ? 0 : -Math.round((ancho + MARGEN + 2) * (1 - Animaciones.suaveFuera(entrada / 300f)));

		int alto;
		if (rastreo) {
			dibujarRastreo(g, font, e, MARGEN + dx, MARGEN, ancho);
			alto = ALTO_RASTREO;
		} else {
			dibujarCarton(g, e, MARGEN + dx, MARGEN, entrada);
			alto = ancho;
		}
		dibujarInfo(g, font, e, MARGEN + dx, MARGEN + alto + 2, ancho, rastreo);
	}

	private static int lado(Paquetes.Estado e) {
		return e.tamano() * CELDA - 1 + 2 * DENTRO;
	}

	private static void dibujarCarton(GuiGraphicsExtractor g, Paquetes.Estado e, int x0, int y0, long entrada) {
		int n = e.tamano();
		int lado = lado(e);
		boolean exclusivo = Modo.porId(e.modo()).exclusivo();
		int mio = e.miEquipo();
		Arte.marcoCarton(g, x0, y0, lado, lado);

		for (int i = 0; i < e.casillas().size(); i++) {
			Paquetes.CasillaRed c = e.casillas().get(i);
			int cx = x0 + DENTRO + (i % n) * CELDA, cy = y0 + DENTRO + (i / n) * CELDA;
			Arte.fondoCasilla(g, c, cx, cy, CELDA - 1, mio, exclusivo);
			// Cada casilla aparece un poco después que la de su izquierda y la de arriba.
			long retraso = 250 + (i % n + i / n) * 40L;
			float aparece = entrada >= Animaciones.ENTRADA_MS ? 1f : Animaciones.rebote((entrada - retraso) / 220f);
			Arte.itemCasilla(g, c, i, cx, cy, CELDA - 1, entrada < retraso ? 0f : aparece);
		}
		g.nextStratum();
		for (int i = 0; i < e.casillas().size(); i++) {
			int cx = x0 + DENTRO + (i % n) * CELDA, cy = y0 + DENTRO + (i / n) * CELDA;
			Arte.encimaCasilla(g, e.casillas().get(i), i, cx, cy, CELDA - 1, mio, exclusivo);
		}
	}

	private static int anchoRastreo(Font font, Paquetes.Estado e) {
		return Math.max(ANCHO_RASTREO, 50 + font.width(nombreRastreo(e.casillas().get(0), e.miEquipo())) + 10);
	}

	/** Lo que se busca; con cantidad, también lo que junta mi equipo ("16 × Tronco de roble  7/16"). */
	private static Component nombreRastreo(Paquetes.CasillaRed c, int mio) {
		if (c.cantidad() <= 1) return c.icono().getHoverName();
		return c.icono().getHoverName().copy()
			.append(Component.literal("  " + Math.min(c.progresoDe(mio), c.cantidad()) + "/" + c.cantidad()).withColor(Arte.ORO));
	}

	/** Rastreo: el item que busca mi equipo, en grande, con su nombre y la barra de progreso. */
	private static void dibujarRastreo(GuiGraphicsExtractor g, Font font, Paquetes.Estado e, int x0, int y0, int ancho) {
		Paquetes.CasillaRed c = e.casillas().get(0);
		int mio = e.miEquipo();
		Arte.marcoCarton(g, x0, y0, ancho, ALTO_RASTREO);
		Arte.sprite(g, Arte.CASILLA_VACIA, x0 + 8, y0 + 9, 36, 36);

		// El item nuevo entra creciendo.
		float nuevo = Animaciones.progreso(Animaciones.itemNuevo, Animaciones.ITEM_NUEVO_MS);
		float escala = 2f * (nuevo < 0 ? 1f : Animaciones.rebote(nuevo));
		g.pose().pushMatrix();
		g.pose().translate(x0 + 26, y0 + 27);
		g.pose().scale(escala, escala);
		g.item(c.icono(), -8, -8);
		g.pose().popMatrix();

		int tx = x0 + 50;
		Arte.icono(g, Arte.ICONO_RASTREO, tx, y0 + 6);
		g.text(font, Component.translatable("venatio.hud.busca"), tx + 19, y0 + 10, Arte.GRIS, true);
		g.text(font, nombreRastreo(c, mio), tx, y0 + 25, Arte.CREMA, true);

		// Barra: en carrera, lo que lleva mi equipo hacia la meta; con límite de tiempo, el tiempo que queda.
		boolean carrera = Modo.porId(e.modo()) == Modo.RASTREO_CARRERA;
		float real = -1;
		int colorBarra = Arte.ORO & 0xFFFFFF;
		Component cuenta = null;
		if (carrera && e.meta() > 0) {
			int puntos = puntosDe(e, mio);
			real = (float) puntos / e.meta();
			if (mio >= 0) colorBarra = Arte.color(mio);
			cuenta = Component.literal(puntos + " / " + e.meta());
		} else if (e.limite() > 0) {
			real = e.fase() == Paquetes.Estado.JUGANDO ? 1f - (float) EstadoCliente.segundos() / e.limite() : 1f;
		}
		if (cuenta != null) g.text(font, cuenta, x0 + ancho - 9 - font.width(cuenta), y0 + 10, Arte.ORO, true);
		if (real >= 0) {
			float destello = Animaciones.progreso(Animaciones.barraDestello, Animaciones.BARRA_DESTELLO_MS);
			Arte.barra(g, tx, y0 + 38, ancho - 50 - 8, 8, Animaciones.barra(real), colorBarra, destello);
		}

		// Item encontrado: el marco entero destella con el color del equipo.
		float destello = Animaciones.progreso(Animaciones.barraDestello, Animaciones.BARRA_DESTELLO_MS);
		if (destello >= 0 && mio >= 0) {
			g.nextStratum();
			float f = (1 - destello) * (1 - destello);
			g.fill(x0 + 2, y0 + 2, x0 + ancho - 2, y0 + ALTO_RASTREO - 2, ARGB.color(f * 0.45f, Arte.color(mio)));
		}
	}

	private static int puntosDe(Paquetes.Estado e, int equipo) {
		for (Paquetes.EquipoRed q : e.equipos()) if (q.color() == equipo) return q.puntos();
		return 0;
	}

	/** Debajo del cartón: reloj y modo, marcador con los emblemas y la línea de la fase. */
	private static void dibujarInfo(GuiGraphicsExtractor g, Font font, Paquetes.Estado e, int x0, int y0, int anchoMinimo, boolean rastreo) {
		Component reloj = lineaReloj(e);
		Component meta = rastreo && Modo.porId(e.modo()) == Modo.RASTREO_CARRERA ? Component.translatable("venatio.hud.meta", e.meta()) : null;
		// Jugando, la pista de la tecla B solo sale el primer medio minuto: después sobra y tapa.
		Component fase = e.fase() == Paquetes.Estado.JUGANDO && EstadoCliente.segundos() >= 30 ? null : lineaFase(e);
		int ancho = Math.max(anchoMinimo, 24 + font.width(reloj) + 6);
		if (fase != null) ancho = Math.max(ancho, font.width(fase) + 10);
		int altoMarcador = dibujarMarcador(null, font, e, 0, 0, ancho - 10);
		int alto = 4 + 17 + altoMarcador + (meta != null ? 10 : 0) + (fase != null ? 11 : 0) + 2;
		Arte.fila(g, x0, y0, ancho, alto, false);

		int y = y0 + 3;
		Arte.icono(g, Arte.ICONO_RELOJ, x0 + 4, y);
		g.text(font, reloj, x0 + 23, y + 4, Arte.CREMA, true);
		y += 17;
		y = dibujarMarcador(g, font, e, x0 + 5, y, ancho - 10);
		if (meta != null) {
			g.text(font, meta, x0 + 5, y, Arte.GRIS, true);
			y += 10;
		}
		if (fase != null) g.text(font, fase, x0 + 5, y + 1, Arte.CREMA, true);
	}

	/** Emblema y casillas de cada equipo, en las filas que hagan falta. Devuelve la y siguiente. Con g null solo mide. */
	static int dibujarMarcador(GuiGraphicsExtractor g, Font font, Paquetes.Estado e, int x0, int y0, int ancho) {
		int x = x0, y = y0;
		for (Paquetes.EquipoRed q : e.equipos()) {
			ColorEquipo c = ColorEquipo.porOrdinal(q.color());
			if (c == null) continue;
			String puntos = String.valueOf(q.puntos());
			int paso = 17 + font.width(puntos) + 6;
			if (x > x0 && x + paso - 6 > x0 + ancho) {
				x = x0;
				y += 17;
			}
			// El emblema late un momento cuando el equipo suma una casilla.
			Long sumo = Animaciones.marcador.get(q.color());
			float p = sumo == null ? -1 : Animaciones.progreso(sumo, Animaciones.MARCADOR_MS);
			if (g != null) {
				Arte.emblema(g, q.color(), x, y, p < 0 ? 1f : 1f + 0.35f * Mth.sin(Mth.PI * p));
				g.text(font, puntos, x + 17, y + 4, 0xFF000000 | c.rgb, true);
			}
			x += paso;
		}
		return y + 17;
	}

	private static void dibujarLobby(GuiGraphicsExtractor g, Font font, Paquetes.Estado e) {
		Component titulo = Component.translatable("venatio.hud.lobby");
		ColorEquipo mio = ColorEquipo.porOrdinal(e.miEquipo());
		Component equipo = mio != null ? Component.translatable("venatio.hud.tu_equipo", mio.nombre())
			: Component.translatable("venatio.hud.sin_equipo");
		Component modo = Component.translatable("venatio.hud.modo", Modo.porId(e.modo()).nombre());
		int sangria = mio != null ? 19 : 0;
		int ancho = Math.max(19 + font.width(titulo), Math.max(sangria + font.width(equipo), font.width(modo))) + 10;
		Arte.fila(g, MARGEN, MARGEN, ancho, 51, false);
		int x = MARGEN + 5, y = MARGEN + 3;
		Arte.icono(g, Arte.ICONO_BINGO, x, y);
		g.text(font, titulo, x + 19, y + 4, Arte.ORO, true);
		y += 18;
		if (mio != null) Arte.emblema(g, e.miEquipo(), x, y);
		g.text(font, equipo, x + sangria, y + 4, Arte.CREMA, true);
		y += 18;
		g.text(font, modo, x, y, Arte.GRIS, true);
	}

	static Component lineaReloj(Paquetes.Estado e) {
		int s = EstadoCliente.segundos();
		MutableComponent modo = Modo.porId(e.modo()).nombre();
		if (e.fase() == Paquetes.Estado.JUGANDO && e.limite() > 0) {
			return Component.translatable("venatio.hud.restante", EstadoCliente.reloj(e.limite() - s)).append(" · ").append(modo);
		}
		return Component.literal(EstadoCliente.reloj(e.fase() == Paquetes.Estado.CUENTA_ATRAS ? 0 : s) + " · ").append(modo);
	}

	static Component lineaFase(Paquetes.Estado e) {
		return switch (e.fase()) {
			case Paquetes.Estado.PREPARANDO -> Component.translatable("venatio.hud.preparando", e.progreso());
			case Paquetes.Estado.CUENTA_ATRAS -> Component.translatable("venatio.hud.cuenta", Math.max(1, Animaciones.segundoCuenta()));
			case Paquetes.Estado.FINAL -> {
				ColorEquipo g = ColorEquipo.porOrdinal(e.ganador());
				yield g != null ? Component.translatable("venatio.hud.ganador", g.nombre())
					: Component.translatable(e.ganador() == -2 ? "venatio.hud.empate" : "venatio.hud.cancelada");
			}
			default -> Component.translatable("venatio.hud.ver_carton").withColor(Arte.GRIS);
		};
	}

	static int color(int ordinal) {
		return Arte.color(ordinal);
	}
}
