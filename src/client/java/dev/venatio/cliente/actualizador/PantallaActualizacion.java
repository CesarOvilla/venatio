package dev.venatio.cliente.actualizador;

import dev.venatio.Venatio;
import dev.venatio.cliente.Arte;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

/** "Hay una versión nueva": la baja, deja listo el cambio de archivos y cierra Minecraft para aplicarlo. */
public final class PantallaActualizacion extends Screen {
	private enum Fase { PREGUNTA, MANUAL, DESCARGANDO, ERROR }

	private static final int ANCHO = 300, ALTO = 124;

	private final @Nullable Screen padre;
	private final Paquete paquete;
	private final @Nullable Actualizador actualizador;
	private final Path mods, raiz;
	private Fase fase;
	private volatile int hechos;
	private volatile boolean listo, fallo;
	private boolean copiado;

	public PantallaActualizacion(@Nullable Screen padre, Paquete paquete, @Nullable Actualizador actualizador, Path mods, Path raiz) {
		super(Component.translatable("venatio.actualizador.titulo"));
		this.padre = padre;
		this.paquete = paquete;
		this.actualizador = actualizador;
		this.mods = mods;
		this.raiz = raiz;
		this.fase = ActualizadorCliente.requiereInstalador(paquete) ? Fase.MANUAL : Fase.PREGUNTA;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return fase != Fase.DESCARGANDO;
	}

	@Override
	protected void init() {
		int y = height / 2 + ALTO / 2 - 30, x = width / 2;
		switch (fase) {
			case PREGUNTA -> dos(x, y, "venatio.actualizador.actualizar", this::actualizar);
			case ERROR -> dos(x, y, "venatio.actualizador.reintentar", this::actualizar);
			case MANUAL -> dos(x, y, copiado ? "venatio.actualizador.copiado" : "venatio.actualizador.copiar", () -> {
				minecraft.keyboardHandler.setClipboard(ActualizadorCliente.comandoInstalador());
				copiado = true;
				rebuildWidgets();
			});
			case DESCARGANDO -> {}
		}
	}

	private void dos(int x, int y, String clave, Runnable accion) {
		addRenderableWidget(Button.builder(Component.translatable(clave), b -> accion.run()).bounds(x - 136, y, 150, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("venatio.actualizador.ahora_no"), b -> onClose()).bounds(x + 20, y, 116, 20).build());
	}

	private void actualizar() {
		if (actualizador == null) return;
		fase = Fase.DESCARGANDO;
		hechos = 0;
		fallo = false;
		rebuildWidgets();
		Thread hilo = new Thread(() -> {
			try {
				actualizador.preparar(paquete, mods, raiz, n -> hechos = n);
				Actualizador.lanzarAplicador(raiz, mods, ProcessHandle.current().pid());
				listo = true;
			} catch (Exception e) {
				Venatio.LOG.warn("No se pudo actualizar", e);
				fallo = true;
			}
		}, "Venatio-actualizacion");
		hilo.setDaemon(true);
		hilo.start();
	}

	@Override
	public void tick() {
		if (listo) {
			minecraft.stop();
		} else if (fallo && fase == Fase.DESCARGANDO) {
			fase = Fase.ERROR;
			rebuildWidgets();
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(padre);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
		int x0 = (width - ANCHO) / 2, y0 = (height - ALTO) / 2;
		Arte.panel(g, x0, y0, ANCHO, ALTO);
		Arte.placa(g, font, title, width / 2, y0 - 8, Arte.ICONO_BINGO);
		g.centeredText(font, Component.translatable("venatio.actualizador.versiones", Venatio.version(), paquete.version()), width / 2, y0 + 24, Arte.ORO);
		Component texto = switch (fase) {
			case PREGUNTA -> listo ? Component.translatable("venatio.actualizador.cerrando") : Component.translatable("venatio.actualizador.pregunta");
			case MANUAL -> Component.translatable("venatio.actualizador.manual");
			case ERROR -> Component.translatable("venatio.actualizador.error");
			case DESCARGANDO -> listo ? Component.translatable("venatio.actualizador.cerrando")
				: Component.translatable("venatio.actualizador.descargando", hechos, paquete.mods().size());
		};
		g.textWithWordWrap(font, texto, x0 + 16, y0 + 42, ANCHO - 32, Arte.CREMA);
		super.extractRenderState(g, mx, my, a);
	}
}
