package dev.venatio.prueba;

import com.sun.net.httpserver.HttpServer;
import dev.venatio.Venatio;
import dev.venatio.cliente.actualizador.Actualizador;
import dev.venatio.cliente.actualizador.Paquete;
import dev.venatio.cliente.actualizador.PantallaActualizacion;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Autoactualizador contra un servidor local: firma, huellas, orígenes, cambio real de jars con el proceso aparte (espera a
 * que cierre el juego, quita lo viejo, conserva lo igual) y que un fallo deje mods/ como estaba. No necesita mundo.
 */
public final class PruebaActualizador implements FabricClientGameTest {
	private final Map<String, byte[]> archivos = new HashMap<>();

	@Override
	public void runTest(ClientGameTestContext ctx) {
		HttpServer web = null;
		try {
			web = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			web.createContext("/", i -> {
				byte[] b = archivos.get(i.getRequestURI().getPath());
				i.sendResponseHeaders(b == null ? 404 : 200, b == null ? -1 : b.length);
				if (b != null) i.getResponseBody().write(b);
				i.close();
			});
			web.start();
			correr(ctx, "http://127.0.0.1:" + web.getAddress().getPort());
		} catch (RuntimeException | Error e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			if (web != null) web.stop(0);
		}
	}

	private void correr(ClientGameTestContext ctx, String base) throws Exception {
		comprobar(Actualizador.esMasNueva("0.10.0", "0.9.0") && Actualizador.esMasNueva("1.0", "0.9.9") && !Actualizador.esMasNueva("0.2.0", "0.2.0")
			&& !Actualizador.esMasNueva("0.1.9", "0.2.0"), "comparación de versiones");
		paso("Compara versiones número por número y nunca baja de versión");

		KeyPair llaves = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(), ajena = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		Actualizador act = Actualizador.paraPruebas(base + "/paquete.txt", llaves.getPublic());
		byte[] venatio = "venatio nuevo".getBytes(), cambia = "cambia nuevo".getBytes(), igual = "igual".getBytes();
		archivos.put("/venatio-9.0.0.jar", venatio);
		archivos.put("/cambia.jar", cambia);
		String texto = "version 9.0.0\nminecraft 26.3\nfabric 0.20.1\n"
			+ mod("venatio-9.0.0.jar", venatio, base + "/venatio-9.0.0.jar")
			+ mod("cambia.jar", cambia, base + "/cambia.jar")
			+ mod("igual.jar", igual, base + "/no-se-debe-bajar.jar");
		publicar(texto, llaves);
		Paquete paquete = act.buscar();
		comprobar(paquete.version().equals("9.0.0") && paquete.mods().size() == 3, "lee el paquete firmado");

		publicar(texto, ajena);
		falla(act::buscar, "firma de otra clave");
		publicar(texto, llaves);
		archivos.put("/paquete.txt", (texto + "mod malo.jar " + "0".repeat(128) + " " + base + "/x.jar\n").getBytes());
		falla(act::buscar, "paquete alterado después de firmar");
		publicar(texto, llaves);
		falla(() -> Paquete.leer("version 9\nminecraft 26.3\nfabric 0.20.1\nmod ../fuera.jar " + "0".repeat(128) + " " + base + "/x.jar\n"), "nombre con ruta");
		falla(() -> Paquete.leer("version 9\nminecraft 26.3\nfabric 0.20.1\n"), "paquete sin Venatio");
		paso("Rechaza firma ajena, paquete alterado, nombres con ruta y paquetes sin Venatio");

		Path raiz = Files.createTempDirectory("venatio-actualizador"), mods = Files.createDirectories(raiz.resolve("mods")), trabajo = Files.createDirectories(raiz.resolve("trabajo"));
		Files.write(mods.resolve("venatio-0.2.0.jar"), "venatio viejo".getBytes());
		Files.write(mods.resolve("cambia.jar"), "cambia viejo".getBytes());
		Files.write(mods.resolve("igual.jar"), igual);

		// Huella distinta y origen no permitido: no queda nada preparado para aplicar.
		byte[] buena = archivos.put("/cambia.jar", "alterado".getBytes());
		falla(() -> act.preparar(paquete, mods, trabajo, n -> {}), "jar con huella distinta");
		archivos.put("/cambia.jar", buena);
		Paquete externo = Paquete.leer("version 9.0.0\nminecraft 26.3\nfabric 0.20.1\n" + mod("venatio-9.0.0.jar", venatio, "https://ejemplo.invalid/v.jar"));
		falla(() -> act.preparar(externo, mods, trabajo, n -> {}), "origen no permitido");
		comprobar(!Files.exists(trabajo.resolve("nueva/plan.txt")), "sin plan tras un fallo");
		paso("Un jar alterado o de otro origen se rechaza y no deja nada por aplicar");

		act.preparar(paquete, mods, trabajo, n -> {});
		comprobar(!Files.exists(trabajo.resolve("nueva/jars/igual.jar")), "lo que ya está igual no se baja");

		// Falla al aplicar (mods/ de solo lectura): vuelve todo a como estaba.
		mods.toFile().setWritable(false);
		Process falla = Actualizador.lanzarAplicador(trabajo, mods, procesoTerminado());
		comprobar(falla.waitFor(30, TimeUnit.SECONDS) && falla.exitValue() == 1, "el aplicador reporta el fallo: " + log(trabajo));
		mods.toFile().setWritable(true);
		comprobar(nombres(mods).equals(Set.of("venatio-0.2.0.jar", "cambia.jar", "igual.jar"))
			&& new String(Files.readAllBytes(mods.resolve("cambia.jar"))).equals("cambia viejo"), "mods/ quedó como estaba");
		paso("Si no puede escribir, deja mods/ como estaba");

		// Aplicar de verdad: espera a que cierre el "juego" (un proceso aparte).
		act.preparar(paquete, mods, trabajo, n -> {});
		Process juego = new ProcessBuilder("sleep", "2").start();
		Process aplicador = Actualizador.lanzarAplicador(trabajo, mods, juego.pid());
		Thread.sleep(1000);
		comprobar(juego.isAlive() && aplicador.isAlive() && Files.exists(mods.resolve("venatio-0.2.0.jar")), "no toca nada con el juego abierto");
		comprobar(aplicador.waitFor(30, TimeUnit.SECONDS) && aplicador.exitValue() == 0, "el aplicador terminó bien: " + log(trabajo));
		comprobar(nombres(mods).equals(Set.of("venatio-9.0.0.jar", "cambia.jar", "igual.jar")), "jars finales: " + nombres(mods));
		comprobar(new String(Files.readAllBytes(mods.resolve("cambia.jar"))).equals("cambia nuevo"), "cambió el jar que cambiaba");
		comprobar(new String(Files.readAllBytes(mods.resolve("igual.jar"))).equals("igual"), "conservó el que estaba igual");
		comprobar(!Files.exists(trabajo.resolve("nueva")), "limpió lo preparado");
		paso("Esperó a que cerrara el juego, cambió los jars, quitó el viejo y conservó el igual");

		Path juegoDir = FabricLoader.getInstance().getGameDir();
		ctx.setScreen(() -> new PantallaActualizacion(null, new Paquete("9.0.0", version("minecraft"), version("fabricloader"), paquete.mods()), null, juegoDir, juegoDir));
		ctx.waitTicks(5);
		ctx.takeScreenshot("actualizador_01_pregunta");
		ctx.setScreen(() -> new PantallaActualizacion(null, new Paquete("9.0.0", "99.1", "9.9.9", paquete.mods()), null, juegoDir, juegoDir));
		ctx.waitTicks(5);
		ctx.takeScreenshot("actualizador_02_manual");
		ctx.setScreen(() -> null);
		paso("Pantallas de actualización (capturas)");
	}

	private interface Accion {
		void correr() throws Exception;
	}

	private static void falla(Accion a, String que) {
		try {
			a.correr();
		} catch (Exception e) {
			return;
		}
		throw new AssertionError("Debió rechazar: " + que);
	}

	private void publicar(String texto, KeyPair llaves) throws Exception {
		byte[] datos = texto.getBytes(StandardCharsets.UTF_8);
		Signature s = Signature.getInstance("Ed25519");
		s.initSign(llaves.getPrivate());
		s.update(datos);
		archivos.put("/paquete.txt", datos);
		archivos.put("/paquete.txt.firma", Base64.getEncoder().encode(s.sign()));
	}

	private static String mod(String nombre, byte[] datos, String url) throws Exception {
		return "mod " + nombre + " " + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(datos)) + " " + url + "\n";
	}

	private static long procesoTerminado() throws Exception {
		Process p = new ProcessBuilder("true").start();
		p.waitFor();
		return p.pid();
	}

	private static Set<String> nombres(Path carpeta) throws Exception {
		try (Stream<Path> s = Files.list(carpeta)) {
			return s.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
		}
	}

	private static String log(Path trabajo) throws Exception {
		return Files.exists(trabajo.resolve("aplicar.log")) ? Files.readString(trabajo.resolve("aplicar.log")) : "(sin log)";
	}

	private static String version(String mod) {
		return FabricLoader.getInstance().getModContainer(mod).orElseThrow().getMetadata().getVersion().getFriendlyString();
	}

	private static void comprobar(boolean condicion, String que) {
		if (!condicion) throw new AssertionError("Falló: " + que);
	}

	private static void paso(String texto) {
		Venatio.LOG.info("PRUEBA ACTUALIZADOR ✔ {}", texto);
	}
}
