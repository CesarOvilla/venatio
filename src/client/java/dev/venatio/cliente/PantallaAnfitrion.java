package dev.venatio.cliente;

import dev.venatio.juego.Modo;
import dev.venatio.red.PaquetesStats;
import dev.venatio.red.PaquetesStats.AccionPanel;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Panel del anfitrión (clic derecho en el atril del lobby): modo, tiempo, PvP, distancia, opciones, semilla,
 * equipos al azar e iniciar. Cada cambio se manda al servidor, que lo aplica como el comando equivalente
 * y avisa en el chat. Quien no es operador la ve en modo lectura.
 */
public final class PantallaAnfitrion extends Screen {
	private static final int BLANCO = Arte.CREMA;
	private static final int GRIS = Arte.GRIS;
	private static final int AMARILLO = Arte.AMARILLO;
	private static final int ROJO = Arte.ROJO;
	private static final int[] MINUTOS = {0, 5, 10, 15, 20, 25, 30, 45, 60, 90, 120};
	private static final int[] DISTANCIAS = {0, 100, 200, 300, 400, 500, 750, 1000, 1500, 2000, 3000, 5000};

	private PaquetesStats.ConfigPanel c;
	/** Semilla escrita y todavía no aplicada; sobrevive a los refrescos del servidor. */
	private String semillaEscrita;
	private Component aviso;
	private int equiposAzar = 2;
	private int ySemilla, xSemilla;
	/** Marco: se calcula en init() alrededor de los controles. */
	private int marcoX, marcoAncho, marcoAbajo;

	public PantallaAnfitrion(PaquetesStats.ConfigPanel c) {
		super(Component.translatable("venatio.stats.panel.titulo"));
		this.c = c;
	}

	/** El servidor mandó la configuración nueva (cambio propio o de otro anfitrión). */
	void actualizar(PaquetesStats.ConfigPanel nueva) {
		this.c = nueva;
		rebuildWidgets();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private boolean editable() {
		return c.editable() && !c.enPartida();
	}

	@Override
	protected void init() {
		int gap = 6;
		int col = Math.min(160, (width - 30) / 2);
		int x0 = width / 2 - col - gap / 2, x1 = width / 2 + gap / 2;
		int nOpciones = c.opciones().size();
		int filas = 2 + (nOpciones + 1) / 2 + 3;
		int y = 34;
		// En ventanas bajas los botones van pegados (paso 20) para que quepa la fila de Iniciar y Cerrar.
		int paso = Mth.clamp((height - y - 5) / filas, 20, 24);
		marcoX = x0 - 12;
		marcoAncho = 2 * col + gap + 24;
		marcoAbajo = y + (filas - 1) * paso + 20 + 8;
		boolean ed = editable();
		List<AbstractWidget> controles = new ArrayList<>();

		Modo modo = Modo.porId(c.modo());
		controles.add(addRenderableWidget(CycleButton.<Modo>builder(Modo::nombre, modo).withValues(Modo.values())
			.create(x0, y, col, 20, Component.translatable("venatio.stats.panel.modo"),
				(b, m) -> enviar(AccionPanel.MODO, m.id(), 0))));
		controles.add(addRenderableWidget(CycleButton.<Integer>builder(PantallaAnfitrion::textoMinutos, c.minutos())
			.withValues(valores(MINUTOS, c.minutos(), c.maxMinutos()))
			.create(x1, y, col, 20, Component.translatable("venatio.stats.panel.tiempo"),
				(b, m) -> enviar(AccionPanel.TIEMPO, "", m))));
		y += paso;

		controles.add(addRenderableWidget(siNo(c.pvp()).create(x0, y, col, 20, Component.translatable("venatio.stats.panel.pvp"),
			(b, v) -> enviar(AccionPanel.PVP, "", v ? 1 : 0))));
		controles.add(addRenderableWidget(CycleButton.<Integer>builder(d -> Component.translatable("venatio.stats.panel.bloques", d), c.distancia())
			.withValues(valores(DISTANCIAS, c.distancia(), c.maxDistancia()))
			.create(x1, y, col, 20, Component.translatable("venatio.stats.panel.distancia"),
				(b, d) -> enviar(AccionPanel.DISTANCIA, "", d))));
		y += paso;

		for (int i = 0; i < nOpciones; i++) {
			PaquetesStats.OpcionRed o = c.opciones().get(i);
			int x = i % 2 == 0 ? x0 : x1;
			controles.add(addRenderableWidget(siNo(o.valor()).create(x, y, col, 20, Component.translatable("venatio.opcion." + o.id()),
				(b, v) -> enviar(AccionPanel.OPCION, o.id(), v ? 1 : 0))));
			if (i % 2 == 1 || i == nOpciones - 1) y += paso;
		}

		// Semilla: etiqueta, campo de texto, Fijar y Aleatoria.
		Component etiqueta = Component.translatable("venatio.stats.panel.semilla");
		int anchoEtiqueta = font.width(etiqueta) + 6;
		ySemilla = y;
		xSemilla = x0;
		EditBox campo = new EditBox(font, x0 + anchoEtiqueta, y, col - anchoEtiqueta, 20, etiqueta);
		campo.setMaxLength(20);
		campo.setHint(Component.translatable("venatio.stats.panel.semilla_hint"));
		campo.setValue(semillaEscrita != null ? semillaEscrita : c.semillaFija() ? String.valueOf(c.semilla()) : "");
		campo.setResponder(s -> {
			semillaEscrita = s;
			aviso = null;
		});
		campo.setEditable(ed);
		controles.add(addRenderableWidget(campo));
		int medio = (col - 4) / 2;
		controles.add(addRenderableWidget(Button.builder(Component.translatable("venatio.stats.panel.fijar"), b -> fijarSemilla(campo.getValue()))
			.bounds(x1, y, medio, 20).build()));
		controles.add(addRenderableWidget(Button.builder(Component.translatable("venatio.stats.panel.aleatoria"), b -> {
			semillaEscrita = null;
			enviar(AccionPanel.SEMILLA, "aleatoria", 0);
		}).bounds(x1 + medio + 4, y, col - medio - 4, 20).build()));
		y += paso;

		controles.add(addRenderableWidget(CycleButton.<Integer>builder(n -> Component.literal(String.valueOf(n)), equiposAzar)
			.withValues(1, 2, 3, 4, 5, 6, 7, 8)
			.create(x0, y, col, 20, Component.translatable("venatio.stats.panel.equipos"), (b, n) -> equiposAzar = n)));
		controles.add(addRenderableWidget(Button.builder(Component.translatable("venatio.stats.panel.repartir"),
			b -> enviar(AccionPanel.ALEATORIOS, "", equiposAzar)).bounds(x1, y, col, 20).build()));
		y += paso;

		controles.add(addRenderableWidget(Button.builder(Component.translatable("venatio.stats.panel.iniciar"), b -> {
			enviar(AccionPanel.INICIAR, "", 0);
			onClose();
		}).bounds(x0, y, col, 20).build()));
		addRenderableWidget(Button.builder(Component.translatable("venatio.stats.panel.cerrar"), b -> onClose()).bounds(x1, y, col, 20).build());

		for (AbstractWidget w : controles) w.active = ed;
	}

	private static CycleButton.Builder<Boolean> siNo(boolean valor) {
		return CycleButton.booleanBuilder(Component.translatable("venatio.stats.panel.si"), Component.translatable("venatio.stats.panel.no"), valor);
	}

	private static Component textoMinutos(int m) {
		return m == 0 ? Component.translatable("venatio.stats.panel.sin_limite") : Component.translatable("venatio.stats.panel.minutos", m);
	}

	/** Valores fijos más el actual (si lo pusieron con un comando), sin pasar del máximo. */
	private static List<Integer> valores(int[] fijos, int actual, int max) {
		TreeSet<Integer> set = new TreeSet<>();
		for (int v : fijos) if (v <= max) set.add(v);
		set.add(actual);
		return new ArrayList<>(set);
	}

	private void fijarSemilla(String texto) {
		String t = texto.trim();
		if (t.isEmpty()) {
			semillaEscrita = null;
			enviar(AccionPanel.SEMILLA, "aleatoria", 0);
			return;
		}
		try {
			long s = Long.parseLong(t);
			semillaEscrita = null;
			enviar(AccionPanel.SEMILLA, "", s);
		} catch (NumberFormatException e) {
			aviso = Component.translatable("venatio.stats.panel.semilla_invalida");
		}
	}

	private void enviar(int tipo, String texto, long numero) {
		if (!editable()) return;
		aviso = null;
		ClientPlayNetworking.send(new AccionPanel(tipo, texto, numero));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		Arte.panel(g, marcoX, 12, marcoAncho, Math.min(height, marcoAbajo) - 12);
		super.extractRenderState(g, mouseX, mouseY, a);
		Arte.placa(g, font, title, width / 2, 2, Arte.ICONO_BINGO);
		Component estado;
		int color;
		if (aviso != null) {
			estado = aviso;
			color = ROJO;
		} else if (!c.editable()) {
			estado = Component.translatable("venatio.stats.panel.solo_lectura");
			color = AMARILLO;
		} else if (c.enPartida()) {
			estado = Component.translatable("venatio.stats.panel.en_partida");
			color = AMARILLO;
		} else {
			estado = Component.translatable("venatio.stats.panel.ayuda");
			color = GRIS;
		}
		g.centeredText(font, estado, width / 2, 24, color);
		g.text(font, Component.translatable("venatio.stats.panel.semilla"), xSemilla, ySemilla + 6, editable() ? BLANCO : GRIS, true);
	}
}
