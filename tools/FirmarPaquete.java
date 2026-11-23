import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Firma instalador/paquete.txt para el autoactualizador. La clave privada vive fuera del repo.
 *   java tools/FirmarPaquete.java generar          crea la clave (una sola vez) e imprime la pública
 *   java tools/FirmarPaquete.java firmar <archivo> escribe <archivo>.firma
 */
public class FirmarPaquete {
	private static final Path CLAVE = Path.of(System.getProperty("user.home"), ".config", "venatio", "firma-paquete.key");

	public static void main(String[] args) throws Exception {
		if (args.length == 1 && args[0].equals("generar")) {
			if (Files.exists(CLAVE)) throw new IllegalStateException("Ya existe la clave: " + CLAVE);
			Files.createDirectories(CLAVE.getParent());
			KeyPair par = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
			Files.createFile(CLAVE, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
			Files.writeString(CLAVE, Base64.getEncoder().encodeToString(par.getPrivate().getEncoded()) + "\n");
			System.out.println(Base64.getEncoder().encodeToString(par.getPublic().getEncoded()));
		} else if (args.length == 2 && args[0].equals("firmar")) {
			byte[] privada = Base64.getDecoder().decode(Files.readString(CLAVE).trim());
			Signature firma = Signature.getInstance("Ed25519");
			firma.initSign(KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(privada)));
			Path archivo = Path.of(args[1]);
			firma.update(Files.readAllBytes(archivo));
			Files.writeString(Path.of(args[1] + ".firma"), Base64.getEncoder().encodeToString(firma.sign()) + "\n");
			System.out.println("Firmado: " + args[1] + ".firma");
		} else {
			throw new IllegalArgumentException("Uso: generar | firmar <archivo>");
		}
	}
}
