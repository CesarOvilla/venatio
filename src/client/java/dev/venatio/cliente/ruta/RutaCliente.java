package dev.venatio.cliente.ruta;

import dev.venatio.Venatio;
import dev.venatio.cliente.EnPartida;
import dev.venatio.red.Paquetes;
import dev.venatio.red.PaquetesRuta;
import dev.venatio.ruta.DatosRuta;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Recibe la ruta (en trozos), la guarda y abre su pantalla. Vale la de la última partida hasta que empieza otra;
 * un espectador también puede recibir una foto en vivo. Las texturas del terreno se hacen al dibujarla por primera vez.
 */
public final class RutaCliente {
	private static int envio = -1;
	private static byte[][] trozos;
	private static int recibidos;
	private static boolean completo;
	private static boolean abrirAlCompletar;
	private static List<ItemStack> iconosEnCurso = List.of();

	private static DatosRuta ruta;
	private static List<ItemStack> iconos = List.of();
	private static final TexturaTerreno[] texturas = new TexturaTerreno[3];
	private static int creadas;

	private RutaCliente() {}

	public static void registrar() {
		ClientPlayNetworking.registerGlobalReceiver(PaquetesRuta.RutaTrozo.TYPE, (t, ctx) -> recibir(t, ctx.client()));
		// La desconexión llega en el hilo de red; las texturas solo se pueden soltar en el del cliente.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(RutaCliente::limpiar));
		ClientTickEvents.END_CLIENT_TICK.register(RutaCliente::alTick);
	}

	/** La ruta guardada (final o en vivo), o null. */
	public static DatosRuta ruta() {
		return ruta;
	}

	public static List<ItemStack> iconos() {
		return iconos;
	}

	/** ¿Ya llegó la ruta final de la partida (para el botón de la pantalla final)? */
	public static boolean hayFinal() {
		return ruta != null && !ruta.enVivo();
	}

	/** Tecla de la ruta: se la pide al servidor (que responde con la ruta o con el motivo por el que no se puede). */
	public static void pedir(Minecraft mc) {
		if (mc.player != null) mc.player.connection.sendCommand("venatio ruta");
	}

	/** Abre la pantalla con la ruta guardada; al cerrarla se vuelve a anterior. */
	public static void abrir(Minecraft mc, Screen anterior) {
		if (ruta == null) return;
		if (anterior instanceof PantallaRuta r) anterior = r.anterior();
		mc.gui.setScreen(new PantallaRuta(anterior, ruta, iconos));
	}

	private static void recibir(PaquetesRuta.RutaTrozo t, Minecraft mc) {
		if (t.envio() != envio) {
			envio = t.envio();
			trozos = new byte[Math.max(1, t.total())][];
			recibidos = 0;
			completo = false;
			abrirAlCompletar = false;
			iconosEnCurso = List.of();
		}
		abrirAlCompletar |= t.abrir();
		if (completo) {
			// La misma ruta otra vez (/venatio ruta): ya la tengo, solo se abre.
			if (t.indice() == t.total() - 1 && abrirAlCompletar) abrir(mc, mc.gui.screen());
			return;
		}
		if (t.indice() < 0 || t.indice() >= trozos.length || trozos[t.indice()] != null) return;
		trozos[t.indice()] = t.datos();
		if (t.indice() == 0) iconosEnCurso = t.iconos();
		if (++recibidos < trozos.length) return;

		ByteArrayOutputStream todo = new ByteArrayOutputStream();
		for (byte[] b : trozos) todo.writeBytes(b);
		trozos = null;
		completo = true;
		try {
			establecer(DatosRuta.descomprimir(todo.toByteArray()), iconosEnCurso);
		} catch (RuntimeException e) {
			Venatio.LOG.error("No se pudo leer la ruta de la partida", e);
			return;
		}
		if (abrirAlCompletar) abrir(mc, mc.gui.screen());
	}

	private static void establecer(DatosRuta d, List<ItemStack> ic) {
		liberarTexturas();
		ruta = d;
		iconos = ic;
	}

	private static void alTick(Minecraft mc) {
		if (ruta == null) return;
		int fase = EnPartida.fase();
		// Empezó otra partida: la ruta anterior ya no vale. Una foto en vivo no sobrevive a su partida.
		if (fase == Paquetes.Estado.PREPARANDO && !ruta.enVivo() || fase == Paquetes.Estado.LOBBY && ruta.enVivo()) {
			if (!(mc.gui.screen() instanceof PantallaRuta)) limpiarRuta();
		}
	}

	private static void limpiarRuta() {
		liberarTexturas();
		ruta = null;
		iconos = List.of();
	}

	private static void limpiar() {
		limpiarRuta();
		envio = -1;
		trozos = null;
		completo = false;
	}

	// ---------------------------------------------------------------- texturas

	/** Mosaicos del terreno de una dimensión de la ruta guardada (se crean la primera vez), o null si no hay terreno. */
	static TexturaTerreno textura(DatosRuta d, int dim) {
		if (d != ruta || dim < 0 || dim >= texturas.length) return null;
		if (texturas[dim] == null) {
			DatosRuta.Terreno t = d.terreno(dim);
			if (t == null || t.cantidad() == 0) return null;
			texturas[dim] = TexturaTerreno.crear(t, creadas++);
		}
		return texturas[dim];
	}

	private static void liberarTexturas() {
		for (int i = 0; i < texturas.length; i++) {
			if (texturas[i] != null) texturas[i].close();
			texturas[i] = null;
		}
	}
}
