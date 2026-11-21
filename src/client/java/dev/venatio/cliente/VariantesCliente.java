package dev.venatio.cliente;

import dev.venatio.juego.ColorEquipo;
import dev.venatio.red.Paquetes;
import dev.venatio.red.PaquetesVariantes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Variantes del cartón en el cliente: qué casillas se ven tapadas (cartón oculto), la casilla secreta, las filas del
 * relevo con la cara de su dueño, las animaciones al destapar y al vetar, y la apertura de la pantalla del draft.
 * Todo sale del último estado ({@link Paquetes.Estado#variantes()}). Solo se usa en el hilo del cliente.
 */
public final class VariantesCliente {
	/** Una casilla que se destapa: la tapa se cierra de lado y el item entra creciendo. */
	public static final long DESTAPE_MS = 520;
	/** Una casilla vetada en el draft: destello rojo y el item nuevo entra. */
	public static final long VETO_MS = 650;
	/** Ancho de la columna de caras del relevo junto al cartón del HUD y de la pantalla B. */
	static final int COLUMNA_HUD = 11, COLUMNA_PANTALLA = 22;

	private static final Map<Integer, Long> destapes = new HashMap<>();
	private static final Map<Integer, Long> vetos = new HashMap<>();
	private static long draftRecibido;

	private VariantesCliente() {}

	/** Último estado recibido (para las pruebas). */
	public static Paquetes.Estado estado() {
		return EstadoCliente.estado;
	}

	static PaquetesVariantes.Variantes de(Paquetes.Estado e) {
		return e == null ? PaquetesVariantes.Variantes.NINGUNA : e.variantes();
	}

	// ---------------------------------------------------------------- cartón oculto

	/**
	 * Si la casilla se dibuja tapada en la vista del equipo visto: está tapada para mí (el servidor no mandó lo que pide)
	 * o, viendo el cartón de otro equipo, ese equipo todavía no la destapa.
	 */
	public static boolean tapada(Paquetes.Estado e, int indice, int visto) {
		if (e == null) return false;
		PaquetesVariantes.Variantes v = e.variantes();
		if (!v.oculto()) return false;
		boolean paraMi = e.miEquipo() >= 0 ? !v.destapada(e.miEquipo(), indice) : !v.destapadaPorAlguno(indice);
		return paraMi || visto >= 0 && visto != e.miEquipo() && !v.destapada(visto, indice);
	}

	static boolean tapada(int indice, int visto) {
		return tapada(EstadoCliente.estado, indice, visto);
	}

	/** Progreso 0..1 de la animación de destapar, o -1 si no hay. */
	static float destape(int indice) {
		Long t = destapes.get(indice);
		if (t == null) return -1;
		long d = Animaciones.ahora() - t;
		if (d < 0) return 0;
		if (d > DESTAPE_MS) {
			destapes.remove(indice);
			return -1;
		}
		return (float) d / DESTAPE_MS;
	}

	/** Para las pruebas: si la casilla está animando su destape. */
	public static boolean destapando(int indice) {
		return destapes.containsKey(indice);
	}

	// ---------------------------------------------------------------- casilla secreta

	/** Casilla secreta del equipo visto, si quien mira la conoce (la suya, o todas al final); -1 si no. */
	static int secreta(int visto) {
		return visto < 0 ? -1 : de(EstadoCliente.estado).secretaDe(visto);
	}

	/** Las casillas secretas reveladas al final, para la pantalla final: "Casilla secreta de Rojo: Diamante (+1)". */
	public static List<Component> lineasSecretas() {
		List<Component> lineas = new ArrayList<>();
		for (Revelada r : secretasReveladas()) lineas.add(r.linea());
		return lineas;
	}

	/** Una casilla secreta revelada: lo que pedía y la línea que se muestra. */
	private record Revelada(Paquetes.CasillaRed casilla, Component linea) {}

	private static List<Revelada> secretasReveladas() {
		Paquetes.Estado e = EstadoCliente.estado;
		List<Revelada> lista = new ArrayList<>();
		if (e == null || e.fase() != Paquetes.Estado.FINAL) return lista;
		for (PaquetesVariantes.Secreta s : e.variantes().secretas()) {
			ColorEquipo c = ColorEquipo.porOrdinal(s.equipo());
			if (c == null || s.casilla() >= e.casillas().size()) continue;
			Paquetes.CasillaRed cas = e.casillas().get(s.casilla());
			boolean suya = cas.logros().stream().anyMatch(l -> l.equipo() == s.equipo());
			lista.add(new Revelada(cas, Component.translatable("venatio.secreta.final", c.nombre(), cas.icono().getHoverName(),
				Component.translatable(suya ? "venatio.secreta.mas_uno" : "venatio.secreta.sin").withColor(suya ? Arte.ORO : Arte.GRIS))));
		}
		return lista;
	}

	/**
	 * Debajo del marcador de la pantalla final: el título y, por equipo, el item de su casilla secreta con la línea
	 * ajustada al ancho. Devuelve la y siguiente; no dibuja nada si la partida no tenía casilla secreta, y corta lo que no quepa.
	 */
	static int dibujarSecretasFinal(GuiGraphicsExtractor g, Font font, int x, int y, int ancho, int yMax, float alfa) {
		List<Revelada> lista = secretasReveladas();
		if (lista.isEmpty() || y + 11 > yMax || alfa <= 0.03f) return y;
		Arte.sprite(g, Arte.SECRETA, x + 1, y, 7, 7, ARGB.color(alfa, 0xFFFFFF));
		g.text(font, Component.translatable("venatio.secreta.titulo"), x + 11, y, ARGB.color(alfa, Arte.ORO & 0xFFFFFF), true);
		y += 11;
		int blanco = ARGB.color(alfa, Arte.CREMA & 0xFFFFFF);
		for (Revelada r : lista) {
			int alto = Math.max(16, font.wordWrapHeight(r.linea(), ancho - 19));
			if (y + Math.min(alto, 16) > yMax) break;
			g.item(r.casilla().icono(), x, y);
			g.textWithWordWrap(font, r.linea(), x + 19, alto > 16 ? y : y + 4, ancho - 19, blanco);
			y += alto + 2;
		}
		return y;
	}

	// ---------------------------------------------------------------- relevo

	static boolean relevo(Paquetes.Estado e) {
		return e != null && e.variantes().relevo();
	}

	static String duenoDeFila(Paquetes.Estado e, int equipo, int fila) {
		return e == null ? null : e.variantes().duenoDeFila(equipo, fila);
	}

	/** Si la fila es mía (relevo, en el cartón de mi equipo). */
	static boolean filaMia(Paquetes.Estado e, int fila) {
		Minecraft mc = Minecraft.getInstance();
		String dueno = duenoDeFila(e, e.miEquipo(), fila);
		return dueno != null && mc.player != null && dueno.equals(mc.player.getGameProfile().name());
	}

	/** Filas (1, 2…) de un jugador en su equipo, "1, 3, 5". */
	static String filasDe(Paquetes.Estado e, int equipo, String jugador) {
		List<String> lista = new ArrayList<>();
		for (PaquetesVariantes.Fila f : e.variantes().filas()) {
			if (f.equipo() == equipo && f.jugador().equals(jugador)) lista.add(String.valueOf(f.fila() + 1));
		}
		return String.join(", ", lista);
	}

	/**
	 * Columna con la cara del dueño de cada fila del equipo visto, a la derecha del cartón. Mis filas llevan marco dorado.
	 * x: borde izquierdo de la columna; y0: arriba de la primera fila; celda: alto de una fila; tam: lado de la cara.
	 * Devuelve la fila bajo el ratón (-1 si ninguna).
	 */
	static int dibujarCaras(GuiGraphicsExtractor g, Paquetes.Estado e, int visto, int x, int y0, int celda, int tam, int mouseX, int mouseY) {
		int hover = -1;
		boolean mio = visto == e.miEquipo();
		for (int r = 0; r < e.tamano(); r++) {
			String dueno = duenoDeFila(e, visto, r);
			if (dueno == null) continue;
			int y = y0 + r * celda + (celda - 1 - tam) / 2;
			boolean mia = mio && filaMia(e, r);
			g.fill(x - 1, y - 1, x + tam + 1, y + tam + 1, mia ? Arte.ORO : 0xFF140D08);
			cara(g, dueno, x, y, tam);
			if (mouseX >= x - 1 && mouseX < x + tam + 1 && mouseY >= y - 1 && mouseY < y + tam + 1) hover = r;
		}
		return hover;
	}

	/** Cara del jugador (de la lista de jugadores; la de por defecto si no está). */
	static void cara(GuiGraphicsExtractor g, String nombre, int x, int y, int tam) {
		ClientPacketListener con = Minecraft.getInstance().getConnection();
		PlayerInfo info = con == null ? null : con.getPlayerInfo(nombre);
		PlayerSkin skin = info != null ? info.getSkin() : DefaultPlayerSkin.getDefaultSkin();
		PlayerFaceExtractor.extractRenderState(g, skin, x, y, tam);
	}

	/** Tooltip de la cara de una fila: "Fila 2: Bot1" o "Fila 3: tuya". */
	static Component textoFila(Paquetes.Estado e, int visto, int fila) {
		String dueno = duenoDeFila(e, visto, fila);
		if (visto == e.miEquipo() && filaMia(e, fila)) return Component.translatable("venatio.relevo.fila_tuya", fila + 1);
		return Component.translatable("venatio.relevo.fila", fila + 1, dueno == null ? "?" : dueno);
	}

	// ---------------------------------------------------------------- draft

	public static boolean enDraft() {
		return de(EstadoCliente.estado).draftEnCurso();
	}

	/** Segundos que le quedan al draft, avanzando entre paquetes. */
	static int segundosDraft() {
		PaquetesVariantes.Draft d = de(EstadoCliente.estado).draft();
		if (d == null || !d.enCurso()) return 0;
		return Math.max(0, d.segundos() - (int) Math.min(1, (Util.getMillis() - draftRecibido) / 1000));
	}

	/** Progreso 0..1 de la animación de una casilla recién vetada, o -1. */
	static float veto(int indice) {
		Long t = vetos.get(indice);
		if (t == null) return -1;
		long d = Animaciones.ahora() - t;
		if (d > VETO_MS) {
			vetos.remove(indice);
			return -1;
		}
		return Mth.clamp((float) d / VETO_MS, 0, 1);
	}

	/** Si la casilla se vetó en el draft que está en curso (para marcarla con el sello). */
	static boolean vetadaEnDraft(int indice) {
		PaquetesVariantes.Draft d = de(EstadoCliente.estado).draft();
		return d != null && d.enCurso() && d.vetoDe(indice) != null;
	}

	/** La línea de la fase en el HUD mientras dura el draft, o null. */
	static Component lineaDraft(Paquetes.Estado e) {
		if (!de(e).draftEnCurso()) return null;
		return Component.translatable("venatio.hud.draft", EstadoCliente.reloj(segundosDraft()));
	}

	// ---------------------------------------------------------------- lo que llega del servidor

	/** Llegó un estado nuevo: animaciones de destape y de veto, y la pantalla del draft al empezar. */
	static void alCambiarEstado(Paquetes.Estado antes, Paquetes.Estado e) {
		PaquetesVariantes.Variantes va = de(antes), v = e.variantes();
		if (e.casillas().isEmpty()) {
			destapes.clear();
			vetos.clear();
			return;
		}
		long ahora = Animaciones.ahora();
		boolean mismoCarton = antes != null && antes.tamano() == e.tamano() && antes.casillas().size() == e.casillas().size();
		int mio = e.miEquipo();
		if (v.draftEnCurso()) draftRecibido = Util.getMillis();

		// Destapes de mi equipo; al final se destapa todo en diagonal desde la esquina.
		if (mismoCarton && va.oculto()) {
			int n = e.tamano();
			boolean alguna = false;
			for (int i = 0; i < e.casillas().size(); i++) {
				boolean antesTapada = tapada(antes, i, mio), ahoraTapada = tapada(e, i, mio);
				if (!antesTapada || ahoraTapada) continue;
				long retraso = v.oculto() ? 0 : (i % n + i / n) * 70L;
				destapes.put(i, ahora + retraso);
				alguna = true;
			}
			if (alguna) Sonidos.tocar(Sonidos.CARTON_ABRIR, 1.5f);
		}

		// Vetos nuevos en el draft.
		if (mismoCarton && v.draft() != null) {
			int antesVetos = va.draft() == null ? 0 : va.draft().vetados().size();
			List<PaquetesVariantes.Veto> lista = v.draft().vetados();
			for (int k = antesVetos; k < lista.size(); k++) vetos.put(lista.get(k).casilla(), ahora);
			if (lista.size() > antesVetos) Sonidos.tocar(Sonidos.MARCA, 0.7f);
		}

		// Empieza el draft: se abre su pantalla si no hay otra delante.
		Minecraft mc = Minecraft.getInstance();
		if (v.draftEnCurso() && !va.draftEnCurso() && mc.player != null && mc.gui.screen() == null) {
			mc.gui.setScreen(new PantallaDraft());
		}
	}

	static void limpiar() {
		destapes.clear();
		vetos.clear();
	}
}
