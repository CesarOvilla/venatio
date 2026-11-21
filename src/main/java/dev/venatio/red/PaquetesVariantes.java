package dev.venatio.red;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Variantes del cartón (cartón oculto, draft, casilla secreta y relevo). Lo que necesita el cliente viaja dentro de
 * {@link Paquetes.Estado}; el veto del draft es el único paquete propio.
 */
public final class PaquetesVariantes {
	private PaquetesVariantes() {}

	public static void registrar() {
		PayloadTypeRegistry.serverboundPlay().register(Vetar.TYPE, Vetar.CODEC);
	}

	/** Las casillas destapadas de un equipo (bit i = casilla i). */
	public record Mascara(int equipo, long casillas) {}

	/** La casilla secreta de un equipo. Antes del final solo se manda la del equipo que la recibe. */
	public record Secreta(int equipo, int casilla) {}

	/** Relevo: a quién le toca la fila (0 = la de arriba) de un equipo. */
	public record Fila(int equipo, int fila, String jugador) {}

	/** Un veto del draft: quién, qué casilla, qué pedía antes y qué pide ahora. */
	public record Veto(int equipo, String jugador, int casilla, ItemStack antes, ItemStack despues) {}

	/** Draft en curso o terminado. segundos: lo que queda (0 si ya terminó); vetos: cuántas casillas veta cada equipo. */
	public record Draft(boolean enCurso, int segundos, int vetos, List<Veto> vetados) {
		public int usados(int equipo) {
			int n = 0;
			for (Veto v : vetados) if (v.equipo == equipo) n++;
			return n;
		}

		public Veto vetoDe(int casilla) {
			for (Veto v : vetados) if (v.casilla == casilla) return v;
			return null;
		}
	}

	/**
	 * Estado de las variantes para un jugador. oculto: el cartón está tapado ahora (no durante el draft ni al final);
	 * destapadas: lo que destapó cada equipo; draft: null si la partida no tiene draft.
	 */
	public record Variantes(boolean oculto, List<Mascara> destapadas, List<Secreta> secretas, List<Fila> filas, Draft draft) {
		public static final Variantes NINGUNA = new Variantes(false, List.of(), List.of(), List.of(), null);

		/** Si el equipo destapó la casilla. Sin cartón oculto, todas están destapadas. */
		public boolean destapada(int equipo, int casilla) {
			if (!oculto) return true;
			for (Mascara m : destapadas) if (m.equipo == equipo) return (m.casillas >>> casilla & 1L) != 0;
			return false;
		}

		/** Si algún equipo la destapó (lo que ve quien no juega). */
		public boolean destapadaPorAlguno(int casilla) {
			if (!oculto) return true;
			for (Mascara m : destapadas) if ((m.casillas >>> casilla & 1L) != 0) return true;
			return false;
		}

		/** Casilla secreta del equipo, o -1 si no se conoce. */
		public int secretaDe(int equipo) {
			for (Secreta s : secretas) if (s.equipo == equipo) return s.casilla;
			return -1;
		}

		public boolean relevo() {
			return !filas.isEmpty();
		}

		/** A quién le toca la fila de ese equipo; null sin relevo. */
		public String duenoDeFila(int equipo, int fila) {
			for (Fila f : filas) if (f.equipo == equipo && f.fila == fila) return f.jugador;
			return null;
		}

		public boolean draftEnCurso() {
			return draft != null && draft.enCurso;
		}
	}

	public static final StreamCodec<RegistryFriendlyByteBuf, Variantes> CODEC = StreamCodec.of(PaquetesVariantes::escribir, PaquetesVariantes::leer);

	private static void escribir(RegistryFriendlyByteBuf buf, Variantes v) {
		buf.writeBoolean(v.oculto);
		buf.writeVarInt(v.destapadas.size());
		for (Mascara m : v.destapadas) {
			buf.writeVarInt(m.equipo);
			buf.writeLong(m.casillas);
		}
		buf.writeVarInt(v.secretas.size());
		for (Secreta s : v.secretas) {
			buf.writeVarInt(s.equipo);
			buf.writeVarInt(s.casilla);
		}
		buf.writeVarInt(v.filas.size());
		for (Fila f : v.filas) {
			buf.writeVarInt(f.equipo);
			buf.writeVarInt(f.fila);
			buf.writeUtf(f.jugador);
		}
		buf.writeBoolean(v.draft != null);
		if (v.draft != null) {
			buf.writeBoolean(v.draft.enCurso);
			buf.writeVarInt(v.draft.segundos);
			buf.writeVarInt(v.draft.vetos);
			buf.writeVarInt(v.draft.vetados.size());
			for (Veto x : v.draft.vetados) {
				buf.writeVarInt(x.equipo);
				buf.writeUtf(x.jugador);
				buf.writeVarInt(x.casilla);
				ItemStack.STREAM_CODEC.encode(buf, x.antes);
				ItemStack.STREAM_CODEC.encode(buf, x.despues);
			}
		}
	}

	private static Variantes leer(RegistryFriendlyByteBuf buf) {
		boolean oculto = buf.readBoolean();
		int n = buf.readVarInt();
		List<Mascara> destapadas = new ArrayList<>(n);
		for (int i = 0; i < n; i++) destapadas.add(new Mascara(buf.readVarInt(), buf.readLong()));
		n = buf.readVarInt();
		List<Secreta> secretas = new ArrayList<>(n);
		for (int i = 0; i < n; i++) secretas.add(new Secreta(buf.readVarInt(), buf.readVarInt()));
		n = buf.readVarInt();
		List<Fila> filas = new ArrayList<>(n);
		for (int i = 0; i < n; i++) filas.add(new Fila(buf.readVarInt(), buf.readVarInt(), buf.readUtf()));
		Draft draft = null;
		if (buf.readBoolean()) {
			boolean enCurso = buf.readBoolean();
			int segundos = buf.readVarInt();
			int vetos = buf.readVarInt();
			n = buf.readVarInt();
			List<Veto> vetados = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				vetados.add(new Veto(buf.readVarInt(), buf.readUtf(), buf.readVarInt(), ItemStack.STREAM_CODEC.decode(buf),
					ItemStack.STREAM_CODEC.decode(buf)));
			}
			draft = new Draft(enCurso, segundos, vetos, vetados);
		}
		return new Variantes(oculto, destapadas, secretas, filas, draft);
	}

	/** Cliente → servidor: vetar esa casilla en el draft. El servidor comprueba todo. */
	public record Vetar(int casilla) implements CustomPacketPayload {
		public static final Type<Vetar> TYPE = new Type<>(Identifier.fromNamespaceAndPath("venatio", "vetar"));
		public static final StreamCodec<FriendlyByteBuf, Vetar> CODEC = StreamCodec.of(
			(buf, v) -> buf.writeVarInt(v.casilla), buf -> new Vetar(buf.readVarInt()));

		@Override
		public Type<Vetar> type() {
			return TYPE;
		}
	}
}
