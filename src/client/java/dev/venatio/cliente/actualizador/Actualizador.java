package dev.venatio.cliente.actualizador;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

/**
 * Busca y prepara versiones nuevas. Solo acepta un paquete con firma válida (la clave privada no está en el repo: aunque
 * alguien entrara a la cuenta de GitHub no podría mandar otro código), más nuevo que el instalado, con jars de Modrinth o de
 * los releases de Venatio y con la huella SHA-512 exacta.
 */
public final class Actualizador {
	private static final String URL_PAQUETE = "https://raw.githubusercontent.com/CesarOvilla/venatio/main/instalador/paquete.txt";
	private static final String CLAVE_PUBLICA = "MCowBQYDK2VwAyEAE6uz3mCaf1AKk1nqcqVJU5Z1c09B5rPcNRlIDZ0F9so=";
	private static final String RUTA_RELEASES = "/CesarOvilla/venatio/releases/download/";
	private static final int MAX_PAQUETE = 64 * 1024, MAX_FIRMA = 1024;
	private static final long MAX_JAR = 64L * 1024 * 1024;

	private final String urlPaquete;
	private final PublicKey clave;
	private final boolean permitirLocal;
	private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
		.connectTimeout(Duration.ofSeconds(10)).build();

	private Actualizador(String urlPaquete, PublicKey clave, boolean permitirLocal) {
		this.urlPaquete = urlPaquete;
		this.clave = clave;
		this.permitirLocal = permitirLocal;
	}

	public static Actualizador oficial() throws GeneralSecurityException {
		byte[] publica = Base64.getDecoder().decode(CLAVE_PUBLICA);
		return new Actualizador(URL_PAQUETE, KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(publica)), false);
	}

	/** Pruebas: otro origen y otra clave, con descargas desde 127.0.0.1. */
	public static Actualizador paraPruebas(String urlPaquete, PublicKey clave) {
		return new Actualizador(urlPaquete, clave, true);
	}

	/** true si "nueva" es mayor que "actual", comparando número por número (0.10.0 &gt; 0.9.0). */
	public static boolean esMasNueva(String nueva, String actual) {
		String[] a = nueva.split("\\."), b = actual.split("\\.");
		for (int i = 0; i < Math.max(a.length, b.length); i++) {
			int x = i < a.length ? Integer.parseInt(a[i]) : 0, y = i < b.length ? Integer.parseInt(b[i]) : 0;
			if (x != y) return x > y;
		}
		return false;
	}

	/** Baja el paquete y su firma. Lanza una excepción si la firma no es válida. */
	public Paquete buscar() throws IOException, InterruptedException, GeneralSecurityException {
		byte[] datos = bajar(urlPaquete, MAX_PAQUETE);
		byte[] firma = Base64.getDecoder().decode(new String(bajar(urlPaquete + ".firma", MAX_FIRMA), StandardCharsets.UTF_8).trim());
		Signature verificador = Signature.getInstance("Ed25519");
		verificador.initVerify(clave);
		verificador.update(datos);
		if (!verificador.verify(firma)) throw new GeneralSecurityException("Firma del paquete inválida");
		return Paquete.leer(new String(datos, StandardCharsets.UTF_8));
	}

	/**
	 * Deja en raiz/nueva los jars que cambian (los que ya están iguales no se bajan) y plan.txt con los que deben quedar.
	 * No toca mods/: eso lo hace {@link Aplicador} cuando el juego cierre.
	 */
	public void preparar(Paquete p, Path mods, Path raiz, IntConsumer progreso) throws IOException, InterruptedException, GeneralSecurityException {
		Path nueva = raiz.resolve("nueva"), jars = nueva.resolve("jars");
		borrar(nueva);
		Files.createDirectories(jars);
		List<String> plan = new ArrayList<>();
		int hechos = 0;
		for (Paquete.Mod m : p.mods()) {
			plan.add(m.nombre());
			Path actual = mods.resolve(m.nombre());
			if (!Files.isRegularFile(actual) || !m.sha512().equals(huella(actual))) {
				if (!permitida(m.url())) throw new GeneralSecurityException("Origen no permitido: " + m.url());
				descargar(m, jars.resolve(m.nombre()));
			}
			progreso.accept(++hechos);
		}
		Files.write(nueva.resolve("plan.txt"), plan);
	}

	/** Copia el Aplicador fuera de mods/ y lo deja esperando a que cierre el proceso pid. */
	public static Process lanzarAplicador(Path raiz, Path mods, long pid) throws IOException {
		String recurso = Aplicador.class.getName().replace('.', '/') + ".class";
		Path clase = raiz.resolve("aplicador").resolve(recurso);
		Files.createDirectories(clase.getParent());
		try (InputStream in = Aplicador.class.getClassLoader().getResourceAsStream(recurso)) {
			if (in == null) throw new IOException("No se encontró " + recurso);
			Files.copy(in, clase, StandardCopyOption.REPLACE_EXISTING);
		}
		String java = ProcessHandle.current().info().command()
			.orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
		return new ProcessBuilder(java, "-Djava.awt.headless=true", "-Xmx32m", "-cp", raiz.resolve("aplicador").toAbsolutePath().toString(),
			Aplicador.class.getName(), String.valueOf(pid), raiz.resolve("nueva").toAbsolutePath().toString(), mods.toAbsolutePath().toString())
			.directory(raiz.toFile()).redirectErrorStream(true).redirectOutput(raiz.resolve("aplicar.log").toFile()).start();
	}

	/** Al abrir el juego: quita lo que haya quedado de una actualización que no se terminó. */
	public static void limpiar(Path raiz) throws IOException {
		borrar(raiz.resolve("nueva"));
	}

	private boolean permitida(String url) {
		URI u = URI.create(url);
		String host = u.getHost(), esquema = u.getScheme();
		if (host == null) return false;
		if (permitirLocal && "http".equals(esquema) && host.equals("127.0.0.1")) return true;
		if (!"https".equals(esquema)) return false;
		return host.equals("cdn.modrinth.com") || host.equals("github.com") && u.getPath().startsWith(RUTA_RELEASES);
	}

	private byte[] bajar(String url, int maximo) throws IOException, InterruptedException {
		try (InputStream in = abrir(url)) {
			byte[] datos = in.readNBytes(maximo + 1);
			if (datos.length > maximo) throw new IOException("Respuesta demasiado grande: " + url);
			return datos;
		}
	}

	private void descargar(Paquete.Mod m, Path destino) throws IOException, InterruptedException, GeneralSecurityException {
		Path parcial = destino.resolveSibling(destino.getFileName() + ".parte");
		MessageDigest sha = MessageDigest.getInstance("SHA-512");
		long total = 0;
		try (InputStream in = abrir(m.url()); OutputStream out = Files.newOutputStream(parcial)) {
			byte[] b = new byte[64 * 1024];
			for (int n; (n = in.read(b)) > 0; ) {
				total += n;
				if (total > MAX_JAR) throw new IOException("Archivo demasiado grande: " + m.nombre());
				sha.update(b, 0, n);
				out.write(b, 0, n);
			}
		}
		if (!m.sha512().equals(HexFormat.of().formatHex(sha.digest()))) {
			Files.deleteIfExists(parcial);
			throw new GeneralSecurityException("Huella distinta: " + m.nombre());
		}
		Files.move(parcial, destino, StandardCopyOption.REPLACE_EXISTING);
	}

	private InputStream abrir(String url) throws IOException, InterruptedException {
		HttpRequest pedido = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("User-Agent", "venatio-actualizador").build();
		HttpResponse<InputStream> r = http.send(pedido, HttpResponse.BodyHandlers.ofInputStream());
		if (r.statusCode() != 200) {
			r.body().close();
			throw new IOException("HTTP " + r.statusCode() + ": " + url);
		}
		return r.body();
	}

	private static String huella(Path archivo) throws IOException, GeneralSecurityException {
		MessageDigest sha = MessageDigest.getInstance("SHA-512");
		try (InputStream in = Files.newInputStream(archivo)) {
			byte[] b = new byte[64 * 1024];
			for (int n; (n = in.read(b)) > 0; ) sha.update(b, 0, n);
		}
		return HexFormat.of().formatHex(sha.digest());
	}

	private static void borrar(Path p) throws IOException {
		if (!Files.exists(p)) return;
		try (Stream<Path> s = Files.walk(p)) {
			for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
		}
	}
}
