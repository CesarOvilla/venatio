package dev.venatio.cliente.localizador;

import dev.venatio.ayudas.NombresObjetivo;
import dev.venatio.cliente.AyudasCliente;
import dev.venatio.cliente.localizador.LocalizadorCliente.Dibujado;
import dev.venatio.cliente.localizador.LocalizadorCliente.Seguido;
import dev.venatio.cliente.localizador.LocalizadorCliente.Tipo;
import dev.venatio.juego.ColorEquipo;
import dev.venatio.red.PaquetesTienda;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Dibujo de la barra de localización, en el lugar de la de experiencia (como la vanilla de 1.21.6+), con más información:
 * la cara de cada jugador con borde del color de su equipo, puntas en los extremos para quien queda fuera de la vista,
 * nombre y distancia del que está al centro, y las marcas de equipo y la guía activa.
 */
final class BarraLocalizador {
	private static final int ANCHO = 182;
	/** Grados a cada lado del centro que caben en la barra (vanilla: 60). */
	private static final float CAMPO = 60f;
	/** Como vanilla: ±60° ocupan 173 px. */
	private static final float PX_POR_GRADO = 173f / 2f / CAMPO;
	/** Umbrales de distancia de los puntos vanilla (WaypointStyle): cerca, media, lejos. */
	private static final int CERCA = 128, LEJOS = 332;
	/** Si el punto más centrado está a menos de esto, se muestra su nombre y distancia. */
	private static final float GRADOS_ETIQUETA = 8f;
	private static final float ESCALA_TEXTO = 0.75f;
	private static final int PUNTAS_POR_LADO = 4;
	private static final int NEGRO = 0xFF000000;
	private static final int SOMBRA = 0xC0101010;

	private static final Identifier FONDO = Identifier.withDefaultNamespace("hud/locator_bar_background");
	private static final Identifier FLECHA_ARRIBA = Identifier.withDefaultNamespace("hud/locator_bar_arrow_up");
	private static final Identifier FLECHA_ABAJO = Identifier.withDefaultNamespace("hud/locator_bar_arrow_down");

	/** Un punto a dibujar este cuadro. conAltura: lleva flecha arriba/abajo (la guía no: solo conoce x y z). */
	private record Punto(Tipo tipo, String nombre, int rgb, UUID uuid, Vec3 pos, double distancia, float angulo,
						 Component etiqueta, boolean conAltura) {}

	private BarraLocalizador() {}

	/**
	 * Como Hud.nextContextualInfoState con la barra vanilla: hay barra si hay algo que mostrar, salvo que esté cargando
	 * un salto a caballo o que la experiencia haya cambiado hace menos de 5 s (Hud.willPrioritizeExperienceInfo).
	 */
	static boolean debeMostrarse(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null || mc.gameMode == null || !LocalizadorCliente.activa() || !hayPuntos(mc)) return false;
		PlayerRideableJumping montura = p.jumpableVehicle();
		if (montura != null && (p.getJumpRidingScale() > 0 || montura.getJumpCooldown() > 0)) return false;
		return !(mc.gameMode.hasExperience() && p.experienceDisplayStartTick + 100 > p.tickCount);
	}

	private static boolean hayPuntos(Minecraft mc) {
		if (!LocalizadorCliente.seguidosEn(mc).isEmpty()) return true;
		Identifier aqui = mc.level.dimension().identifier();
		PaquetesTienda.Guia guia = AyudasCliente.guiaActual();
		if (guia != null && guia.dimension().equals(aqui)) return true;
		for (PaquetesTienda.MarcaRed m : AyudasCliente.marcasActuales()) {
			if (m.dimension().equals(aqui)) return true;
		}
		return false;
	}

	static void fondo(GuiGraphicsExtractor g) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, FONDO, izquierda(g), arriba(g), ANCHO, 5);
	}

	static void puntos(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		Camera camara = mc.gameRenderer.mainCamera();
		if (!camara.isInitialized()) return;
		Vec3 ojo = camara.position();
		List<Punto> puntos = reunir(mc, ojo, camara.yRot(), delta.getGameTimeDeltaPartialTick(true));

		int top = arriba(g);
		float centro = g.guiWidth() / 2f;
		List<Dibujado> dibujados = new ArrayList<>();

		// Dentro del campo de visión: de lejos a cerca, para que los cercanos queden encima.
		puntos.sort(Comparator.comparingDouble(Punto::distancia).reversed());
		Punto centrado = null;
		float xCentrado = 0;
		int tamCentrado = 0;
		for (Punto pt : puntos) {
			if (!dentro(pt.angulo)) continue;
			float cx = centro + pt.angulo * PX_POR_GRADO;
			int tam = tamano(pt.distancia);
			icono(g, mc, pt, cx, top, tam);
			int flecha = pt.conAltura ? flecha(g, mc, pt.pos, cx, top, tam) : 0;
			dibujados.add(new Dibujado(pt.tipo, pt.nombre, pt.rgb, cx - centro, tam, flecha, false, 0));
			if (Math.abs(pt.angulo) < GRADOS_ETIQUETA && (centrado == null || Math.abs(pt.angulo) < Math.abs(centrado.angulo))) {
				centrado = pt;
				xCentrado = cx;
				tamCentrado = tam;
			}
		}

		// Fuera del campo de visión: una punta en el extremo de su lado; la más cercana va pegada a la barra.
		int izquierda = izquierda(g);
		int[] porLado = new int[2];
		for (int i = puntos.size() - 1; i >= 0; i--) {
			Punto pt = puntos.get(i);
			if (dentro(pt.angulo)) continue;
			int lado = pt.angulo > 0 ? 1 : -1;
			int orden = porLado[lado > 0 ? 1 : 0]++;
			if (orden >= PUNTAS_POR_LADO) continue;
			int base = lado > 0 ? izquierda + ANCHO + 1 + orden * 4 : izquierda - 2 - orden * 4;
			punta(g, base, top, lado, pt.rgb);
			dibujados.add(new Dibujado(pt.tipo, pt.nombre, pt.rgb, base - centro, 0, 0, true, lado));
		}

		String texto = null;
		if (centrado != null) {
			etiqueta(g, mc, centrado.etiqueta, xCentrado, top, tamCentrado);
			texto = centrado.etiqueta.getString();
		}
		LocalizadorCliente.registrarCuadro(dibujados, texto);
	}

	// ---------------------------------------------------------------- puntos

	private static List<Punto> reunir(Minecraft mc, Vec3 ojo, float yaw, float parcial) {
		List<Punto> lista = new ArrayList<>();
		long ms = Util.getMillis();
		for (Seguido s : LocalizadorCliente.seguidosEn(mc)) {
			// Si el cliente tiene cargado al jugador, su posición interpolada de cada cuadro; si no, la de los paquetes.
			Player cargado = mc.level.getPlayerByUUID(s.uuid);
			Vec3 pos = cargado != null ? cargado.getEyePosition(parcial) : s.posicion(ms);
			int rgb = rgb(s.color);
			double dist = ojo.distanceTo(pos);
			Component texto = Component.translatable("venatio_localizador.jugador", Component.literal(s.nombre).withColor(rgb), Math.round(dist));
			lista.add(new Punto(Tipo.JUGADOR, s.nombre, rgb, s.uuid, pos, dist, angulo(ojo, pos, yaw), texto, true));
		}

		Identifier aqui = mc.level.dimension().identifier();
		for (PaquetesTienda.MarcaRed m : AyudasCliente.marcasActuales()) {
			if (!m.dimension().equals(aqui)) continue;
			Vec3 pos = new Vec3(m.x() + 0.5, m.y() + 1.0, m.z() + 0.5);
			double dist = ojo.distanceTo(pos);
			Component texto = Component.translatable("venatio_localizador.marca", m.autor(), Math.round(dist));
			lista.add(new Punto(Tipo.MARCA, m.autor(), rgb(m.color()), null, pos, dist, angulo(ojo, pos, yaw), texto, true));
		}

		PaquetesTienda.Guia guia = AyudasCliente.guiaActual();
		if (guia != null && guia.dimension().equals(aqui)) {
			Vec3 pos = new Vec3(guia.x() + 0.5, ojo.y, guia.z() + 0.5); // a la altura de los ojos: distancia horizontal
			double dist = ojo.distanceTo(pos);
			Component nombre = NombresObjetivo.de(guia.tipo(), guia.objetivo());
			Component texto = Component.translatable("venatio_localizador.guia", nombre, AyudasCliente.rangoGuia(dist));
			lista.add(new Punto(Tipo.GUIA, nombre.getString(), 0xFFFFFF, null, pos, dist, angulo(ojo, pos, yaw), texto, false));
		}
		return lista;
	}

	/** Grados del punto respecto de hacia dónde mira la cámara; positivo = a la derecha. Yaw 0 = sur (+Z), 90 = oeste. */
	private static float angulo(Vec3 ojo, Vec3 pos, float yaw) {
		double dx = pos.x - ojo.x, dz = pos.z - ojo.z;
		if (dx * dx + dz * dz < 1) return 0; // encima o debajo de la cámara: al centro, no hacia un lado al azar
		return Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)) - yaw);
	}

	private static boolean dentro(float angulo) {
		return angulo > -CAMPO && angulo <= CAMPO;
	}

	private static int tamano(double distancia) {
		return distancia < CERCA ? 9 : distancia < LEJOS ? 7 : 5;
	}

	private static int rgb(int ordinal) {
		ColorEquipo c = ColorEquipo.porOrdinal(ordinal);
		return c == null ? 0xFFFFFF : c.rgb;
	}

	// ---------------------------------------------------------------- dibujo

	private static int izquierda(GuiGraphicsExtractor g) {
		return (g.guiWidth() - ANCHO) / 2;
	}

	private static int arriba(GuiGraphicsExtractor g) {
		return g.guiHeight() - 24 - 5;
	}

	/** Fila de arriba del icono: todos quedan centrados un píxel arriba del centro de la barra, sin tocar la barra rápida. */
	private static int iconoArriba(int top, int tam) {
		return top + 1 - tam / 2;
	}

	private static void icono(GuiGraphicsExtractor g, Minecraft mc, Punto pt, float cx, int top, int tam) {
		int x = Math.round(cx - tam / 2f), y = iconoArriba(top, tam);
		switch (pt.tipo) {
			case JUGADOR -> {
				ClientPacketListener conexion = mc.getConnection();
				PlayerInfo info = conexion == null ? null : conexion.getPlayerInfo(pt.uuid);
				if (info != null) {
					g.fill(x - 1, y - 1, x + tam + 1, y + tam + 1, NEGRO | pt.rgb);
					PlayerFaceExtractor.extractRenderState(g, info.getSkin().body().texturePath(), x, y, tam, info.showHat(), false, -1);
				} else {
					// Sin la piel del jugador: el punto vanilla con el color de su equipo.
					g.blitSprite(RenderPipelines.GUI_TEXTURED, puntoVanilla(tam), Math.round(cx - 4.5f), top - 2, 9, 9, NEGRO | pt.rgb);
				}
			}
			case MARCA -> g.blit(RenderPipelines.GUI_TEXTURED, textura("rombo", tam), x, y, 0, 0, tam, tam, tam, tam, NEGRO | pt.rgb);
			case GUIA -> g.blit(RenderPipelines.GUI_TEXTURED, textura("brujula", tam), x, y, 0, 0, tam, tam, tam, tam);
		}
	}

	private static Identifier textura(String nombre, int tam) {
		return Identifier.fromNamespaceAndPath("venatio_localizador", "textures/gui/" + nombre + "_" + tam + ".png");
	}

	private static Identifier puntoVanilla(int tam) {
		return Identifier.withDefaultNamespace("hud/locator_bar_dot/default_" + (tam == 9 ? 0 : tam == 7 ? 1 : 3));
	}

	/**
	 * Flecha arriba o abajo si el punto queda fuera de la pantalla en vertical (la misma regla que la barra vanilla).
	 * Devuelve 1 (arriba), -1 (abajo) o 0.
	 */
	private static int flecha(GuiGraphicsExtractor g, Minecraft mc, Vec3 pos, float cx, int top, int tam) {
		Vec3 enPantalla = mc.gameRenderer.projectPointToScreen(pos);
		boolean detras = enPantalla.z > 1.0;
		double y = detras ? -enPantalla.y : enPantalla.y;
		int sentido = y < -1.0 ? -1 : y > 1.0 ? 1 : detras ? (int) Math.signum(enPantalla.y) : 0;
		if (sentido == 0) return 0;
		int x = Math.round(cx - 3.5f);
		int arribaIcono = iconoArriba(top, tam);
		if (sentido > 0) g.blitSprite(RenderPipelines.GUI_TEXTURED, FLECHA_ARRIBA, x, arribaIcono - 5, 7, 5);
		else g.blitSprite(RenderPipelines.GUI_TEXTURED, FLECHA_ABAJO, x, arribaIcono + tam, 7, 5);
		return sentido;
	}

	/** Punta de 3 px que sale del extremo de la barra hacia el lado donde está el punto, con contorno oscuro. */
	private static void punta(GuiGraphicsExtractor g, int base, int top, int lado, int rgb) {
		for (int col = 0; col < 4; col++) {
			int x = base + col * lado;
			g.fill(x, top - 1 + col, x + 1, top + 6 - col, SOMBRA);
			if (col < 3) g.fill(x, top + col, x + 1, top + 5 - col, NEGRO | rgb);
		}
	}

	/** Nombre y distancia en letra pequeña con fondo, encima del icono (o encima del número de nivel, si se ve). */
	private static void etiqueta(GuiGraphicsExtractor g, Minecraft mc, Component texto, float cx, int top, int tam) {
		Font font = mc.font;
		// ~3/4 del tamaño normal, redondeado para que cada píxel de la letra ocupe píxeles enteros de pantalla (si no, se ve chueca).
		int escalaGui = Math.max(1, mc.getWindow().getGuiScale());
		float escala = Math.max(1, Math.round(escalaGui * ESCALA_TEXTO)) / (float) escalaGui;
		boolean nivel = mc.gameMode != null && mc.gameMode.hasExperience() && mc.player.experienceLevel > 0;
		int abajo = nivel ? top - 7 : iconoArriba(top, tam) - 2;
		int ancho = font.width(texto);
		float anchoEscalado = ancho * escala;
		float x = Mth.clamp(cx - anchoEscalado / 2f, 2f, g.guiWidth() - anchoEscalado - 2f);
		float y = abajo - (font.lineHeight - 1) * escala;
		g.pose().pushMatrix();
		// Alineado a la rejilla de píxeles de pantalla.
		g.pose().translate(Math.round(x * escalaGui) / (float) escalaGui, Math.round(y * escalaGui) / (float) escalaGui);
		g.pose().scale(escala, escala);
		g.fill(-2, -2, ancho + 1, font.lineHeight - 1, 0xB0000000);
		g.text(font, texto, 0, 0, 0xFFFFFFFF, false);
		g.pose().popMatrix();
	}
}
