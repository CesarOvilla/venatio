package dev.venatio.cliente;

import dev.venatio.Venatio;
import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Eventos;
import dev.venatio.juego.TipoEvento;
import dev.venatio.red.Paquetes;
import dev.venatio.red.PaquetesEventos;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * Switcheroo y eventos sorpresa en el cliente: la cuenta regresiva del item (roja, latiendo y con tic los últimos 10 s),
 * el banner cuando un equipo gana el punto o el item cambia solo, el anuncio de cada evento, su nombre en grande cuando
 * empieza y el indicador del evento debajo del cartón mientras dura.
 */
public final class HudSorpresas {
	/** Cuánto se queda el banner de Switcheroo o del anuncio de un evento. */
	public static final long BANNER_MS = 2800;
	/** El nombre del evento en grande cuando empieza. */
	public static final long TEXTO_MS = 1800;
	/** Últimos segundos del item de Switcheroo: la cuenta se pone roja, late y suena. */
	public static final int URGENTE = 10;
	private static final int ANCHO_INDICADOR = 150;
	private static final int Y_BANNER = 34;
	/** Alto que ocupa la guía de las ayudas arriba al centro (flecha y dos líneas) y medio ancho de su texto. */
	private static final int ALTO_GUIA = 52, MEDIO_GUIA = 75;

	record Banner(long inicio, int equipo, Component titulo, Component subtitulo) {}

	private static PaquetesEventos.Sorpresas estado = PaquetesEventos.Sorpresas.NADA;
	private static Banner banner;
	private static long textoInicio = Long.MIN_VALUE;
	private static TipoEvento textoEvento;
	/** Último segundo de la cuenta de Switcheroo que sonó (para no repetir el tic). */
	private static int ultimoTic = Integer.MAX_VALUE;
	/** Borde inferior del indicador en el último cuadro (0 si no se dibujó): el banner va debajo. */
	private static int indicadorAbajo;

	private HudSorpresas() {}

	/** Después de HudEfectos: se dibuja encima de sus efectos y debajo del cartón. */
	static void registrar() {
		ClientPlayNetworking.registerGlobalReceiver(PaquetesEventos.Sorpresas.TYPE, (s, ctx) -> estado = s);
		ClientPlayNetworking.registerGlobalReceiver(PaquetesEventos.Aviso.TYPE, (a, ctx) -> recibir(a));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> limpiar());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> limpiar());
		HudElementRegistry.attachElementAfter(Identifier.fromNamespaceAndPath(Venatio.MOD_ID, "carton"),
			Identifier.fromNamespaceAndPath(Venatio.MOD_ID, "sorpresas"), HudSorpresas::dibujarIndicador);
		HudElementRegistry.attachElementAfter(Identifier.fromNamespaceAndPath(Venatio.MOD_ID, "efectos"),
			Identifier.fromNamespaceAndPath(Venatio.MOD_ID, "sorpresas_avisos"), HudSorpresas::dibujarAvisos);
		ClientTickEvents.END_CLIENT_TICK.register(HudSorpresas::alTick);
	}

	private static void limpiar() {
		estado = PaquetesEventos.Sorpresas.NADA;
		banner = null;
		textoInicio = Long.MIN_VALUE;
		textoEvento = null;
		ultimoTic = Integer.MAX_VALUE;
	}

	private static void recibir(PaquetesEventos.Aviso a) {
		long ahora = Animaciones.ahora();
		TipoEvento t = TipoEvento.porId(a.evento());
		switch (a.tipo()) {
			case PaquetesEventos.Aviso.PUNTO -> {
				// En el título el equipo va en blanco: el banner ya es de su color. El sonido lo toca el evento CASILLA.
				ColorEquipo c = ColorEquipo.porOrdinal(a.equipo());
				Component equipo = c == null ? Component.literal("?") : Component.translatable("venatio.equipo." + c.id());
				banner = new Banner(ahora, a.equipo(), Component.translatable("venatio.switcheroo.banner.punto", equipo),
					Component.translatable("venatio.switcheroo.banner.punto_sub", a.item().getHoverName()));
			}
			case PaquetesEventos.Aviso.CAMBIO -> {
				banner = new Banner(ahora, -1, Component.translatable("venatio.switcheroo.banner.cambio"),
					Component.translatable("venatio.switcheroo.banner.cambio_sub", a.item().getHoverName()));
				Sonidos.tocar(Sonidos.SWITCHEROO_CAMBIO);
			}
			case PaquetesEventos.Aviso.ANUNCIO -> {
				if (t == null) return;
				banner = new Banner(ahora, -1, Component.translatable("venatio.evento.banner.anuncio"),
					Component.translatable("venatio.evento.banner.anuncio_sub", t.nombre(), Eventos.ANUNCIO));
				Sonidos.tocar(Sonidos.EVENTO_ANUNCIO);
			}
			case PaquetesEventos.Aviso.EMPIEZA -> {
				if (t == null) return;
				// El anuncio ya cumplió: en su lugar sale el nombre en grande.
				banner = null;
				textoEvento = t;
				textoInicio = ahora;
				Sonidos.tocar(Sonidos.EVENTO_INICIO);
			}
			case PaquetesEventos.Aviso.TERMINA -> {
				if (a.evento().equals(estado.evento())) {
					estado = new PaquetesEventos.Sorpresas(estado.cambio(), estado.duracion(), "", -1, -1);
				}
			}
			default -> {}
		}
	}

	// ---------------------------------------------------------------- Switcheroo

	/** Segundos que le quedan al item de Switcheroo, o -1 si no se está jugando Switcheroo. */
	static int switcherooRestante() {
		Paquetes.Estado e = EstadoCliente.estado;
		if (estado.cambio() < 0 || e == null || e.fase() != Paquetes.Estado.JUGANDO) return -1;
		return Math.max(0, estado.cambio() - EstadoCliente.segundos());
	}

	static int switcherooDuracion() {
		return estado.duracion();
	}

	/** Switcheroo, últimos 10 s: el recuadro del item late en rojo. Va encima del item (llamar tras nextStratum). */
	static void urgencia(GuiGraphicsExtractor g, int x0, int y0, int ancho, int alto) {
		int queda = switcherooRestante();
		if (queda < 0 || queda > URGENTE) return;
		float pulso = 0.5f + 0.5f * Mth.sin(Animaciones.ahora() / 1000f * Mth.TWO_PI * 2f);
		g.fill(x0 + 2, y0 + 2, x0 + ancho - 2, y0 + alto - 2, ARGB.color(0.10f + 0.20f * pulso, 0xE0302A));
		g.outline(x0 + 1, y0 + 1, ancho - 2, alto - 2, ARGB.color(0.45f + 0.55f * pulso, 0xFF5A48));
	}

	private static void alTick(Minecraft mc) {
		int queda = switcherooRestante();
		if (queda >= 1 && queda <= URGENTE && queda < ultimoTic) {
			// Cada segundo un tic, un poco más agudo conforme se acaba.
			Sonidos.tocar(Sonidos.CUENTA_TIC, 1.1f + (URGENTE - queda) * 0.05f);
			ultimoTic = queda;
		} else if (queda < 0 || queda > URGENTE) {
			ultimoTic = Integer.MAX_VALUE;
		}
		long ahora = Animaciones.ahora();
		if (banner != null && ahora - banner.inicio() > BANNER_MS) banner = null;
		if (textoEvento != null && ahora - textoInicio > TEXTO_MS) textoEvento = null;
	}

	// ---------------------------------------------------------------- dibujo

	/** Banner de Switcheroo o del anuncio y el nombre del evento en grande. */
	private static void dibujarAvisos(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		Paquetes.Estado e = EstadoCliente.estado;
		if (e == null || mc.player == null || mc.gui.screen() instanceof PantallaFinal) return;
		Font font = mc.font;
		int ancho = g.guiWidth(), alto = g.guiHeight();
		long ahora = Animaciones.ahora();

		if (banner != null && ahora - banner.inicio() <= BANNER_MS && ahora >= banner.inicio()) {
			// Debajo del indicador del evento y del banner de bingo o victoria; a la derecha del cartón si la pantalla es angosta.
			int y = Math.max(Y_BANNER, indicadorAbajo + 4);
			if (Animaciones.banner != null) y = Math.max(y, Y_BANNER + Arte.ALTO_BANNER + 4);
			Component[] textos = {banner.titulo(), banner.subtitulo()};
			int anchoBanner = Math.min(ancho - 8, Math.max(200,
				Math.max(font.width(banner.titulo()) * 2 + (banner.equipo() >= 0 ? 52 : 0), font.width(banner.subtitulo())) + 32));
			int izquierda = (ancho - anchoBanner) / 2;
			int dx = Math.max(0, Math.min(HudCarton.derecha() + 4 - izquierda, ancho - 4 - (izquierda + anchoBanner)));
			g.pose().pushMatrix();
			g.pose().translate(dx, 0);
			Arte.banner(g, font, ancho, ahora - banner.inicio(), BANNER_MS, banner.equipo(), textos, y, false);
			g.pose().popMatrix();
		}

		float p = textoEvento == null ? -1 : Animaciones.progreso(textoInicio, TEXTO_MS);
		if (p >= 0) {
			Component texto = Component.translatable("venatio.evento.grande", textoEvento.nombre());
			float entra = Math.min(1f, p / 0.18f);
			float sale = p > 0.75f ? (p - 0.75f) / 0.25f : 0f;
			float maximo = Math.min(3f, (ancho - 24f) / Math.max(1, font.width(texto)));
			float escala = maximo * Animaciones.rebote(entra) * (1f + 0.15f * sale);
			float alfa = Math.min(1f, entra * 2) * (1 - sale);
			if (alfa < 0.04f) return;
			float cy = alto / 2f - 46;
			Arte.textoGrande(g, font, texto, ancho / 2f, cy, escala, Arte.ORO_CLARO, alfa);
			Component desc = textoEvento.descripcion();
			int medio = font.width(desc) / 2 + 6;
			int fondo = ARGB.color(alfa * 0.7f, 0x140D08);
			g.fill(ancho / 2 - medio, Math.round(cy) + 18, ancho / 2 + medio, Math.round(cy) + 31, fondo);
			g.centeredText(font, desc, ancho / 2, Math.round(cy) + 21, ARGB.color(alfa, Arte.CREMA & 0xFFFFFF));
		}
	}

	/**
	 * Arriba, a la derecha del cartón (abajo a la izquierda lo taparía el chat): el evento anunciado, con su cuenta y
	 * parpadeando, o el que está en curso, con lo que le queda. Si hay una guía de las ayudas arriba al centro, va debajo.
	 */
	private static void dibujarIndicador(GuiGraphicsExtractor g, DeltaTracker delta) {
		indicadorAbajo = 0;
		Minecraft mc = Minecraft.getInstance();
		Paquetes.Estado e = EstadoCliente.estado;
		if (e == null || mc.player == null || e.fase() != Paquetes.Estado.JUGANDO || estado.evento().isEmpty()) return;
		TipoEvento t = TipoEvento.porId(estado.evento());
		if (t == null) return;
		int s = EstadoCliente.segundos();
		boolean anuncio = s < estado.desde();
		if (!anuncio && s >= estado.hasta()) return;
		Font font = mc.font;

		Component titulo = anuncio ? Component.translatable("venatio.evento.banner.anuncio") : t.nombre();
		Component linea = anuncio ? t.nombre() : t.descripcion();
		Component reloj = anuncio ? Component.translatable("venatio.evento.hud.en", Math.min(Eventos.ANUNCIO, estado.desde() - s))
			: t.segundos > 0 ? Component.literal(EstadoCliente.reloj(estado.hasta() - s)) : Component.translatable("venatio.evento.hud.ahora");
		boolean barra = !anuncio && t.segundos > 0;
		int ancho = Math.max(ANCHO_INDICADOR, 24 + Math.max(font.width(titulo) + 8 + font.width(reloj), font.width(linea)) + 6);
		int alto = 26 + (barra ? 6 : 0);
		int x = Math.max(4, Math.min(HudCarton.derecha() + 6, g.guiWidth() - ancho - 4)), y = 4;
		int centro = g.guiWidth() / 2;
		if (AyudasCliente.guia != null && x < centro + MEDIO_GUIA && x + ancho > centro - MEDIO_GUIA) y = ALTO_GUIA + 4;
		indicadorAbajo = y + alto;

		// Durante el anuncio el recuadro parpadea.
		boolean parpadeo = anuncio && (Animaciones.ahora() / 350) % 2 == 0;
		Arte.fila(g, x, y, ancho, alto, parpadeo);
		g.item(new ItemStack(t.icono), x + 4, y + 5);
		int colorTitulo = anuncio ? Arte.COBRE : Arte.ORO;
		g.text(font, titulo, x + 24, y + 4, colorTitulo, true);
		g.text(font, reloj, x + ancho - 5 - font.width(reloj), y + 4, anuncio ? Arte.COBRE : Arte.CREMA, true);
		g.text(font, linea, x + 24, y + 15, anuncio ? Arte.CREMA : Arte.GRIS, true);
		if (barra) {
			float queda = Mth.clamp((float) (estado.hasta() - s) / t.segundos, 0, 1);
			Arte.barra(g, x + 24, y + alto - 8, ancho - 24 - 5, 5, queda, Arte.ORO & 0xFFFFFF, -1);
		}
	}

	// Para las pruebas.

	public static String eventoEnHud() {
		return estado.evento();
	}

	public static boolean hayBanner() {
		return banner != null;
	}

	/** Cuándo empezó el banner en curso (reloj de {@link Animaciones}), o MIN_VALUE si no hay. */
	public static long inicioBanner() {
		return banner == null ? Long.MIN_VALUE : banner.inicio();
	}

	/** Cuándo empezó el nombre en grande del evento, o MIN_VALUE si no hay. */
	public static long inicioTexto() {
		return textoEvento == null ? Long.MIN_VALUE : textoInicio;
	}

	public static int segundosSwitcheroo() {
		return switcherooRestante();
	}
}
