package dev.venatio.cliente;

import dev.venatio.Venatio;
import dev.venatio.red.PaquetesTienda;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Sonidos propios de las ayudas: compra (guía o minimapa), marca puesta y llegada de la guía.
 *
 * {@link AyudasCliente} recibe esos paquetes y toca sonidos vanilla. Para no tocar ese archivo, aquí se envuelve
 * cada receptor: mientras corre el original se callan sus sonidos vanilla ({@code SoundManagerMixin}) y después
 * se toca el propio. Si algún día AyudasCliente llama a {@link Sonidos} directamente, esta clase y el mixin sobran.
 */
public final class SonidosAyudas {
	private static boolean callando;
	/** Tipo y objetivo de la guía en curso, para distinguir una compra de un refresco de la misma guía. */
	private static String guiaActual;
	private static final Set<Integer> marcasVistas = new HashSet<>();
	private static int minimapaRestante = -1;

	private SonidosAyudas() {}

	/** Hay que llamarlo después de AyudasCliente.registrar(). */
	static void registrar() {
		envolver(PaquetesTienda.Guia.TYPE, g -> {
			String clave = g.tipo() + "|" + g.objetivo();
			if (!clave.equals(guiaActual)) Sonidos.tocar(Sonidos.COMPRA);
			guiaActual = clave;
		});
		envolver(PaquetesTienda.GuiaTerminada.TYPE, t -> {
			guiaActual = null;
			if (t.motivo() == PaquetesTienda.GuiaTerminada.LLEGADA) Sonidos.tocar(Sonidos.GUIA_LLEGADA);
		});
		envolver(PaquetesTienda.Marcas.TYPE, m -> {
			boolean nueva = false;
			for (PaquetesTienda.MarcaRed r : m.marcas()) if (marcasVistas.add(r.id())) nueva = true;
			if (nueva) Sonidos.tocar(Sonidos.MARCA);
		});
		envolver(PaquetesTienda.Tienda.TYPE, t -> {
			// El minimapa comprado se nota porque su tiempo restante sube.
			if (minimapaRestante >= 0 && t.minimapaRestante() > minimapaRestante + 1) Sonidos.tocar(Sonidos.COMPRA);
			minimapaRestante = t.minimapaRestante();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			guiaActual = null;
			marcasVistas.clear();
			minimapaRestante = -1;
		});
	}

	private static <T extends CustomPacketPayload> void envolver(CustomPacketPayload.Type<T> tipo, Consumer<T> despues) {
		@SuppressWarnings("unchecked")
		ClientPlayNetworking.PlayPayloadHandler<T> original =
			(ClientPlayNetworking.PlayPayloadHandler<T>) ClientPlayNetworking.unregisterGlobalReceiver(tipo.id());
		if (original == null) {
			// No se registra nada: si quien recibe este paquete se registra después, que no se encuentre el sitio ocupado.
			Venatio.LOG.warn("Nadie recibe {} todavía: ese sonido de las ayudas se queda sin sustituir", tipo.id());
			return;
		}
		ClientPlayNetworking.registerGlobalReceiver(tipo, (paquete, ctx) -> {
			callando = true;
			try {
				original.receive(paquete, ctx);
			} finally {
				callando = false;
			}
			despues.accept(paquete);
		});
	}

	/** true si este sonido es uno vanilla que AyudasCliente está tocando ahora mismo: se sustituye por el propio. */
	public static boolean calla(SoundInstance sonido) {
		return callando && "minecraft".equals(sonido.getIdentifier().getNamespace());
	}
}
