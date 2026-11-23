package dev.venatio.cliente.actualizador;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Programa aparte que cambia los jars cuando Minecraft ya cerró (en Windows no se pueden tocar mientras está abierto).
 * Se copia fuera de mods/ y corre solo con la JVM: es una sola clase, sin nada de Minecraft ni de Fabric.
 * Si algo falla deja mods/ como estaba.
 *
 * Uso: Aplicador &lt;pid del juego&gt; &lt;carpeta nueva&gt; &lt;carpeta mods&gt;
 */
public final class Aplicador {
	private static final long ESPERA_MAXIMA_MS = 15 * 60_000L;
	private static final int REINTENTOS = 12;

	private Aplicador() {}

	public static void main(String[] args) throws Exception {
		long pid = Long.parseLong(args[0]);
		Path nueva = Path.of(args[1]), mods = Path.of(args[2]);
		long limite = System.currentTimeMillis() + ESPERA_MAXIMA_MS;
		Optional<ProcessHandle> juego = ProcessHandle.of(pid);
		while (juego.isPresent() && juego.get().isAlive()) {
			if (System.currentTimeMillis() > limite) {
				System.out.println("El juego sigue abierto: no se aplicó la actualización");
				System.exit(2);
			}
			Thread.sleep(250);
		}
		Thread.sleep(500);

		Path jars = nueva.resolve("jars"), respaldo = nueva.resolve("respaldo");
		Set<String> plan = new HashSet<>(Files.readAllLines(nueva.resolve("plan.txt")));
		Files.createDirectories(respaldo);
		List<String> respaldados = new ArrayList<>(), puestos = new ArrayList<>();
		try {
			for (Path jar : jarsDe(mods)) {
				String nombre = jar.getFileName().toString();
				if (plan.contains(nombre) && !Files.exists(jars.resolve(nombre))) continue;
				mover(jar, respaldo.resolve(nombre));
				respaldados.add(nombre);
			}
			for (Path jar : jarsDe(jars)) {
				String nombre = jar.getFileName().toString();
				mover(jar, mods.resolve(nombre));
				puestos.add(nombre);
			}
		} catch (Exception e) {
			System.out.println("Falló, se deja todo como estaba: " + e);
			for (String nombre : puestos) mover(mods.resolve(nombre), jars.resolve(nombre));
			for (String nombre : respaldados) mover(respaldo.resolve(nombre), mods.resolve(nombre));
			System.exit(1);
		}
		borrar(nueva);
		System.out.println("Actualización aplicada: " + puestos);
	}

	private static List<Path> jarsDe(Path carpeta) throws IOException {
		List<Path> lista = new ArrayList<>();
		try (DirectoryStream<Path> s = Files.newDirectoryStream(carpeta, "*.jar")) {
			for (Path p : s) lista.add(p);
		}
		return lista;
	}

	/** Reintenta: en Windows el archivo puede seguir bloqueado un momento después de cerrar el juego. */
	private static void mover(Path de, Path a) throws IOException, InterruptedException {
		for (int i = 1; ; i++) {
			try {
				Files.move(de, a, StandardCopyOption.REPLACE_EXISTING);
				return;
			} catch (IOException e) {
				if (i >= REINTENTOS) throw e;
				Thread.sleep(250);
			}
		}
	}

	private static void borrar(Path p) throws IOException {
		if (Files.isDirectory(p)) {
			try (DirectoryStream<Path> s = Files.newDirectoryStream(p)) {
				for (Path hijo : s) borrar(hijo);
			}
		}
		Files.deleteIfExists(p);
	}
}
