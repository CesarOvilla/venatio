package dev.venatio.cliente.actualizador;

import dev.venatio.Venatio;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * En el menú principal revisa si hay una versión más nueva y la ofrece. Solo corre en una instalación hecha con el
 * instalador (no en desarrollo ni en las pruebas). Sin internet no molesta: lo intenta otra vez más tarde.
 */
public final class ActualizadorCliente {
	private static final long CADA_MS = 10 * 60_000L;

	private static Actualizador actualizador;
	private static Path raiz, mods;
	private static long proximaRevision, proximoAviso;
	private static volatile boolean buscando;
	private static volatile Paquete disponible;

	private ActualizadorCliente() {}

	public static void registrar() {
		FabricLoader fabric = FabricLoader.getInstance();
		if (fabric.isDevelopmentEnvironment() || Boolean.getBoolean("venatio.sinActualizar")) return;
		mods = fabric.getGameDir().resolve("mods");
		raiz = fabric.getGameDir().resolve(".venatio-actualizacion");
		if (!Files.exists(fabric.getConfigDir().resolve("venatio-servidor.txt"))
			|| !Files.exists(mods.resolve("venatio-" + Venatio.version() + ".jar"))) return;
		try {
			actualizador = Actualizador.oficial();
			Files.createDirectories(raiz);
			Actualizador.limpiar(raiz);
		} catch (Exception e) {
			Venatio.LOG.warn("Autoactualizador apagado: {}", e.toString());
			actualizador = null;
			return;
		}
		ClientTickEvents.END_CLIENT_TICK.register(ActualizadorCliente::alTick);
	}

	private static void alTick(Minecraft mc) {
		if (mc.gui.overlay() != null || !(mc.gui.screen() instanceof TitleScreen titulo)) return;
		long ahora = System.currentTimeMillis();
		if (!buscando && ahora >= proximaRevision) {
			proximaRevision = ahora + CADA_MS;
			buscando = true;
			Thread hilo = new Thread(ActualizadorCliente::buscar, "Venatio-actualizador");
			hilo.setDaemon(true);
			hilo.start();
		}
		Paquete p = disponible;
		if (p != null && ahora >= proximoAviso) {
			proximoAviso = ahora + CADA_MS;
			mc.gui.setScreen(new PantallaActualizacion(titulo, p, actualizador, mods, raiz));
		}
	}

	private static void buscar() {
		try {
			Paquete p = actualizador.buscar();
			if (Actualizador.esMasNueva(p.version(), Venatio.version())) disponible = p;
		} catch (Exception e) {
			Venatio.LOG.info("No se pudo revisar si hay actualización: {}", e.toString());
		} finally {
			buscando = false;
		}
	}

	/** Cambia Minecraft o Fabric: los jars no bastan, hay que volver a correr el instalador. */
	static boolean requiereInstalador(Paquete p) {
		return !p.minecraft().equals(versionDe("minecraft")) || !p.fabric().equals(versionDe("fabricloader"));
	}

	private static String versionDe(String mod) {
		return FabricLoader.getInstance().getModContainer(mod).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}

	static String comandoInstalador() {
		String base = "https://raw.githubusercontent.com/CesarOvilla/venatio/main/instalador/";
		return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
			? "irm " + base + "instalar.ps1 | iex"
			: "curl -fsSL " + base + "instalar.sh | sh";
	}
}
