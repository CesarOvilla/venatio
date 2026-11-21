package dev.venatio.cliente;

import dev.venatio.Venatio;
import dev.venatio.juego.ColorEquipo;
import dev.venatio.red.Paquetes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;

import java.util.Random;

/**
 * Arte propio de Venatio: sprites (los genera tools/arte/texturas.py) y cómo se dibujan.
 * Identidad de pabellón de caza: madera oscura, cobre y dorado, con los colores de equipo vivos.
 */
public final class Arte {
	// Colores de texto sobre los paneles oscuros
	public static final int CREMA = 0xFFF1E6CC;
	public static final int GRIS = 0xFFB9AA94;
	public static final int GRIS_OSCURO = 0xFF8A7B68;
	public static final int ORO = 0xFFF2B84B;
	public static final int ORO_CLARO = 0xFFFFE69A;
	public static final int COBRE = 0xFFE08F55;
	public static final int MADERA = 0xFF6B472A;
	public static final int VERDE = 0xFF8CE08C;
	public static final int ROJO = 0xFFFF7A6B;
	public static final int AMARILLO = 0xFFFFE066;

	public static final Identifier MARCO_CARTON = sprite("carton/marco");
	public static final Identifier CASILLA_VACIA = sprite("carton/casilla_vacia");
	public static final Identifier CASILLA_MIA = sprite("carton/casilla_mia");
	public static final Identifier CASILLA_RIVAL = sprite("carton/casilla_rival");
	public static final Identifier CASILLA_BLOQUEADA = sprite("carton/casilla_bloqueada");
	public static final Identifier CANDADO = sprite("carton/candado");
	public static final Identifier PALOMITA = sprite("carton/palomita");
	/** Variantes: tapa del cartón oculto, su signo de interrogación, estrella de la casilla secreta y sello de veto. */
	public static final Identifier CASILLA_TAPADA = sprite("carton/casilla_tapada");
	public static final Identifier INTERROGACION = sprite("carton/interrogacion");
	public static final Identifier SECRETA = sprite("carton/secreta");
	public static final Identifier VETO = sprite("carton/veto");
	public static final Identifier MARCO_PANTALLA = sprite("pantalla/marco");
	public static final Identifier PLACA = sprite("pantalla/placa");
	public static final Identifier FILA = sprite("pantalla/fila");
	public static final Identifier FILA_ACTIVA = sprite("pantalla/fila_activa");
	public static final Identifier BARRA_FONDO = sprite("rastreo/barra_fondo");
	public static final Identifier BARRA_RELLENO = sprite("rastreo/barra_relleno");
	public static final Identifier BANNER_CUERPO = sprite("banner/cuerpo");
	public static final Identifier BANNER_BORDE = sprite("banner/borde");

	public static final Identifier ICONO_BINGO = sprite("icono/bingo");
	public static final Identifier ICONO_TIENDA = sprite("icono/tienda");
	public static final Identifier ICONO_GUIA = sprite("icono/guia");
	/** En grises: se tiñe con el color del equipo. */
	public static final Identifier ICONO_MARCA = sprite("icono/marca");
	public static final Identifier ICONO_MINIMAPA = sprite("icono/minimapa");
	public static final Identifier ICONO_RELOJ = sprite("icono/reloj");
	public static final Identifier ICONO_TROFEO = sprite("icono/trofeo");
	public static final Identifier ICONO_RASTREO = sprite("icono/rastreo");
	public static final Identifier ICONO_VETO = sprite("icono/veto");

	private static final Identifier[] EMBLEMAS = new Identifier[ColorEquipo.values().length];

	static {
		for (ColorEquipo c : ColorEquipo.values()) EMBLEMAS[c.ordinal()] = sprite("equipo/" + c.id());
	}

	/** Grosor del marco del cartón: lo que hay que dejar entre el borde y las casillas. */
	public static final int BORDE_CARTON = 5;
	/** Grosor del marco de las pantallas. */
	public static final int BORDE_PANTALLA = 6;

	private Arte() {}

	private static Identifier sprite(String ruta) {
		return Identifier.fromNamespaceAndPath(Venatio.MOD_ID, ruta);
	}

	/** Emblema de un equipo, o null si el ordinal no es de un equipo. */
	public static Identifier emblemaDe(int equipo) {
		return equipo >= 0 && equipo < EMBLEMAS.length ? EMBLEMAS[equipo] : null;
	}

	/** Todos los sprites, para comprobar en las pruebas que están en el atlas. */
	public static java.util.List<Identifier> todos() {
		java.util.List<Identifier> lista = new java.util.ArrayList<>(java.util.List.of(MARCO_CARTON, CASILLA_VACIA, CASILLA_MIA, CASILLA_RIVAL,
			CASILLA_BLOQUEADA, CANDADO, PALOMITA, MARCO_PANTALLA, PLACA, FILA, FILA_ACTIVA, BARRA_FONDO, BARRA_RELLENO, BANNER_CUERPO,
			BANNER_BORDE, ICONO_BINGO, ICONO_TIENDA, ICONO_GUIA, ICONO_MARCA, ICONO_MINIMAPA, ICONO_RELOJ, ICONO_TROFEO, ICONO_RASTREO,
			CASILLA_TAPADA, INTERROGACION, SECRETA, VETO, ICONO_VETO));
		lista.addAll(java.util.List.of(EMBLEMAS));
		return lista;
	}

	static int color(int equipo) {
		ColorEquipo c = ColorEquipo.porOrdinal(equipo);
		return c == null ? 0x808080 : c.rgb;
	}

	// ---------------------------------------------------------------- piezas

	static void sprite(GuiGraphicsExtractor g, Identifier id, int x, int y, int ancho, int alto) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, id, x, y, ancho, alto);
	}

	static void sprite(GuiGraphicsExtractor g, Identifier id, int x, int y, int ancho, int alto, int argb) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, id, x, y, ancho, alto, argb);
	}

	/** Icono de 16×16 a su tamaño real. */
	public static void icono(GuiGraphicsExtractor g, Identifier id, int x, int y) {
		sprite(g, id, x, y, 16, 16);
	}

	public static void icono(GuiGraphicsExtractor g, Identifier id, int x, int y, int argb) {
		sprite(g, id, x, y, 16, 16, argb);
	}

	/** Emblema del equipo (16×16). Devuelve false si el equipo no existe y no dibujó nada. */
	public static boolean emblema(GuiGraphicsExtractor g, int equipo, int x, int y) {
		Identifier id = emblemaDe(equipo);
		if (id == null) return false;
		sprite(g, id, x, y, 16, 16);
		return true;
	}

	/** Emblema con escala alrededor de su centro (para el pulso del marcador). */
	static void emblema(GuiGraphicsExtractor g, int equipo, int x, int y, float escala) {
		Identifier id = emblemaDe(equipo);
		if (id == null) return;
		if (escala == 1f) {
			sprite(g, id, x, y, 16, 16);
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(x + 8, y + 8);
		g.pose().scale(escala, escala);
		sprite(g, id, -8, -8, 16, 16);
		g.pose().popMatrix();
	}

	/** Marco de madera y cobre con fondo de fieltro: el cartón. */
	public static void marcoCarton(GuiGraphicsExtractor g, int x, int y, int ancho, int alto) {
		sprite(g, MARCO_CARTON, x, y, ancho, alto);
	}

	/** Marco de las pantallas propias. */
	public static void panel(GuiGraphicsExtractor g, int x, int y, int ancho, int alto) {
		sprite(g, MARCO_PANTALLA, x, y, ancho, alto);
	}

	/** Hueco oscuro para una fila de lista o un bloque de texto. */
	public static void fila(GuiGraphicsExtractor g, int x, int y, int ancho, int alto, boolean activa) {
		sprite(g, activa ? FILA_ACTIVA : FILA, x, y, ancho, alto);
	}

	/** Placa de latón con el título centrado en cx. Con icono, va a la izquierda del texto. Mide {@link #ALTO_PLACA} de alto. */
	public static void placa(GuiGraphicsExtractor g, Font font, Component titulo, int cx, int y, Identifier icono) {
		int anchoTexto = font.width(titulo);
		int contenido = anchoTexto + (icono != null ? 19 : 0);
		int ancho = contenido + 20;
		int x = cx - ancho / 2;
		sprite(g, PLACA, x, y, ancho, ALTO_PLACA);
		int tx = x + 10;
		if (icono != null) {
			icono(g, icono, tx, y + 2);
			tx += 19;
		}
		g.text(font, titulo, tx, y + 6, ORO, true);
	}

	public static final int ALTO_PLACA = 20;

	// ---------------------------------------------------------------- casillas del cartón

	/** Fondo de una casilla según su estado, visto desde el equipo dado. Tapada (cartón oculto), lleva la tapa. */
	static void fondoCasilla(GuiGraphicsExtractor g, Paquetes.CasillaRed c, int indice, int x, int y, int tam, int visto, boolean exclusivo) {
		boolean tapada = VariantesCliente.tapada(indice, visto);
		if (tapada && !tiene(c, visto) && !(exclusivo && !c.logros().isEmpty())) {
			sprite(g, CASILLA_TAPADA, x, y, tam, tam);
		} else if (tiene(c, visto)) {
			sprite(g, CASILLA_MIA, x, y, tam, tam, 0xFF000000 | color(visto));
		} else if (c.logros().isEmpty()) {
			sprite(g, CASILLA_VACIA, x, y, tam, tam);
		} else if (exclusivo) {
			sprite(g, CASILLA_BLOQUEADA, x, y, tam, tam, 0xFF000000 | color(c.logros().get(0).equipo()));
		} else {
			sprite(g, CASILLA_RIVAL, x, y, tam, tam);
		}
	}

	/**
	 * El item centrado en la casilla; escala alrededor del centro (1 = tamaño normal, 0 = no se dibuja). Tapada para el
	 * equipo visto, un signo de interrogación; al destaparse, la tapa se cierra de lado y el item entra creciendo.
	 */
	static void itemCasilla(GuiGraphicsExtractor g, Paquetes.CasillaRed c, int indice, int x, int y, int tam, int visto, float escala) {
		if (VariantesCliente.tapada(indice, visto)) {
			interrogacion(g, x, y, tam, escala, 1f);
			return;
		}
		float d = VariantesCliente.destape(indice);
		if (d >= 0 && d < 0.5f) {
			// Primera mitad: la tapa (con su interrogación) se encoge de lado hasta desaparecer.
			float ancho = 1 - d * 2;
			g.pose().pushMatrix();
			g.pose().translate(x + tam / 2f, y);
			g.pose().scale(ancho, 1f);
			sprite(g, CASILLA_TAPADA, -tam / 2, 0, tam, tam);
			g.pose().popMatrix();
			interrogacion(g, x, y, tam, escala, ancho);
			return;
		}
		if (d >= 0.5f) escala *= rebote((d - 0.5f) * 2);
		float v = VariantesCliente.veto(indice);
		if (v >= 0) escala *= rebote(v);
		Animaciones.Casilla a = Animaciones.casilla(indice);
		if (a != null && a.propia()) {
			float t = (float) (Animaciones.ahora() - a.inicio()) / Animaciones.CASILLA_MS;
			escala *= 1f + 0.45f * Mth.sin(Mth.PI * Math.min(1f, t / 0.6f));
		}
		if (escala <= 0.02f) return;
		if (Math.abs(escala - 1f) < 0.01f) {
			g.item(c.icono(), x + (tam - 16) / 2, y + (tam - 16) / 2);
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(x + tam / 2f, y + tam / 2f);
		g.pose().scale(escala, escala);
		g.item(c.icono(), -8, -8);
		g.pose().popMatrix();
	}

	/** Signo de interrogación de una casilla tapada (2× en la pantalla grande). ancho: escala horizontal (la tapa al girar). */
	private static void interrogacion(GuiGraphicsExtractor g, int x, int y, int tam, float escala, float ancho) {
		if (escala <= 0.02f || ancho <= 0.02f) return;
		float z = (tam >= 24 ? 2f : 1f) * escala;
		g.pose().pushMatrix();
		g.pose().translate(x + tam / 2f, y + tam / 2f);
		g.pose().scale(z * ancho, z);
		sprite(g, INTERROGACION, -4, -5, 7, 10);
		g.pose().popMatrix();
	}

	private static float rebote(float t) {
		return Animaciones.rebote(t);
	}

	/**
	 * Lo que va encima del item: candado de Lockout, puntos de los rivales, palomita y las animaciones de casilla
	 * conseguida; con las variantes, la estrella de la casilla secreta, el sello de vetada y el destello al destapar.
	 * Hay que llamarlo después de {@code g.nextStratum()} para que tape al item.
	 */
	static void encimaCasilla(GuiGraphicsExtractor g, Paquetes.CasillaRed c, int indice, int x, int y, int tam, int visto, boolean exclusivo) {
		boolean delVisto = tiene(c, visto);
		boolean grande = tam >= 24;
		boolean tapada = VariantesCliente.tapada(indice, visto);
		encimaVariantes(g, indice, x, y, tam, visto, grande);
		if (!delVisto && !c.logros().isEmpty()) {
			if (exclusivo) {
				// Lockout: la tomó otro equipo. Se oscurece y lleva candado.
				g.fill(x + 1, y + 1, x + tam - 1, y + tam - 1, 0x70000000);
				sprite(g, CANDADO, x + tam - 9, y + tam - 11, 8, 10);
			}
		}
		if (!exclusivo) {
			int p = grande ? 4 : 3;
			int px = x + tam - 2 - p, py = y + tam - 2 - p;
			for (Paquetes.Logro l : c.logros()) {
				if (l.equipo() == visto) continue;
				g.fill(px - 1, py - 1, px + p + 1, py + p + 1, 0xFF140D08);
				g.fill(px, py, px + p, py + p, 0xFF000000 | color(l.equipo()));
				px -= p + 2;
			}
		}
		if (delVisto && grande) sprite(g, PALOMITA, x + 2, y + tam - 10, 9, 8);
		if (c.esReto() && !tapada) esquinaReto(g, x, y, grande);
		if (c.cantidad() > 1 && !tapada) cantidadCasilla(g, c, x, y, tam, visto, delVisto || exclusivo && !c.logros().isEmpty());

		Animaciones.Casilla a = Animaciones.casilla(indice);
		if (a == null) return;
		float t = (float) (Animaciones.ahora() - a.inicio()) / a.duracion();
		int rgb = color(a.equipo());
		if (a.propia()) {
			// Destello que pasa de blanco al color del equipo y se apaga, y un anillo que se abre.
			float f = (1 - t) * (1 - t);
			g.fill(x, y, x + tam, y + tam, ARGB.color(f * 0.8f, ARGB.srgbLerp(f, rgb, 0xFFFFFF)));
			int d = Math.round(Animaciones.suaveFuera(t) * (grande ? 8 : 5));
			g.outline(x - d, y - d, tam + 2 * d, tam + 2 * d, ARGB.color(1 - t, rgb));
			g.outline(x - d - 1, y - d - 1, tam + 2 * d + 2, tam + 2 * d + 2, ARGB.color((1 - t) * 0.5f, rgb));
		} else {
			// Aviso discreto: el borde parpadea una vez con el color del rival.
			float f = 1 - t;
			g.outline(x, y, tam, tam, ARGB.color(f * 0.95f, rgb));
			g.outline(x + 1, y + 1, tam - 2, tam - 2, ARGB.color(f * 0.45f, rgb));
		}
	}

	/** Casilla secreta del equipo visto (marco dorado que late y estrella), vetada en el draft y destello al destaparse. */
	private static void encimaVariantes(GuiGraphicsExtractor g, int indice, int x, int y, int tam, int visto, boolean grande) {
		if (indice == VariantesCliente.secreta(visto)) {
			float pulso = 0.65f + 0.35f * Mth.sin(Animaciones.ahora() % 1600 / 1600f * Mth.TWO_PI);
			g.outline(x, y, tam, tam, ARGB.color(pulso, ORO_CLARO & 0xFFFFFF));
			g.outline(x + 1, y + 1, tam - 2, tam - 2, ARGB.color(pulso * 0.6f, ORO & 0xFFFFFF));
			sprite(g, SECRETA, x + tam - 8, y + 1, 7, 7);
		}
		if (VariantesCliente.vetadaEnDraft(indice)) sprite(g, VETO, x + 1, y + 1, 9, 9);
		float v = VariantesCliente.veto(indice);
		if (v >= 0) {
			float f = (1 - v) * (1 - v);
			g.fill(x, y, x + tam, y + tam, ARGB.color(f * 0.7f, 0xE04A3C));
		}
		float d = VariantesCliente.destape(indice);
		if (d >= 0.5f) {
			float f = 1 - (d - 0.5f) * 2;
			int r = Math.round((1 - f) * (grande ? 5 : 3));
			g.outline(x - r, y - r, tam + 2 * r, tam + 2 * r, ARGB.color(f, ORO_CLARO & 0xFFFFFF));
			g.fill(x + 1, y + 1, x + tam - 1, y + tam - 1, ARGB.color(f * 0.35f, 0xFFFFFF));
		}
	}

	/** Casilla de logro: esquina de cobre y oro arriba a la izquierda, para no confundirla con un item. */
	private static void esquinaReto(GuiGraphicsExtractor g, int x, int y, boolean grande) {
		int k = grande ? 9 : 7;
		for (int r = 0; r < k; r++) g.fill(x, y + r, x + k - r, y + r + 1, 0xFF140D08);
		for (int r = 0; r < k - 3; r++) g.fill(x + 1, y + 1 + r, x + k - 2 - r, y + 2 + r, r == 0 ? ORO_CLARO : ORO);
	}

	/**
	 * Casilla con cantidad: las unidades que pide, abajo a la derecha, y lo que junta el equipo que se está viendo
	 * (medidor vertical en el HUD, barra abajo en la pantalla grande). Sin medidor si ya es suya o está tomada.
	 */
	private static void cantidadCasilla(GuiGraphicsExtractor g, Paquetes.CasillaRed c, int x, int y, int tam, int visto, boolean cerrada) {
		Font font = Minecraft.getInstance().font;
		boolean grande = tam >= 24;
		if (!cerrada) {
			float fraccion = Math.min(1f, (float) c.progresoDe(visto) / c.cantidad());
			int rgb = 0xFF000000 | (visto >= 0 ? color(visto) : ORO);
			if (grande) {
				int bx = x + 3, by = y + tam - 4, ancho = tam - 6;
				g.fill(bx - 1, by - 1, bx + ancho + 1, by + 3, 0xFF140D08);
				int lleno = Math.round(ancho * fraccion);
				if (lleno > 0) g.fill(bx, by, bx + lleno, by + 2, rgb);
			} else {
				int alto = tam - 2;
				g.fill(x + 1, y + 1, x + 3, y + 1 + alto, 0xFF140D08);
				int lleno = Math.round(alto * fraccion);
				if (lleno > 0) g.fill(x + 1, y + 1 + alto - lleno, x + 3, y + 1 + alto, rgb);
			}
		}
		String n = String.valueOf(c.cantidad());
		g.text(font, n, x + tam - 1 - font.width(n), grande ? y + tam - 14 : y + tam - 8, 0xFFFFFFFF, true);
	}

	private static boolean tiene(Paquetes.CasillaRed c, int equipo) {
		for (Paquetes.Logro l : c.logros()) if (l.equipo() == equipo) return true;
		return false;
	}

	// ---------------------------------------------------------------- barra de progreso

	/** Barra de progreso con el relleno del color dado. destello 0..1: una franja clara que la recorre (-1 = sin franja). */
	static void barra(GuiGraphicsExtractor g, int x, int y, int ancho, int alto, float fraccion, int rgb, float destello) {
		sprite(g, BARRA_FONDO, x, y, ancho, alto);
		int dentro = ancho - 2;
		int lleno = Math.round(dentro * Mth.clamp(fraccion, 0, 1));
		if (lleno <= 0) return;
		sprite(g, BARRA_RELLENO, x + 1, y + 1, lleno, alto - 2, 0xFF000000 | rgb);
		if (destello >= 0 && destello <= 1) {
			int franja = 10;
			int fx = x + 1 + Math.round((lleno + franja) * destello) - franja;
			int a = Math.max(x + 1, fx), b = Math.min(x + 1 + lleno, fx + franja);
			if (b > a) g.fill(a, y + 1, b, y + alto - 1, ARGB.color(0.55f * Mth.sin(Mth.PI * destello), 0xFFFFFF));
		}
	}

	// ---------------------------------------------------------------- textos grandes

	/**
	 * Texto grande centrado en (cx, cy) con contorno oscuro de un píxel de fuente, para que se lea sobre cualquier paisaje.
	 * alfa 0..1 desvanece el conjunto.
	 */
	static void textoGrande(GuiGraphicsExtractor g, Font font, Component texto, float cx, float cy, float escala, int rgb, float alfa) {
		int x = -font.width(texto) / 2, y = -4;
		int contorno = ARGB.color(alfa * alfa * 0.9f, 0x1A0F08);
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().scale(escala, escala);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				if (dx != 0 || dy != 0) g.text(font, texto, x + dx, y + dy, contorno, false);
			}
		}
		g.text(font, texto, x, y, ARGB.color(alfa, rgb & 0xFFFFFF), false);
		g.pose().popMatrix();
	}

	// ---------------------------------------------------------------- banner y confeti

	static final int ALTO_BANNER = 44;

	/**
	 * Título y subtítulo del banner. final_: terminó la partida (si no, es un bingo y sigue).
	 * equipo: ordinal del ganador o negativo; resultado: -2 empate, -3 cancelada por el anfitrión (solo sin ganador).
	 */
	static Component[] textosBanner(boolean final_, int equipo, int miEquipo, int resultado) {
		ColorEquipo c = ColorEquipo.porOrdinal(equipo);
		if (!final_) {
			return new Component[]{Component.translatable("venatio.bingo.titulo"),
				c == null ? Component.empty() : Component.translatable("venatio.bingo.banner", Component.translatable("venatio.equipo." + c.id()))};
		}
		if (c != null) {
			// En el título el nombre del equipo va en blanco: el banner ya es de su color.
			Component gana = Component.translatable("venatio.final.gana", Component.translatable("venatio.equipo." + c.id()));
			return equipo == miEquipo
				? new Component[]{Component.translatable("venatio.final.titulo.ganaste"), gana}
				: new Component[]{gana, Component.translatable("venatio.final.titulo.fin")};
		}
		return new Component[]{Component.translatable("venatio.final.titulo.fin"),
			resultado == -2 ? Component.translatable("venatio.final.empate")
				: resultado == -3 ? Component.translatable("venatio.final.cancelada") : Component.empty()};
	}

	/**
	 * Banner de bingo o de fin de partida, centrado. Entra desde arriba con rebote; si no es fijo, al final se va por donde vino.
	 * t: milisegundos desde que apareció; duracion: cuánto se queda si no es fijo.
	 */
	static void banner(GuiGraphicsExtractor g, Font font, int anchoPantalla, long t, long duracion, int equipo, Component[] textos,
					   int yDestino, boolean fijo) {
		ColorEquipo c = ColorEquipo.porOrdinal(equipo);
		Component titulo = textos[0], subtitulo = textos[1];
		boolean trofeos = c != null;
		int anchoTitulo = font.width(titulo) * 2;
		int ancho = Math.max(200, Math.max(anchoTitulo + (trofeos ? 52 : 0), font.width(subtitulo)) + 32);
		ancho = Math.min(ancho, anchoPantalla - 8);
		int x = (anchoPantalla - ancho) / 2;
		float y = Mth.lerp(Animaciones.rebote(Math.min(1f, t / 450f)), -ALTO_BANNER - 6, yDestino);
		long resta = duracion - t;
		if (!fijo && resta < 350) y = Mth.lerp(Animaciones.suaveFuera(1 - Math.max(0, resta) / 350f), yDestino, -ALTO_BANNER - 6);
		int yi = Math.round(y);

		sprite(g, BANNER_CUERPO, x + 2, yi + 2, ancho - 4, ALTO_BANNER - 4, c != null ? 0xFF000000 | c.rgb : MADERA);
		sprite(g, BANNER_BORDE, x, yi, ancho, ALTO_BANNER);
		if (trofeos) {
			icono(g, ICONO_TROFEO, x + 12, yi + 9);
			icono(g, ICONO_TROFEO, x + ancho - 28, yi + 9);
		}
		g.pose().pushMatrix();
		g.pose().translate(anchoPantalla / 2f, yi + 8);
		g.pose().scale(2f, 2f);
		g.text(font, titulo, -font.width(titulo) / 2, 0, 0xFFFFFFFF, true);
		g.pose().popMatrix();
		g.text(font, subtitulo, (anchoPantalla - font.width(subtitulo)) / 2, yi + 29, 0xFFFFFFFF, true);
	}

	/**
	 * Confeti que cae desde arriba durante unos segundos: papelitos del color del equipo, dorados y blancos.
	 * La trayectoria de cada uno sale de una semilla, así no hay estado que actualizar.
	 */
	static void confeti(GuiGraphicsExtractor g, int ancho, int alto, long inicio, int equipo, int piezas) {
		float t = (Animaciones.ahora() - inicio) / 1000f;
		float total = Animaciones.CONFETI_MS / 1000f;
		if (t < 0 || t > total || equipo < 0) return;
		int rgb = color(equipo);
		int[] paleta = {rgb, rgb, ARGB.srgbLerp(0.5f, rgb, 0xFFFFFF) & 0xFFFFFF, ORO & 0xFFFFFF, ORO_CLARO & 0xFFFFFF, 0xFFFFFF, COBRE & 0xFFFFFF};
		float alfa = t > total - 0.7f ? (total - t) / 0.7f : 1f;
		Random r = new Random(inicio * 31 + equipo);
		for (int i = 0; i < piezas; i++) {
			float x0 = r.nextFloat() * ancho;
			float retraso = r.nextFloat() * 1.1f;
			float vy = 70 + r.nextFloat() * 90;
			float vx = (r.nextFloat() - 0.5f) * 50;
			float fase = r.nextFloat() * Mth.TWO_PI;
			int tam = 2 + r.nextInt(3);
			int c = paleta[r.nextInt(paleta.length)];
			float tt = t - retraso;
			if (tt < 0) continue;
			float y = -6 + vy * tt + 25 * tt * tt;
			if (y > alto) continue;
			float x = x0 + vx * tt + Mth.sin(tt * 5 + fase) * 6;
			// el papelito gira: su ancho aparente va y viene
			int w = Math.max(1, Math.round(tam * Math.abs(Mth.cos(tt * 7 + fase))));
			int xi = Math.round(x), yi = Math.round(y);
			g.fill(xi, yi, xi + w, yi + tam, ARGB.color(alfa, c));
		}
	}
}
