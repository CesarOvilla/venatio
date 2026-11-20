package dev.venatio.cliente;

import dev.venatio.Venatio;
import dev.venatio.cliente.minimapa.Minimapa;
import dev.venatio.cliente.fichas.FichasCliente;
import dev.venatio.red.Paquetes;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class VenatioCliente implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORIA =
		KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Venatio.MOD_ID, "teclas"));

	private static KeyMapping carton;
	private static KeyMapping ayudas;
	private static KeyMapping marca;
	private static KeyMapping minimapa;

	@Override
	public void onInitializeClient() {
		carton = tecla("carton", InputConstants.KEY_B);
		ayudas = tecla("ayudas", InputConstants.KEY_H);
		marca = tecla("marca", InputConstants.KEY_N);
		minimapa = tecla("minimapa", InputConstants.KEY_M);
		Minimapa.registrar(minimapa, () -> EstadoCliente.estado);

		ClientPlayNetworking.registerGlobalReceiver(Paquetes.Estado.TYPE, (estado, ctx) -> EstadoCliente.recibir(estado));
		ClientPlayNetworking.registerGlobalReceiver(Paquetes.Evento.TYPE, (evento, ctx) -> EstadoCliente.evento(evento, ctx.client()));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			EstadoCliente.limpiar();
			ClientPlayNetworking.send(new Paquetes.Hola(Venatio.version()));
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> EstadoCliente.limpiar());
		StatsCliente.registrar();
		FichasCliente.registrar();

		HudElementRegistry.attachElementAfter(VanillaHudElements.BOSS_BAR,
			Identifier.fromNamespaceAndPath(Venatio.MOD_ID, "carton"), HudCarton::dibujar);
		AyudasCliente.registrar();
		ClientTickEvents.END_CLIENT_TICK.register(VenatioCliente::alTick);
		ServidorEnLista.registrar();
	}

	static KeyMapping teclaCarton() {
		return carton;
	}

	static KeyMapping teclaAyudas() {
		return ayudas;
	}

	private static KeyMapping tecla(String nombre, int codigo) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.venatio." + nombre, codigo, CATEGORIA));
	}

	private static void alTick(Minecraft mc) {
		if (mc.player == null) return;
		while (carton.consumeClick()) {
			if (mc.gui.screen() == null) mc.gui.setScreen(StatsCliente.pantallaTeclaB());
		}
		while (ayudas.consumeClick()) {
			if (mc.gui.screen() == null) AyudasCliente.abrirTienda(mc);
		}
		while (marca.consumeClick()) AyudasCliente.ponerMarca();
		// La M (minimapa) la atiende el módulo Minimapa.
	}
}
