package dev.venatio.cliente;

import dev.venatio.ayudas.TipoAyuda;
import dev.venatio.red.PaquetesAyudas;
import dev.venatio.red.PaquetesTienda;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Elegir el objetivo de una guía: lista con búsqueda por texto. Clic selecciona; doble clic, Enter o el botón compran. */
public final class PantallaObjetivos extends Screen {
	private static final int ANCHO = 280;
	private static final int FILA = 18;
	private static final int Y_LISTA = 56;
	private static final int BLANCO = Arte.CREMA;
	private static final int GRIS = Arte.GRIS_OSCURO;
	private static final int ROJO = Arte.ROJO;
	private static final int AMARILLO = Arte.AMARILLO;

	private final TipoAyuda tipo;
	private final Screen padre;
	private final List<CatalogoObjetivos.Entrada> todas;
	private List<CatalogoObjetivos.Entrada> filtradas;
	private EditBox buscador;
	private Button comprar;
	private int seleccion;
	private double desplazamiento;
	private int x0, altoLista;

	public PantallaObjetivos(TipoAyuda tipo, Screen padre) {
		super(Component.translatable("venatio.ayudas.objetivos.titulo." + tipo.id));
		this.tipo = tipo;
		this.padre = padre;
		this.todas = CatalogoObjetivos.de(tipo, Minecraft.getInstance());
		this.filtradas = todas;
		this.seleccion = todas.isEmpty() ? -1 : 0;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		x0 = (width - ANCHO) / 2;
		altoLista = Math.max(FILA * 3, (height - Y_LISTA - 60) / FILA * FILA);
		String previo = buscador == null ? "" : buscador.getValue();
		buscador = new EditBox(font, x0, 32, ANCHO, 18, Component.translatable("venatio.ayudas.objetivos.buscar"));
		buscador.setHint(Component.translatable("venatio.ayudas.objetivos.buscar").withStyle(EditBox.SEARCH_HINT_STYLE));
		buscador.setValue(previo);
		buscador.setResponder(this::filtrar);
		addRenderableWidget(buscador);

		int yBotones = Y_LISTA + altoLista + 26;
		addRenderableWidget(Button.builder(Component.translatable("venatio.ayudas.objetivos.volver"), b -> minecraft.gui.setScreen(padre))
			.bounds(width / 2 - 104, yBotones, 100, 20).build());
		comprar = addRenderableWidget(Button.builder(Component.translatable("venatio.ayudas.objetivos.comprar"), b -> comprar())
			.bounds(width / 2 + 4, yBotones, 100, 20).build());
		actualizarBoton();
	}

	/** Se escribe directo en el buscador al abrir la pantalla. */
	@Override
	protected void setInitialFocus() {
		setInitialFocus(buscador);
	}

	/** Posición de un objetivo en la lista filtrada, o -1 (para pruebas). */
	public int indiceDe(Identifier id) {
		for (int i = 0; i < filtradas.size(); i++) if (filtradas.get(i).id().equals(id)) return i;
		return -1;
	}

	/** Objetivo seleccionado, o null (para pruebas). */
	public Identifier seleccionado() {
		return seleccion >= 0 && seleccion < filtradas.size() ? filtradas.get(seleccion).id() : null;
	}

	private void filtrar(String texto) {
		String q = CatalogoObjetivos.normalizar(texto.trim());
		filtradas = q.isEmpty() ? todas : todas.stream().filter(e -> e.clave().contains(q)).toList();
		seleccion = filtradas.isEmpty() ? -1 : 0;
		desplazamiento = 0;
		actualizarBoton();
	}

	private boolean puedeComprar() {
		return seleccion >= 0 && seleccion < filtradas.size() && PantallaTienda.disponible(tipo) && PantallaTienda.alcanza(tipo);
	}

	private void actualizarBoton() {
		if (comprar != null) comprar.active = puedeComprar();
	}

	private void comprar() {
		if (!puedeComprar()) return;
		CatalogoObjetivos.Entrada e = filtradas.get(seleccion);
		ClientPlayNetworking.send(new PaquetesAyudas.PedirGuia(tipo.ordinal(), e.id()));
		minecraft.gui.setScreen(null);
	}

	@Override
	public void tick() {
		actualizarBoton();
	}

	private void mover(int paso) {
		if (filtradas.isEmpty()) return;
		seleccion = Mth.clamp(seleccion + paso, 0, filtradas.size() - 1);
		// Que la selección quede a la vista.
		int y = seleccion * FILA;
		if (y < desplazamiento) desplazamiento = y;
		if (y + FILA > desplazamiento + altoLista) desplazamiento = y + FILA - altoLista;
		actualizarBoton();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isUp()) {
			mover(-1);
			return true;
		}
		if (event.isDown()) {
			mover(1);
			return true;
		}
		if (event.isConfirmation()) {
			comprar();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doble) {
		if (event.x() >= x0 && event.x() < x0 + ANCHO && event.y() >= Y_LISTA && event.y() < Y_LISTA + altoLista) {
			int i = (int) ((event.y() - Y_LISTA + desplazamiento) / FILA);
			if (i >= 0 && i < filtradas.size()) {
				if (doble && i == seleccion) comprar();
				seleccion = i;
				actualizarBoton();
				return true;
			}
		}
		return super.mouseClicked(event, doble);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		desplazamiento = Mth.clamp(desplazamiento - dy * FILA * 2, 0, maxDesplazamiento());
		return true;
	}

	private double maxDesplazamiento() {
		return Math.max(0, filtradas.size() * FILA - altoLista);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		Arte.panel(g, x0 - 14, 14, ANCHO + 28, Y_LISTA + altoLista + 26 + 20 + 10 - 14);
		Arte.placa(g, font, title, width / 2, 6, Arte.ICONO_GUIA);
		Arte.fila(g, x0 - 2, Y_LISTA - 2, ANCHO + 4, altoLista + 4, false);

		desplazamiento = Mth.clamp(desplazamiento, 0, maxDesplazamiento());
		if (filtradas.isEmpty()) {
			g.centeredText(font, Component.translatable("venatio.ayudas.objetivos.vacio"), width / 2, Y_LISTA + altoLista / 2 - 4, GRIS);
		}
		g.enableScissor(x0, Y_LISTA, x0 + ANCHO, Y_LISTA + altoLista);
		int primera = (int) (desplazamiento / FILA);
		int ultima = Math.min(filtradas.size(), primera + altoLista / FILA + 2);
		for (int i = primera; i < ultima; i++) {
			CatalogoObjetivos.Entrada e = filtradas.get(i);
			int y = Y_LISTA + i * FILA - (int) desplazamiento;
			boolean encima = mouseX >= x0 && mouseX < x0 + ANCHO && mouseY >= Math.max(y, Y_LISTA) && mouseY < Math.min(y + FILA, Y_LISTA + altoLista);
			if (i == seleccion) Arte.fila(g, x0, y, ANCHO, FILA, true);
			else if (encima) g.fill(x0, y, x0 + ANCHO, y + FILA, 0x30FFFFFF);
			g.item(e.icono(), x0 + 2, y + 1);
			g.text(font, e.nombre(), x0 + 22, y + 5, e.disponible() ? BLANCO : GRIS);
			if (!e.disponible()) {
				Component nota = Component.translatable("venatio.ayudas.objetivos.otra_dimension");
				g.text(font, nota, x0 + ANCHO - font.width(nota) - 4, y + 5, GRIS);
			}
		}
		g.disableScissor();

		// Barra de desplazamiento
		double max = maxDesplazamiento();
		if (max > 0) {
			int alto = Math.max(12, altoLista * altoLista / (filtradas.size() * FILA));
			int y = Y_LISTA + (int) ((altoLista - alto) * (desplazamiento / max));
			g.fill(x0 + ANCHO - 2, y, x0 + ANCHO, y + alto, Arte.COBRE);
		}

		g.centeredText(font, lineaPrecio(), width / 2, Y_LISTA + altoLista + 8, BLANCO);
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	/** "Cuesta 8 × Lingote de hierro · tienes 12", o por qué no se puede comprar. */
	private Component lineaPrecio() {
		Component estado = PantallaTienda.estado();
		if (estado != null) return estado;
		PaquetesTienda.Oferta o = PantallaTienda.oferta(tipo);
		if (o == null) return Component.empty();
		int espera = AyudasCliente.enfriamiento(o);
		if (espera > 0) return Component.translatable("venatio.ayudas.tienda.espera", EstadoCliente.reloj(espera)).withColor(AMARILLO);
		if (o.cantidad() == 0) return Component.translatable("venatio.ayudas.tienda.gratis");
		var item = PantallaTienda.item(o);
		int tiene = AyudasCliente.contar(minecraft, item);
		return Component.translatable("venatio.ayudas.objetivos.precio", o.cantidad(), new ItemStack(item).getHoverName(), tiene)
			.withColor(tiene >= o.cantidad() ? BLANCO : ROJO);
	}
}
