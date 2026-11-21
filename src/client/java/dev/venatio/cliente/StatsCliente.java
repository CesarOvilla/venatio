package dev.venatio.cliente;

import dev.venatio.red.Paquetes;
import dev.venatio.red.PaquetesStats;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

/** Cliente del módulo de estadísticas: pantalla final (al terminar y con B) y panel del anfitrión. */
public final class StatsCliente {
	/** Resumen de la última partida terminada; vale mientras el servidor siga en la fase final. */
	static PaquetesStats.FinalPartida ultimoFinal;

	private StatsCliente() {}

	public static void registrar() {
		ClientPlayNetworking.registerGlobalReceiver(PaquetesStats.FinalPartida.TYPE, (f, ctx) -> alTerminar(f, ctx.client()));
		ClientPlayNetworking.registerGlobalReceiver(PaquetesStats.ConfigPanel.TYPE, (c, ctx) -> alRecibirConfig(c, ctx.client()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ultimoFinal = null);
	}

	static boolean hayFinal() {
		Paquetes.Estado e = EstadoCliente.estado;
		return ultimoFinal != null && e != null && e.fase() == Paquetes.Estado.FINAL;
	}

	/** Tecla B: durante el final abre el resumen; durante el draft, la pantalla para vetar; el resto del tiempo, el cartón. */
	static Screen pantallaTeclaB() {
		return hayFinal() ? new PantallaFinal(ultimoFinal) : VariantesCliente.enDraft() ? new PantallaDraft() : new PantallaCarton();
	}

	private static void alTerminar(PaquetesStats.FinalPartida f, Minecraft mc) {
		ultimoFinal = f;
		if (mc.player == null) return;
		Screen actual = mc.gui.screen();
		// Si estaba en un inventario o mesa se cierra (avisando al servidor); el chat o el menú de pausa no se interrumpen.
		if (actual instanceof AbstractContainerScreen<?>) {
			mc.player.closeContainer();
			actual = mc.gui.screen();
		}
		if (actual == null || actual instanceof PantallaCarton) {
			mc.gui.setScreen(new PantallaFinal(f));
		} else {
			mc.player.sendOverlayMessage(Component.translatable("venatio.stats.final.ver"));
		}
	}

	private static void alRecibirConfig(PaquetesStats.ConfigPanel c, Minecraft mc) {
		Screen actual = mc.gui.screen();
		if (actual instanceof PantallaAnfitrion panel) {
			panel.actualizar(c);
		} else if (c.abrir() && mc.player != null) {
			mc.gui.setScreen(new PantallaAnfitrion(c));
		}
	}
}
