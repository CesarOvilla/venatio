package dev.venatio.cliente;

import dev.venatio.ayudas.TipoAyuda;
import dev.venatio.juego.ColorEquipo;
import dev.venatio.red.PaquetesTienda;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Tecla H: las ayudas con su precio, cuánto tienes del material y el enfriamiento que te queda. */
public final class PantallaTienda extends Screen {
	private static final int ANCHO = 370;
	private static final int FILA = 28;
	private static final int BLANCO = 0xFFFFFFFF;
	private static final int GRIS = 0xFFAAAAAA;
	private static final int VERDE = 0xFF7CFF7C;
	private static final int ROJO = 0xFFFF6B6B;
	private static final int AMARILLO = 0xFFFFE066;
	private static final Identifier ROMBO = Identifier.fromNamespaceAndPath("venatio_ayudas", "textures/gui/marca.png");

	private final Button[] botones = new Button[TipoAyuda.values().length];
	private int x0, y0;

	public PantallaTienda() {
		super(Component.translatable("venatio.ayudas.tienda.titulo"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		x0 = (width - ANCHO) / 2;
		int altoTotal = TipoAyuda.values().length * FILA + 30;
		y0 = Math.max(34, (height - altoTotal) / 2 + 14);
		for (TipoAyuda t : TipoAyuda.values()) {
			int y = y0 + t.ordinal() * FILA;
			botones[t.ordinal()] = addRenderableWidget(Button.builder(textoBoton(t), b -> accion(t))
				.bounds(x0 + ANCHO - 74, y + 3, 68, 20).build());
		}
		addRenderableWidget(Button.builder(Component.translatable("venatio.ayudas.cerrar"), b -> onClose())
			.bounds(width / 2 - 50, y0 + TipoAyuda.values().length * FILA + 6, 100, 20).build());
		actualizarBotones();
	}

	private static Component textoBoton(TipoAyuda t) {
		return Component.translatable(switch (t) {
			case MINIMAPA -> "venatio.ayudas.tienda.comprar";
			case MARCA -> "venatio.ayudas.tienda.marcar";
			default -> "venatio.ayudas.tienda.elegir";
		});
	}

	private void accion(TipoAyuda t) {
		switch (t) {
			case MINIMAPA -> {
				ClientPlayNetworking.send(PaquetesTienda.ComprarMinimapa.INSTANCIA);
				onClose();
			}
			case MARCA -> {
				onClose();
				AyudasCliente.ponerMarca();
			}
			default -> minecraft.gui.setScreen(new PantallaObjetivos(t, this));
		}
	}

	@Override
	public void tick() {
		actualizarBotones();
	}

	private void actualizarBotones() {
		for (TipoAyuda t : TipoAyuda.values()) {
			Button b = botones[t.ordinal()];
			if (b != null) b.active = disponible(t) && alcanza(t);
		}
	}

	/** Se puede usar ahora: hay tienda, ayudas activas, partida en juego y sin enfriamiento. */
	static boolean disponible(TipoAyuda t) {
		PaquetesTienda.Tienda tienda = AyudasCliente.tienda;
		PaquetesTienda.Oferta o = oferta(t);
		return tienda != null && tienda.activas() && AyudasCliente.jugando() && o != null && AyudasCliente.enfriamiento(o) == 0;
	}

	static boolean alcanza(TipoAyuda t) {
		PaquetesTienda.Oferta o = oferta(t);
		if (o == null) return false;
		return o.cantidad() == 0 || AyudasCliente.contar(Minecraft.getInstance(), item(o)) >= o.cantidad();
	}

	static PaquetesTienda.Oferta oferta(TipoAyuda t) {
		PaquetesTienda.Tienda tienda = AyudasCliente.tienda;
		if (tienda == null) return null;
		for (PaquetesTienda.Oferta o : tienda.ofertas()) if (o.tipo() == t.ordinal()) return o;
		return null;
	}

	static Item item(PaquetesTienda.Oferta o) {
		return BuiltInRegistries.ITEM.getValue(o.item());
	}

	/** Motivo para no poder comprar, o null si se puede. */
	static Component estado() {
		PaquetesTienda.Tienda tienda = AyudasCliente.tienda;
		if (tienda == null) return Component.translatable("venatio.ayudas.tienda.cargando").withColor(GRIS);
		if (!tienda.activas()) return Component.translatable("venatio.ayudas.tienda.apagadas").withColor(ROJO);
		if (!AyudasCliente.jugando()) return Component.translatable("venatio.ayudas.tienda.fuera").withColor(ROJO);
		return null;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (VenatioCliente.teclaAyudas().matches(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.centeredText(font, title, width / 2, y0 - 28, BLANCO);
		Component estado = estado();
		g.centeredText(font, estado != null ? estado : Component.translatable("venatio.ayudas.tienda.subtitulo").withColor(GRIS),
			width / 2, y0 - 15, BLANCO);

		for (TipoAyuda t : TipoAyuda.values()) dibujarFila(g, t, y0 + t.ordinal() * FILA, mouseX, mouseY);
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	private void dibujarFila(GuiGraphicsExtractor g, TipoAyuda t, int y, int mouseX, int mouseY) {
		boolean encima = mouseX >= x0 && mouseX < x0 + ANCHO && mouseY >= y && mouseY < y + FILA - 2;
		g.fill(x0, y, x0 + ANCHO, y + FILA - 2, encima ? 0xC0202020 : 0xB0000000);

		// Ícono de la ayuda
		if (t == TipoAyuda.MARCA) {
			int color = EstadoCliente.estado == null ? 0xFFFFFF : HudCarton.color(EstadoCliente.estado.miEquipo());
			g.blit(RenderPipelines.GUI_TEXTURED, ROMBO, x0 + 6, y + 5, 0, 0, 16, 16, 16, 16, 16, 16, 0xFF000000 | color);
		} else {
			g.item(new ItemStack(icono(t)), x0 + 6, y + 5);
		}

		PaquetesTienda.Tienda tienda = AyudasCliente.tienda;
		g.text(font, Component.translatable(t.clave()), x0 + 28, y + 4, BLANCO);
		g.text(font, descripcion(t, tienda), x0 + 28, y + 15, GRIS);

		PaquetesTienda.Oferta o = oferta(t);
		if (o == null) return;
		// Precio arriba; abajo lo que tienes, o el enfriamiento si todavía no se puede usar.
		int px = x0 + ANCHO - 160;
		int tx = px;
		Component abajo = null;
		int colorAbajo = GRIS;
		if (o.cantidad() == 0) {
			g.text(font, Component.translatable("venatio.ayudas.tienda.gratis"), px, y + 4, VERDE);
		} else {
			Item item = item(o);
			g.item(new ItemStack(item), px, y + 5);
			tx = px + 18;
			g.text(font, "× " + o.cantidad(), tx, y + 4, BLANCO);
			int tiene = AyudasCliente.contar(minecraft, item);
			abajo = Component.translatable("venatio.ayudas.tienda.tienes", tiene);
			colorAbajo = tiene >= o.cantidad() ? VERDE : ROJO;
			if (mouseX >= px && mouseX < px + 16 && mouseY >= y + 5 && mouseY < y + 21) {
				g.setTooltipForNextFrame(font, new ItemStack(item), mouseX, mouseY);
			}
		}
		int espera = AyudasCliente.enfriamiento(o);
		if (espera > 0) {
			abajo = Component.translatable("venatio.ayudas.tienda.espera", EstadoCliente.reloj(espera));
			colorAbajo = AMARILLO;
		}
		if (abajo != null) g.text(font, abajo, tx, y + 15, colorAbajo);
	}

	private static Item icono(TipoAyuda t) {
		return switch (t) {
			case GUIA_BIOMA -> Items.GRASS_BLOCK;
			case GUIA_ESTRUCTURA -> Items.CHISELED_STONE_BRICKS;
			case GUIA_MOB -> Items.ZOMBIE_HEAD;
			default -> Items.FILLED_MAP;
		};
	}

	private static Component descripcion(TipoAyuda t, PaquetesTienda.Tienda tienda) {
		return switch (t) {
			case MINIMAPA -> {
				int activo = AyudasCliente.minimapaRestante();
				if (activo > 0) yield Component.translatable("venatio.ayudas.tienda.minimapa_activo", EstadoCliente.reloj(activo)).withColor(VERDE);
				yield tienda == null ? Component.empty()
					: Component.translatable("venatio.ayudas.desc.minimapa", EstadoCliente.reloj(tienda.minimapaSegundos()), tienda.minimapaRadio());
			}
			case MARCA -> tienda == null ? Component.empty()
				: Component.translatable("venatio.ayudas.desc.marca", tienda.marcaDuracion(), tienda.marcaMaximo());
			default -> Component.translatable("venatio.ayudas.desc." + t.id);
		};
	}
}
