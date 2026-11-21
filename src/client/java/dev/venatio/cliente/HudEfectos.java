package dev.venatio.cliente;

import dev.venatio.Venatio;
import dev.venatio.juego.Modo;
import dev.venatio.red.Paquetes;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Efectos a pantalla completa del HUD: la cuenta atrás con números que escalan, el "¡A cazar!",
 * y el banner con confeti de bingo o de victoria (cuando no está abierta la pantalla final, que dibuja el suyo).
 */
public final class HudEfectos {
	private HudEfectos() {}

	static void registrar() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.TITLE_AND_SUBTITLE,
			Identifier.fromNamespaceAndPath(Venatio.MOD_ID, "efectos"), HudEfectos::dibujar);
		ClientTickEvents.END_CLIENT_TICK.register(Animaciones::alTick);
		SonidosAyudas.registrar();
	}

	private static void dibujar(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		Paquetes.Estado e = EstadoCliente.estado;
		if (e == null || mc.player == null) return;
		Font font = mc.font;
		int ancho = g.guiWidth(), alto = g.guiHeight();

		if (e.fase() == Paquetes.Estado.CUENTA_ATRAS) cuentaAtras(g, font, e, ancho, alto);
		float ya = Animaciones.progreso(Animaciones.yaInicio, Animaciones.YA_MS);
		if (ya >= 0) aCazar(g, font, ya, ancho, alto);

		Animaciones.Banner b = Animaciones.banner;
		if (b != null && !(mc.gui.screen() instanceof PantallaFinal)) {
			long t = Animaciones.ahora() - b.inicio();
			if (t <= b.duracion()) {
				Arte.banner(g, font, ancho, t, b.duracion(), b.equipo(), Arte.textosBanner(b.final_(), b.equipo(), e.miEquipo(), e.ganador()), 34, false);
			}
			Arte.confeti(g, ancho, alto, b.inicio(), b.equipo(), piezas(b, e));
		}
	}

	/** Más confeti si ganó mi equipo; menos en un bingo a media partida. */
	static int piezas(Animaciones.Banner b, Paquetes.Estado e) {
		if (!b.final_()) return 45;
		return e != null && b.equipo() == e.miEquipo() ? 130 : 70;
	}

	/** Número grande que entra pasado de tamaño, se asienta y se desvanece antes del siguiente. */
	private static void cuentaAtras(GuiGraphicsExtractor g, Font font, Paquetes.Estado e, int ancho, int alto) {
		int s = Animaciones.segundoCuenta();
		if (s <= 0) return;
		float t = Animaciones.faseCuenta();
		float escala = 5f + 3.5f * (1 - Animaciones.suaveFuera(Math.min(1f, t / 0.3f)));
		float alfa = t < 0.08f ? t / 0.08f : t > 0.72f ? Math.max(0f, 1 - (t - 0.72f) / 0.28f) : 1f;
		if (alfa < 0.04f) return;
		// Los tres últimos, en cobre claro: se nota que ya casi.
		int color = s <= 3 ? Arte.COBRE : Arte.ORO;
		int cy = alto / 2 - 46;
		Arte.textoGrande(g, font, Component.literal(String.valueOf(s)), ancho / 2f, cy, escala, color, alfa);

		Component modo = Component.translatable("venatio.cuenta.subtitulo", Modo.porId(e.modo()).nombre());
		int medio = font.width(modo) / 2 + 6;
		Arte.fila(g, ancho / 2 - medio, cy + 30, medio * 2, 14, false);
		g.centeredText(font, modo, ancho / 2, cy + 33, Arte.CREMA);
	}

	/** "¡A cazar!": entra con rebote, aguanta y se va creciendo un poco mientras se desvanece. */
	private static void aCazar(GuiGraphicsExtractor g, Font font, float t, int ancho, int alto) {
		Component texto = Component.translatable("venatio.cuenta.ya");
		float entra = Math.min(1f, t / 0.22f);
		float sale = t > 0.72f ? (t - 0.72f) / 0.28f : 0f;
		float escala = 4f * Animaciones.rebote(entra) * (1f + 0.25f * sale);
		float alfa = Math.min(1f, entra * 2) * (1 - sale);
		if (alfa < 0.04f) return;
		Arte.textoGrande(g, font, texto, ancho / 2f, alto / 2f - 46, escala, Arte.ORO_CLARO, alfa);
	}

}
