package dev.venatio.ruta;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Ruta de una partida: por dónde anduvo cada jugador, qué pasó en cada sitio y una muestra del terreno explorado.
 * La arma el servidor ({@link GrabadoraRuta}) y viaja comprimida al cliente, que la dibuja en la pestaña "Ruta".
 * Los iconos de las casillas (ItemStack) no van aquí: viajan aparte en el primer trozo del paquete y los eventos
 * los citan por índice.
 *
 * @param duracion  segundos de juego que abarca
 * @param enVivo    foto tomada a media partida (espectadores); si no, la ruta final
 * @param jugadores recorrido de cada participante
 * @param eventos   casillas, muertes, portales y ayudas, en orden de tiempo
 * @param spawns    punto de aparición de cada equipo
 * @param terrenos  muestra del terreno por dimensión (solo las que tienen algo)
 */
public record DatosRuta(int duracion, boolean enVivo, List<Jugador> jugadores, List<Evento> eventos, List<Spawn> spawns,
						List<Terreno> terrenos) {

	/** Dimensiones de la partida. */
	public static final int MUNDO = 0, NETHER = 1, END = 2;
	/** Lado en bloques de cada píxel de terreno: un chunk son 4×4 muestras. */
	public static final int BLOQUES_POR_MUESTRA = 4;
	public static final int MUESTRAS_POR_LADO = 16 / BLOQUES_POR_MUESTRA;
	public static final int MUESTRAS_POR_CHUNK = MUESTRAS_POR_LADO * MUESTRAS_POR_LADO;

	/** Tipos de evento. */
	public static final int CASILLA = 0, MUERTE = 1, PORTAL_SALIDA = 2, PORTAL_LLEGADA = 3, AYUDA = 4;

	/** Tope al descomprimir (la ruta de una partida larga ocupa unos cientos de KB). */
	private static final int MAX_DESCOMPRIMIDO = 64 * 1024 * 1024;

	/** corte: aquí empieza un tramo nuevo (después de morir o de cambiar de dimensión); no se une con el anterior. */
	public record Punto(int segundo, int dim, int x, int z, boolean corte) {}

	/** equipo: ordinal de ColorEquipo. */
	public record Jugador(UUID uuid, String nombre, int equipo, List<Punto> puntos) {}

	/**
	 * jugador: índice en jugadores. icono: índice en la lista de iconos (casillas) o -1.
	 * extra: en los portales, la dimensión del otro lado.
	 */
	public record Evento(int tipo, int jugador, int segundo, int dim, int x, int z, int icono, int extra) {}

	public record Spawn(int equipo, int dim, int x, int z) {}

	/**
	 * Muestra del terreno de una dimensión: por cada chunk (clave de ChunkPos.pack) muestras × muestras colores de mapa
	 * empaquetados (MapColor.getPackedId: id del color × 4 + brillo), fila por fila de norte a sur. 0 = sin dato.
	 * muestras: 4 (un color cada 4 bloques); en partidas con mucho terreno explorado baja a 2 o 1 para que la ruta no crezca.
	 */
	public record Terreno(int dim, int muestras, long[] chunks, byte[] colores) {
		public int cantidad() {
			return chunks.length;
		}
	}

	// ---------------------------------------------------------------- escritura

	public void escribir(FriendlyByteBuf buf) {
		buf.writeVarInt(duracion);
		buf.writeBoolean(enVivo);
		buf.writeVarInt(jugadores.size());
		for (Jugador j : jugadores) {
			buf.writeUUID(j.uuid);
			buf.writeUtf(j.nombre);
			buf.writeVarInt(j.equipo + 1);
			buf.writeVarInt(j.puntos.size());
			// En diferencias: casi todos los números quedan en uno o dos bytes y se comprimen mejor.
			int s = 0, x = 0, z = 0;
			for (Punto p : j.puntos) {
				buf.writeVarInt(p.segundo - s);
				buf.writeByte(p.dim | (p.corte ? 0x10 : 0));
				buf.writeVarInt(zigzag(p.x - x));
				buf.writeVarInt(zigzag(p.z - z));
				s = p.segundo;
				x = p.x;
				z = p.z;
			}
		}
		buf.writeVarInt(eventos.size());
		for (Evento e : eventos) {
			buf.writeVarInt(e.tipo);
			buf.writeVarInt(e.jugador + 1);
			buf.writeVarInt(e.segundo);
			buf.writeVarInt(e.dim);
			buf.writeVarInt(zigzag(e.x));
			buf.writeVarInt(zigzag(e.z));
			buf.writeVarInt(e.icono + 1);
			buf.writeVarInt(e.extra + 1);
		}
		buf.writeVarInt(spawns.size());
		for (Spawn sp : spawns) {
			buf.writeVarInt(sp.equipo);
			buf.writeVarInt(sp.dim);
			buf.writeVarInt(zigzag(sp.x));
			buf.writeVarInt(zigzag(sp.z));
		}
		buf.writeVarInt(terrenos.size());
		for (Terreno t : terrenos) {
			buf.writeVarInt(t.dim);
			buf.writeVarInt(t.muestras);
			buf.writeVarInt(t.chunks.length);
			int cx = 0, cz = 0;
			for (long c : t.chunks) {
				int x = (int) c, z = (int) (c >>> 32);
				buf.writeVarInt(zigzag(x - cx));
				buf.writeVarInt(zigzag(z - cz));
				cx = x;
				cz = z;
			}
			buf.writeBytes(t.colores);
		}
	}

	public static DatosRuta leer(FriendlyByteBuf buf) {
		int duracion = buf.readVarInt();
		boolean enVivo = buf.readBoolean();
		int nj = buf.readVarInt();
		List<Jugador> jugadores = new ArrayList<>(nj);
		for (int i = 0; i < nj; i++) {
			UUID uuid = buf.readUUID();
			String nombre = buf.readUtf();
			int equipo = buf.readVarInt() - 1;
			int np = buf.readVarInt();
			List<Punto> puntos = new ArrayList<>(np);
			int s = 0, x = 0, z = 0;
			for (int k = 0; k < np; k++) {
				s += buf.readVarInt();
				int banderas = buf.readByte();
				x += unzigzag(buf.readVarInt());
				z += unzigzag(buf.readVarInt());
				puntos.add(new Punto(s, banderas & 0x0F, x, z, (banderas & 0x10) != 0));
			}
			jugadores.add(new Jugador(uuid, nombre, equipo, puntos));
		}
		int ne = buf.readVarInt();
		List<Evento> eventos = new ArrayList<>(ne);
		for (int i = 0; i < ne; i++) {
			eventos.add(new Evento(buf.readVarInt(), buf.readVarInt() - 1, buf.readVarInt(), buf.readVarInt(),
				unzigzag(buf.readVarInt()), unzigzag(buf.readVarInt()), buf.readVarInt() - 1, buf.readVarInt() - 1));
		}
		int ns = buf.readVarInt();
		List<Spawn> spawns = new ArrayList<>(ns);
		for (int i = 0; i < ns; i++) {
			spawns.add(new Spawn(buf.readVarInt(), buf.readVarInt(), unzigzag(buf.readVarInt()), unzigzag(buf.readVarInt())));
		}
		int nt = buf.readVarInt();
		List<Terreno> terrenos = new ArrayList<>(nt);
		for (int i = 0; i < nt; i++) {
			int dim = buf.readVarInt();
			int muestras = Math.clamp(buf.readVarInt(), 1, MUESTRAS_POR_LADO);
			int n = buf.readVarInt();
			long[] chunks = new long[n];
			int cx = 0, cz = 0;
			for (int k = 0; k < n; k++) {
				cx += unzigzag(buf.readVarInt());
				cz += unzigzag(buf.readVarInt());
				chunks[k] = clave(cx, cz);
			}
			byte[] colores = new byte[n * muestras * muestras];
			buf.readBytes(colores);
			terrenos.add(new Terreno(dim, muestras, chunks, colores));
		}
		return new DatosRuta(duracion, enVivo, jugadores, eventos, spawns, terrenos);
	}

	/** Igual que ChunkPos.pack: x en los 32 bits bajos, z en los altos. */
	public static long clave(int cx, int cz) {
		return (long) cx & 0xFFFFFFFFL | ((long) cz & 0xFFFFFFFFL) << 32;
	}

	private static int zigzag(int n) {
		return (n << 1) ^ (n >> 31);
	}

	private static int unzigzag(int n) {
		return (n >>> 1) ^ -(n & 1);
	}

	// ---------------------------------------------------------------- compresión

	public byte[] comprimir() {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		try {
			escribir(buf);
			byte[] crudo = new byte[buf.readableBytes()];
			buf.readBytes(crudo);
			Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION);
			d.setInput(crudo);
			d.finish();
			ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, crudo.length / 3));
			byte[] bloque = new byte[16 * 1024];
			while (!d.finished()) out.write(bloque, 0, d.deflate(bloque));
			d.end();
			return out.toByteArray();
		} finally {
			buf.release();
		}
	}

	public static DatosRuta descomprimir(byte[] datos) {
		Inflater inf = new Inflater();
		inf.setInput(datos);
		ByteArrayOutputStream out = new ByteArrayOutputStream(datos.length * 3);
		byte[] bloque = new byte[16 * 1024];
		try {
			while (!inf.finished()) {
				int n = inf.inflate(bloque);
				if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw new IllegalArgumentException("Ruta incompleta");
				out.write(bloque, 0, n);
				if (out.size() > MAX_DESCOMPRIMIDO) throw new IllegalArgumentException("Ruta demasiado grande");
			}
		} catch (DataFormatException e) {
			throw new IllegalArgumentException("Ruta dañada", e);
		} finally {
			inf.end();
		}
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(out.toByteArray()));
		try {
			return leer(buf);
		} finally {
			buf.release();
		}
	}

	// ---------------------------------------------------------------- consultas

	/** ¿Hay algo que dibujar en esa dimensión? */
	public boolean tieneDimension(int dim) {
		for (Jugador j : jugadores) for (Punto p : j.puntos) if (p.dim == dim) return true;
		for (Terreno t : terrenos) if (t.dim == dim && t.cantidad() > 0) return true;
		return false;
	}

	public Terreno terreno(int dim) {
		for (Terreno t : terrenos) if (t.dim == dim) return t;
		return null;
	}
}
