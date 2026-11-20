package dev.venatio.cliente;

import dev.venatio.Venatio;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Agrega el server de Venatio a la lista de servidores la primera vez que se abre el juego.
 * El instalador deja la dirección en config/venatio-servidor.txt (líneas "nombre=…" y "direccion=…").
 */
public final class ServidorEnLista {
	private ServidorEnLista() {}

	public static void registrar() {
		ClientLifecycleEvents.CLIENT_STARTED.register(ServidorEnLista::agregarSiFalta);
	}

	private static void agregarSiFalta(Minecraft mc) {
		Path archivo = FabricLoader.getInstance().getConfigDir().resolve("venatio-servidor.txt");
		if (!Files.isRegularFile(archivo)) return;
		try {
			String nombre = "Venatio", direccion = null;
			for (String linea : Files.readAllLines(archivo, StandardCharsets.UTF_8)) {
				if (linea.startsWith("nombre=")) nombre = linea.substring(7).trim();
				if (linea.startsWith("direccion=")) direccion = linea.substring(10).trim();
			}
			if (direccion == null || direccion.isEmpty()) return;
			ServerList lista = new ServerList(mc);
			lista.load();
			for (int i = 0; i < lista.size(); i++) {
				if (direccion.equalsIgnoreCase(lista.get(i).ip)) return;
			}
			lista.add(new ServerData(nombre, direccion, ServerData.Type.OTHER), false);
			lista.save();
			Venatio.LOG.info("Server {} ({}) agregado a la lista de servidores", nombre, direccion);
		} catch (Exception e) {
			Venatio.LOG.warn("No se pudo agregar el server a la lista", e);
		}
	}
}
