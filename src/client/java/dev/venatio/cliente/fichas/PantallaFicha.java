package dev.venatio.cliente.fichas;

import com.mojang.blaze3d.platform.InputConstants;
import dev.venatio.carton.Reto;
import dev.venatio.carton.Retos;
import dev.venatio.red.PaquetesFicha.Destilacion;
import dev.venatio.red.PaquetesFicha.Ficha;
import dev.venatio.red.PaquetesFicha.Fuente;
import dev.venatio.cliente.Arte;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Ficha de un item: recetas dibujadas como la estación real (con páginas), de dónde sale (con "Guíame" para mobs,
 * estructuras y biomas) y la pista. Clic en un ingrediente abre su ficha; "Volver" regresa por el mismo camino.
 *
 * También la de una casilla de logro: cómo lograrlo, dónde (biomas, el mob, estructuras, con "Guíame") y la receta del
 * item que hace falta (la cama para dormir, la comida para comer…).
 */
public final class PantallaFicha extends Screen {
	private static final Identifier RANURA = Identifier.withDefaultNamespace("container/slot");
	private static final Identifier FLECHA = Identifier.withDefaultNamespace("container/furnace/burn_progress");
	private static final Identifier FUEGO = Identifier.withDefaultNamespace("container/furnace/lit_progress");
	private static final int BLANCO = 0xFFFFFFFF, GRIS = 0xFFAAAAAA, GRIS_LUGARES = 0xFF9C9C9C, AMARILLO = 0xFFF2E3A0,
		TEXTO_CAJA = 0xFF404040, VERDE = 0xFF2F6B2F, VERDE_CLARO = 0xFF3F8F3F;
	private static final int FILA = 22, ANCHO_MAX = 470;
	/** Margen inferior: el marco de la pantalla y un respiro. */
	private static final int ABAJO = 10;

	/** Lo que se está viendo: un item, o un logro (con su item relacionado, que puede faltar). */
	private record Vista(@Nullable Item item, @Nullable Reto reto) {}

	private final @Nullable Screen padre;
	private final Deque<Vista> historial = new ArrayDeque<>();
	private @Nullable Item item;
	private @Nullable Reto reto;
	private @Nullable Ficha ficha;
	private List<RecetaVista> recetas = List.of();
	private List<ItemStack> grupo = List.of();
	private List<FormattedCharSequence> pista = List.of();
	private int pagina;
	private double scroll;
	private @Nullable Component aviso;
	private long avisoHasta;

	private Button anterior, siguiente;
	// Disposición, recalculada al cambiar de item o de tamaño.
	private int x0, ancho, yPista, yGrupo, yColumnas, xCaja, yCaja, xLista, anchoLista, yLista;

	public PantallaFicha(@Nullable Screen padre, Item item) {
		super(Component.translatable("venatio.fichas.titulo"));
		this.padre = padre;
		this.item = item;
	}

	/** Ficha de una casilla de logro (id del logro). */
	public static PantallaFicha deReto(@Nullable Screen padre, String id) {
		Reto r = Retos.porId(id);
		PantallaFicha p = new PantallaFicha(padre, r == null ? Items.BARRIER : r.itemRelacionado());
		p.reto = r;
		return p;
	}

	/** Item cuya ficha se está mostrando (en un logro, su item relacionado; puede ser null). */
	public Item item() {
		return item;
	}

	/** Logro cuya ficha se está mostrando, o null. */
	public @Nullable Reto reto() {
		return reto;
	}

	/** Logro sin item relacionado: no hay columna de recetas y la lista ocupa todo el ancho. */
	private boolean sinRecetas() {
		return reto != null && item == null;
	}

	/** Item para los iconos de las fuentes de bloque. */
	private Item itemFuentes() {
		return item != null ? item : reto != null ? reto.itemIcono() : Items.BARRIER;
	}

	/** true cuando la ficha ya llegó del servidor. */
	public boolean cargada() {
		return ficha != null;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		ancho = Math.min(width - 16, ANCHO_MAX);
		x0 = (width - ancho) / 2;
		addRenderableWidget(Button.builder(Component.translatable("venatio.fichas.volver"), b -> volver()).bounds(x0, 6, 54, 20).build());
		anterior = addRenderableWidget(Button.builder(Component.literal("<"), b -> cambiarPagina(-1)).bounds(0, 0, 16, 16).build());
		siguiente = addRenderableWidget(Button.builder(Component.literal(">"), b -> cambiarPagina(1)).bounds(0, 0, 16, 16).build());
		cargar();
	}

	@Override
	public void tick() {
		if (ficha == null) cargar();
	}

	private void cargar() {
		ficha = reto != null ? FichasCliente.fichaReto(reto.id()) : FichasCliente.ficha(item);
		recetas = List.of();
		grupo = List.of();
		pista = List.of();
		if (ficha != null && minecraft.level != null) {
			ContextMap ctx = SlotDisplayContext.fromLevel(minecraft.level);
			List<RecetaVista> lista = new ArrayList<>();
			for (RecipeDisplay d : ficha.recetas()) lista.add(RecetaVista.de(d, ctx));
			for (Destilacion d : ficha.destilaciones()) lista.add(RecetaVista.de(d, ctx));
			recetas = lista;
			grupo = ficha.grupo().map(FichasCliente::itemsDelGrupo).orElse(List.of());
			if (reto != null) {
				List<FormattedCharSequence> lineas = new ArrayList<>(font.split(reto.como(), ancho));
				if (!ficha.pista().isEmpty()) lineas.addAll(font.split(Component.literal(ficha.pista()), ancho));
				pista = lineas;
			} else if (!ficha.pista().isEmpty()) {
				pista = font.split(Component.literal(ficha.pista()), ancho);
			}
		}
		pagina = Mth.clamp(pagina, 0, Math.max(0, recetas.size() - 1));
		reubicar();
	}

	private void reubicar() {
		int y = 32;
		yPista = y;
		y += pista.size() * (font.lineHeight + 1) + (pista.isEmpty() ? 0 : 6);
		yGrupo = y;
		if (!grupo.isEmpty()) y += 12 + filasGrupo() * 18 + 4;
		yColumnas = y;
		xCaja = x0;
		yCaja = yColumnas + 12;
		xLista = sinRecetas() ? x0 : x0 + RecetaVista.ANCHO + 14;
		anchoLista = sinRecetas() ? ancho : ancho - RecetaVista.ANCHO - 14;
		yLista = yColumnas + 12;
		int yPaginas = yCaja + RecetaVista.ALTO + 4;
		anterior.setX(xCaja);
		anterior.setY(yPaginas);
		siguiente.setX(xCaja + RecetaVista.ANCHO - 16);
		siguiente.setY(yPaginas);
		anterior.visible = siguiente.visible = recetas.size() > 1 && !sinRecetas();
		scroll = Mth.clamp(scroll, 0, maxScroll());
	}

	private int porFilaGrupo() {
		return Math.max(1, (ancho - 4) / 18);
	}

	private int filasGrupo() {
		return (grupo.size() + porFilaGrupo() - 1) / porFilaGrupo();
	}

	// ------------------------------------------------------------------------------------------------ navegación

	private void navegar(Item nuevo) {
		if (nuevo == item && reto == null) return;
		historial.push(new Vista(item, reto));
		item = nuevo;
		reto = null;
		pagina = 0;
		scroll = 0;
		cargar();
	}

	private void volver() {
		if (historial.isEmpty()) {
			onClose();
			return;
		}
		Vista previa = historial.pop();
		item = previa.item();
		reto = previa.reto();
		pagina = 0;
		scroll = 0;
		cargar();
	}

	private void cambiarPagina(int delta) {
		if (recetas.isEmpty()) return;
		pagina = Math.floorMod(pagina + delta, recetas.size());
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(padre);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_BACKSPACE) {
			volver();
			return true;
		}
		return super.keyPressed(event);
	}

	// ------------------------------------------------------------------------------------------------ dibujo

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
		Arte.panel(g, x0 - 8, 0, ancho + 16, height);
		super.extractRenderState(g, mx, my, a);
		ItemStack stack = reto != null ? reto.pila() : new ItemStack(item);
		g.item(stack, x0 + 60, 8);
		g.text(font, stack.getHoverName(), x0 + 80, 12, Arte.ORO, true);
		if (reto != null) {
			Component etiqueta = Component.translatable("venatio.reto.etiqueta");
			g.text(font, etiqueta, x0 + ancho - font.width(etiqueta), 12, Arte.COBRE, true);
		}
		if (ficha == null) {
			g.centeredText(font, Component.translatable("venatio.fichas.cargando"), width / 2, height / 2, GRIS);
			return;
		}

		int y = yPista;
		for (FormattedCharSequence linea : pista) {
			g.text(font, linea, x0, y, AMARILLO, true);
			y += font.lineHeight + 1;
		}
		ItemStack sobre = dibujarGrupo(g, mx, my);
		ItemStack enReceta = sinRecetas() ? ItemStack.EMPTY : dibujarRecetas(g, mx, my);
		if (sobre.isEmpty()) sobre = enReceta;
		dibujarFuentes(g, mx, my);

		if (aviso != null && Util.getMillis() < avisoHasta) {
			g.textWithWordWrap(font, aviso, xCaja, yCaja + RecetaVista.ALTO + 36, RecetaVista.ANCHO, AMARILLO, true);
		}
		if (!sobre.isEmpty()) g.setTooltipForNextFrame(font, sobre, mx, my);
	}

	/** Casillas de "cualquier X": todas las variantes que valen. Devuelve la que está bajo el ratón. */
	private ItemStack dibujarGrupo(GuiGraphicsExtractor g, int mx, int my) {
		if (grupo.isEmpty()) return ItemStack.EMPTY;
		g.text(font, Component.translatable("venatio.fichas.grupo"), x0, yGrupo, GRIS, true);
		ItemStack sobre = ItemStack.EMPTY;
		for (int i = 0; i < grupo.size(); i++) {
			int x = x0 + (i % porFilaGrupo()) * 18, y = yGrupo + 11 + (i / porFilaGrupo()) * 18;
			g.blitSprite(RenderPipelines.GUI_TEXTURED, RANURA, x, y, 18, 18);
			g.item(grupo.get(i), x + 1, y + 1);
			if (dentro(mx, my, x, y, 18, 18)) {
				sobre = grupo.get(i);
				resaltar(g, x + 1, y + 1, 16);
			}
		}
		return sobre;
	}

	private ItemStack dibujarRecetas(GuiGraphicsExtractor g, int mx, int my) {
		Component tituloRecetas = reto != null ? Component.translatable("venatio.reto.ficha.receta", new ItemStack(item).getHoverName())
			: Component.translatable("venatio.fichas.como_se_fabrica");
		g.text(font, tituloRecetas, xCaja, yColumnas, Arte.ORO, true);
		if (recetas.isEmpty()) {
			g.textWithWordWrap(font, Component.translatable("venatio.fichas.no_se_fabrica"), xCaja, yCaja + 2, RecetaVista.ANCHO, GRIS, true);
			return ItemStack.EMPTY;
		}
		RecetaVista r = recetas.get(pagina);
		caja(g, xCaja, yCaja, RecetaVista.ANCHO, RecetaVista.ALTO);
		ItemStack sobre = ItemStack.EMPTY;

		// Estación: nombre arriba a la izquierda, icono arriba a la derecha (clic abre su ficha).
		if (!r.estacion.isEmpty()) {
			String nombre = recortar(r.estacion.getHoverName().getString(), RecetaVista.ANCHO - 30);
			g.text(font, nombre, xCaja + 7, yCaja + 6, TEXTO_CAJA, false);
			int ex = xCaja + RecetaVista.ANCHO - 21, ey = yCaja + 3;
			g.item(r.estacion, ex, ey);
			if (dentro(mx, my, ex, ey, 16, 16)) {
				sobre = r.estacion;
				resaltar(g, ex, ey, 16);
			}
		}
		g.blitSprite(RenderPipelines.GUI_TEXTURED, FLECHA, xCaja + r.flechaX, yCaja + r.flechaY, 24, 16);
		if (r.fuegoX >= 0) g.blitSprite(RenderPipelines.GUI_TEXTURED, FUEGO, xCaja + r.fuegoX, yCaja + r.fuegoY, 14, 14);

		for (RecetaVista.Ranura ranura : r.ranuras) {
			int x = xCaja + ranura.x(), y = yCaja + ranura.y(), lado = ranura.lado();
			if (ranura.resultado()) ranuraGrande(g, x, y);
			else g.blitSprite(RenderPipelines.GUI_TEXTURED, RANURA, x, y, 18, 18);
			int off = (lado - 16) / 2;
			ItemStack actual = ranura.actual();
			if (!actual.isEmpty()) {
				g.item(actual, x + off, y + off);
				g.itemDecorations(font, actual, x + off, y + off);
			}
			if (dentro(mx, my, x, y, lado, lado)) {
				resaltar(g, x + off, y + off, 16);
				if (!actual.isEmpty()) sobre = actual;
			}
		}

		int yPie = yCaja + RecetaVista.ALTO + 8;
		if (recetas.size() > 1) {
			Component paginas = Component.translatable("venatio.fichas.receta.pagina", pagina + 1, recetas.size());
			g.centeredText(font, paginas, xCaja + RecetaVista.ANCHO / 2, yPie, BLANCO);
		}
		if (r.nota != null) g.centeredText(font, r.nota, xCaja + RecetaVista.ANCHO / 2, recetas.size() > 1 ? yPie + 12 : yPie, GRIS);
		return sobre;
	}

	private void dibujarFuentes(GuiGraphicsExtractor g, int mx, int my) {
		g.text(font, Component.translatable(reto != null ? "venatio.reto.ficha.donde" : "venatio.fichas.de_donde_sale"), xLista, yColumnas, Arte.ORO, true);
		List<Fuente> fuentes = ficha.fuentes();
		if (fuentes.isEmpty()) {
			Component texto = Component.translatable(reto != null ? "venatio.reto.ficha.sin_lugares"
				: recetas.isEmpty() ? "venatio.fichas.sin_fuentes" : "venatio.fichas.solo_fabricando");
			g.textWithWordWrap(font, texto, xLista, yLista + 2, anchoLista, GRIS, true);
			return;
		}
		int abajo = height - ABAJO;
		g.enableScissor(xLista, yLista, xLista + anchoLista, abajo);
		List<Component> tooltip = null;
		for (int i = 0; i < fuentes.size(); i++) {
			int y = yLista + i * FILA - (int) scroll;
			if (y + FILA < yLista || y > abajo) continue;
			Fuente f = fuentes.get(i);
			boolean sobreFila = dentro(mx, my, xLista, Math.max(y, yLista), anchoLista, Math.min(y + FILA, abajo) - Math.max(y, yLista));
			if (sobreFila) g.fill(xLista, y, xLista + anchoLista, y + FILA - 1, 0x30FFFFFF);
			g.item(VistaFuente.icono(f, itemFuentes()), xLista + 2, y + 3);

			int anchoTexto = anchoLista - 24;
			int botonX = -1;
			if (VistaFuente.tipoGuia(f) >= 0) {
				int bw = font.width(Component.translatable("venatio.fichas.guiame")) + 10;
				botonX = xLista + anchoLista - bw - 2;
				anchoTexto -= bw + 4;
				boolean sobreBoton = sobreFila && dentro(mx, my, botonX, y + 4, bw, 14);
				g.fill(botonX, y + 4, botonX + bw, y + 18, 0xFF000000);
				g.fill(botonX + 1, y + 5, botonX + bw - 1, y + 17, sobreBoton ? VERDE_CLARO : VERDE);
				g.centeredText(font, Component.translatable("venatio.fichas.guiame"), botonX + bw / 2, y + 7, BLANCO);
			}
			Component nombre = VistaFuente.nombre(f);
			Component modo = VistaFuente.modo(f);
			List<Component> lugares = VistaFuente.lugares(f);
			int tx = xLista + 22;
			if (lugares.isEmpty()) {
				g.text(font, recortar(nombre.copy().append(Component.literal(" · ").append(modo).withColor(GRIS)), anchoTexto), tx, y + 7, BLANCO, true);
			} else {
				g.text(font, recortar(nombre.copy().append(Component.literal(" · ").append(modo).withColor(GRIS)), anchoTexto), tx, y + 2, BLANCO, true);
				Component donde = Component.translatable("venatio.fichas.aparece_en", unir(lugares));
				g.text(font, recortar(donde, anchoTexto), tx, y + 12, GRIS_LUGARES, true);
			}
			if (sobreFila && (botonX < 0 || mx < botonX)) {
				tooltip = new ArrayList<>();
				tooltip.add(nombre);
				tooltip.add(modo.copy().withColor(GRIS));
				if (!lugares.isEmpty()) tooltip.add(Component.translatable("venatio.fichas.aparece_en", unir(lugares)).withColor(GRIS));
			}
		}
		g.disableScissor();

		int total = fuentes.size() * FILA, visible = abajo - yLista;
		if (total > visible) {
			int barra = Math.max(12, visible * visible / total);
			int yb = yLista + (int) ((visible - barra) * scroll / maxScroll());
			g.fill(xLista + anchoLista + 2, yLista, xLista + anchoLista + 4, abajo, 0x40FFFFFF);
			g.fill(xLista + anchoLista + 2, yb, xLista + anchoLista + 4, yb + barra, 0xC0FFFFFF);
		}
		if (tooltip != null) {
			List<FormattedCharSequence> lineas = new ArrayList<>();
			for (Component c : tooltip) lineas.addAll(font.split(c, 220));
			g.setTooltipForNextFrame(lineas, mx, my);
		}
	}

	private static Component unir(List<Component> partes) {
		var c = Component.empty();
		for (int i = 0; i < partes.size(); i++) {
			if (i > 0) c.append(", ");
			c.append(partes.get(i));
		}
		return c;
	}

	/** Fondo gris con relieve, como las interfaces de contenedores. */
	private static void caja(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, 0xFF000000);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFC6C6C6);
		g.fill(x + 1, y + 1, x + w - 2, y + 2, 0xFFFFFFFF);
		g.fill(x + 1, y + 1, x + 2, y + h - 2, 0xFFFFFFFF);
		g.fill(x + 2, y + h - 2, x + w - 1, y + h - 1, 0xFF555555);
		g.fill(x + w - 2, y + 2, x + w - 1, y + h - 1, 0xFF555555);
	}

	/** Ranura de resultado de 26×26, como la de la mesa de trabajo. */
	private static void ranuraGrande(GuiGraphicsExtractor g, int x, int y) {
		g.fill(x, y, x + 26, y + 26, 0xFF8B8B8B);
		g.fill(x, y, x + 25, y + 1, 0xFF373737);
		g.fill(x, y, x + 1, y + 25, 0xFF373737);
		g.fill(x + 1, y + 25, x + 26, y + 26, 0xFFFFFFFF);
		g.fill(x + 25, y + 1, x + 26, y + 26, 0xFFFFFFFF);
	}

	private static void resaltar(GuiGraphicsExtractor g, int x, int y, int lado) {
		g.nextStratum();
		g.fill(x, y, x + lado, y + lado, 0x80FFFFFF);
	}

	private String recortar(String s, int ancho) {
		if (font.width(s) <= ancho) return s;
		return font.plainSubstrByWidth(s, Math.max(0, ancho - font.width("…"))) + "…";
	}

	private FormattedCharSequence recortar(Component c, int ancho) {
		if (font.width(c) <= ancho) return c.getVisualOrderText();
		FormattedText corto = font.substrByWidth(c, Math.max(0, ancho - font.width("…")));
		return Language.getInstance().getVisualOrder(FormattedText.composite(corto, FormattedText.of("…")));
	}

	private static boolean dentro(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	// ------------------------------------------------------------------------------------------------ ratón

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || ficha == null) return false;
		double mx = event.x(), my = event.y();

		for (int i = 0; i < grupo.size(); i++) {
			int x = x0 + (i % porFilaGrupo()) * 18, y = yGrupo + 11 + (i / porFilaGrupo()) * 18;
			if (dentro(mx, my, x, y, 18, 18)) {
				navegar(grupo.get(i).getItem());
				return true;
			}
		}
		if (!recetas.isEmpty() && !sinRecetas()) {
			RecetaVista r = recetas.get(pagina);
			if (!r.estacion.isEmpty() && dentro(mx, my, xCaja + RecetaVista.ANCHO - 21, yCaja + 3, 16, 16)) {
				navegar(r.estacion.getItem());
				return true;
			}
			for (RecetaVista.Ranura ranura : r.ranuras) {
				if (ranura.resultado() || !dentro(mx, my, xCaja + ranura.x(), yCaja + ranura.y(), ranura.lado(), ranura.lado())) continue;
				ItemStack actual = ranura.actual();
				if (!actual.isEmpty()) navegar(actual.getItem());
				return true;
			}
		}
		int abajo = height - ABAJO;
		if (dentro(mx, my, xLista, yLista, anchoLista, abajo - yLista)) {
			int i = (int) ((my - yLista + scroll) / FILA);
			List<Fuente> fuentes = ficha.fuentes();
			if (i >= 0 && i < fuentes.size()) {
				Fuente f = fuentes.get(i);
				int tipo = VistaFuente.tipoGuia(f);
				int bw = font.width(Component.translatable("venatio.fichas.guiame")) + 10;
				int y = yLista + i * FILA - (int) scroll;
				if (tipo >= 0 && dentro(mx, my, xLista + anchoLista - bw - 2, y + 4, bw, 14)) {
					pedirGuia(tipo, f);
					return true;
				}
			}
		}
		return false;
	}

	private void pedirGuia(int tipo, Fuente f) {
		Optional<Identifier> objetivo = f.guia();
		if (objetivo.isEmpty()) return;
		boolean enviada = FichasCliente.pedirGuia(tipo, objetivo.get());
		aviso = enviada ? Component.translatable("venatio.fichas.guia_pedida", VistaFuente.nombre(f))
			: Component.translatable("venatio.fichas.guia_no_disponible");
		avisoHasta = Util.getMillis() + 4000;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (ficha != null && x >= xLista) {
			scroll = Mth.clamp(scroll - scrollY * FILA, 0, maxScroll());
			return true;
		}
		if (recetas.size() > 1 && !sinRecetas() && dentro(x, y, xCaja, yCaja, RecetaVista.ANCHO, RecetaVista.ALTO)) {
			cambiarPagina(scrollY > 0 ? -1 : 1);
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	private double maxScroll() {
		if (ficha == null) return 0;
		return Math.max(0, ficha.fuentes().size() * FILA - (height - ABAJO - yLista));
	}

	// ------------------------------------------------------------------------------------------------ pruebas

	/** Centro (en coordenadas de interfaz) del botón "Guíame" de una fila de "De dónde sale", si tiene. */
	public Optional<int[]> centroBotonGuia(int fila) {
		if (ficha == null || fila >= ficha.fuentes().size() || VistaFuente.tipoGuia(ficha.fuentes().get(fila)) < 0) return Optional.empty();
		int bw = font.width(Component.translatable("venatio.fichas.guiame")) + 10;
		int y = yLista + fila * FILA - (int) scroll;
		return Optional.of(new int[]{xLista + anchoLista - 2 - bw / 2, y + 11});
	}

	/** Centro (en coordenadas de interfaz) de la primera ranura de ingrediente que muestra este item, si hay. */
	public Optional<int[]> centroDeIngrediente(Item ingrediente) {
		if (recetas.isEmpty()) return Optional.empty();
		for (RecetaVista.Ranura r : recetas.get(pagina).ranuras) {
			if (!r.resultado() && r.actual().is(ingrediente)) {
				return Optional.of(new int[]{xCaja + r.x() + r.lado() / 2, yCaja + r.y() + r.lado() / 2});
			}
		}
		return Optional.empty();
	}
}
