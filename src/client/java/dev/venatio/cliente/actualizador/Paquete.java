package dev.venatio.cliente.actualizador;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** instalador/paquete.txt: qué versión es y qué jars lleva. Rechaza lo que no pueda ser un paquete sano. */
public record Paquete(String version, String minecraft, String fabric, List<Mod> mods) {
	public record Mod(String nombre, String sha512, String url) {}

	/** Sin separadores de carpeta: un nombre nunca puede escribir fuera de mods/. */
	private static final Pattern NOMBRE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*\\.jar");
	private static final Pattern HUELLA = Pattern.compile("[0-9a-f]{128}");
	private static final Pattern VERSION = Pattern.compile("\\d+(\\.\\d+)*");

	public static Paquete leer(String texto) {
		String version = null, minecraft = null, fabric = null;
		List<Mod> mods = new ArrayList<>();
		Set<String> nombres = new HashSet<>();
		for (String linea : texto.split("\\R")) {
			if (linea.isBlank() || linea.startsWith("#")) continue;
			String[] p = linea.trim().split("\\s+");
			switch (p[0]) {
				case "version" -> version = p[1];
				case "minecraft" -> minecraft = p[1];
				case "fabric" -> fabric = p[1];
				case "mod" -> {
					if (p.length != 4 || !NOMBRE.matcher(p[1]).matches() || !HUELLA.matcher(p[2]).matches() || !nombres.add(p[1])) {
						throw new IllegalArgumentException("Línea de mod inválida: " + p[1]);
					}
					mods.add(new Mod(p[1], p[2], p[3]));
				}
				default -> {}
			}
		}
		if (version == null || !VERSION.matcher(version).matches() || minecraft == null || fabric == null) {
			throw new IllegalArgumentException("Paquete incompleto");
		}
		if (!nombres.contains("venatio-" + version + ".jar")) throw new IllegalArgumentException("El paquete no trae Venatio " + version);
		return new Paquete(version, minecraft, fabric, List.copyOf(mods));
	}
}
