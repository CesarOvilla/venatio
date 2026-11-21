package dev.venatio.cliente.fichas;

import dev.venatio.cliente.Arte;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Todos los items que pueden salir en el cartón (lo que MCBingo mostraba en la pantalla de logros).
 * Los que están en el cartón actual llevan marco dorado; clic en uno abre su ficha. Los que el anfitrión quitó del pool
 * se ven apagados y tachados; un operador los quita o los vuelve a poner con clic derecho.
 */
public final class PantallaItemsPosibles extends Screen {
	private static final int CELDA = 18, BLANCO = Arte.CREMA, GRIS = Arte.GRIS, DORADO = Arte.ORO;

	private static final int ROJO_TACHADO = 0xFFE0473A;

	private record Entrada(String texto, ItemStack icono, boolean grupo, int opciones) {}

	private final @Nullable Screen padre;
	private final Set<Item> enCarton;
	private List<Entrada> entradas;
	private int columnas, x0, y0;
	private int versionCargada = -1;

	/** enCarton: items del cartón en juego, para resaltarlos (vacío si no hay partida). */
	public PantallaItemsPosibles(@Nullable Screen padre, Set<Item> enCarton) {
		super(Component.translatable("venatio.fichas.items_posibles"));
		this.padre = padre;
		this.enCarton = enCarton;
	}

	/** true cuando la lista ya llegó del servidor. */
	public boolean cargada() {
		return entradas != null;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.translatable("venatio.fichas.volver"), b -> onClose()).bounds(8, 6, 54, 20).build());
		if (entradas == null) FichasCliente.refrescarItemsPosibles();
		cargar();
	}

	@Override
	public void tick() {
		if (entradas == null || versionCargada != FichasCliente.version()) cargar();
	}

	private void cargar() {
		List<String> lista = FichasCliente.itemsPosibles();
		if (lista == null) return;
		versionCargada = FichasCliente.version();
		List<Entrada> nuevas = new ArrayList<>();
		for (String e : lista) {
			boolean grupo = e.startsWith("#");
			int opciones = grupo ? FichasCliente.itemsDelGrupo(Identifier.parse(e.substring(1))).size() : 1;
			nuevas.add(new Entrada(e, FichasCliente.icono(e), grupo, opciones));
		}
		entradas = nuevas;
		columnas = Math.max(1, Math.min(24, (width - 48) / CELDA));
		int filas = (entradas.size() + columnas - 1) / columnas;
		x0 = (width - columnas * CELDA) / 2;
		y0 = Math.max(50, (height - filas * CELDA) / 2 + 12);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(padre);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
		if (entradas == null) {
			Arte.panel(g, width / 2 - 110, height / 2 - 30, 220, 60);
			Arte.placa(g, font, title, width / 2, height / 2 - 40, Arte.ICONO_BINGO);
			g.centeredText(font, Component.translatable("venatio.fichas.cargando"), width / 2, height / 2 - 2, GRIS);
			super.extractRenderState(g, mx, my, a);
			return;
		}
		int filasMarco = (entradas.size() + columnas - 1) / columnas;
		Arte.panel(g, x0 - 12, y0 - 34, columnas * CELDA + 24, filasMarco * CELDA + 34 + (enCarton.isEmpty() ? 12 : 26));
		super.extractRenderState(g, mx, my, a);
		int quitadas = (int) entradas.stream().filter(e -> excluida(e)).count();
		String cuenta = quitadas == 0 ? " (" + entradas.size() + ")" : " (" + (entradas.size() - quitadas) + "/" + entradas.size() + ")";
		Arte.placa(g, font, title.copy().append(Component.literal(cuenta).withColor(GRIS)), width / 2, y0 - 42, Arte.ICONO_BINGO);
		g.centeredText(font, Component.translatable(FichasCliente.editable() ? "venatio.pool.ayuda_anfitrion" : "venatio.fichas.items_posibles.ayuda"),
			width / 2, y0 - 14, GRIS);

		Entrada sobre = null;
		for (int i = 0; i < entradas.size(); i++) {
			Entrada e = entradas.get(i);
			int x = x0 + (i % columnas) * CELDA, y = y0 + (i / columnas) * CELDA;
			boolean delCarton = enCarton.contains(e.icono.getItem());
			g.blitSprite(RenderPipelines.GUI_TEXTURED, delCarton ? Arte.CASILLA_MIA : Arte.CASILLA_VACIA, x, y, CELDA - 1, CELDA - 1,
				delCarton ? DORADO : 0xFFFFFFFF);
			g.item(e.icono, x + 1, y + 1);
			boolean encima = mx >= x && mx < x + CELDA && my >= y && my < y + CELDA;
			if (encima) sobre = e;
			if (excluida(e) || encima) {
				g.nextStratum();
				if (excluida(e)) tachar(g, x, y);
				if (encima) g.fill(x, y, x + 17, y + 17, 0x60FFFFFF);
			}
		}
		int filas = (entradas.size() + columnas - 1) / columnas;
		if (!enCarton.isEmpty()) {
			g.centeredText(font, Component.translatable("venatio.fichas.items_posibles.en_carton"), width / 2, y0 + filas * CELDA + 8, DORADO);
		}
		if (sobre != null) {
			List<Component> lineas = new ArrayList<>();
			lineas.add(sobre.icono.getHoverName());
			if (sobre.grupo) lineas.add(Component.translatable("venatio.fichas.items_posibles.grupo", sobre.opciones).withColor(GRIS));
			if (excluida(sobre)) lineas.add(Component.translatable("venatio.pool.excluido").withColor(ROJO_TACHADO));
			lineas.add(Component.translatable("venatio.fichas.clic_para_ver").withColor(GRIS));
			if (FichasCliente.editable()) {
				lineas.add(Component.translatable(excluida(sobre) ? "venatio.pool.clic_poner" : "venatio.pool.clic_quitar").withColor(Arte.ORO));
			}
			g.setComponentTooltipForNextFrame(font, lineas, mx, my);
		}
	}

	private static boolean excluida(Entrada e) {
		return FichasCliente.excluidas().contains(e.texto);
	}

	/** Quitada del pool: casilla oscurecida y una raya roja en diagonal. */
	private static void tachar(GuiGraphicsExtractor g, int x, int y) {
		g.fill(x, y, x + CELDA - 1, y + CELDA - 1, 0xA0140D08);
		for (int k = 1; k < CELDA - 2; k++) g.fill(x + k, y + CELDA - 2 - k, x + k + 1, y + CELDA - k, ROJO_TACHADO);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		if (entradas == null || event.button() > 1) return false;
		int col = (int) Math.floor((event.x() - x0) / CELDA), fila = (int) Math.floor((event.y() - y0) / CELDA);
		int i = fila * columnas + col;
		if (col < 0 || col >= columnas || fila < 0 || i >= entradas.size()) return false;
		if (event.button() == 1) {
			if (!FichasCliente.editable()) return false;
			FichasCliente.alternarExclusion(entradas.get(i).texto);
			return true;
		}
		minecraft.gui.setScreen(new PantallaFicha(this, entradas.get(i).icono.getItem()));
		return true;
	}

	/** Índice de una entrada del pool ("minecraft:diamond"), o -1; para las pruebas. */
	public int indiceDe(String entrada) {
		if (entradas == null) return -1;
		for (int i = 0; i < entradas.size(); i++) if (entradas.get(i).texto.equals(entrada)) return i;
		return -1;
	}

	/** Centro (en coordenadas de interfaz) de la casilla de un item, para las pruebas. */
	public int[] centroDe(int indice) {
		return new int[]{x0 + (indice % columnas) * CELDA + CELDA / 2, y0 + (indice / columnas) * CELDA + CELDA / 2};
	}
}
