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

/** Paquetes entre el mod de cliente y el servidor. */
public final class Paquetes {
	private Paquetes() {}

	public static void registrar() {
		PayloadTypeRegistry.serverboundPlay().register(Hola.TYPE, Hola.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Estado.TYPE, Estado.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Evento.TYPE, Evento.CODEC);
	}

	private static Identifier id(String nombre) {
		return Identifier.fromNamespaceAndPath("venatio", nombre);
	}

	/** Cliente → servidor al entrar: versión del mod instalada. */
	public record Hola(String version) implements CustomPacketPayload {
		public static final Type<Hola> TYPE = new Type<>(id("hola"));
		public static final StreamCodec<FriendlyByteBuf, Hola> CODEC = StreamCodec.of(
			(buf, h) -> buf.writeUtf(h.version), buf -> new Hola(buf.readUtf()));

		@Override
		public Type<Hola> type() {
			return TYPE;
		}
	}

	public record Logro(int equipo, int segundo) {}

	public record CasillaRed(ItemStack icono, List<Logro> logros) {}

	public record EquipoRed(int color, int puntos, List<String> miembros) {}

	/**
	 * Servidor → cliente: todo lo que el HUD y la pantalla del cartón necesitan.
	 * fase: 0 lobby, 1 preparando, 2 cuenta atrás, 3 jugando, 4 final.
	 * segundos: transcurridos en juego, o restantes en la cuenta atrás.
	 * ganador: ordinal del color, -1 sin ganador todavía, -2 empate, -3 cancelada por el anfitrión.
	 */
	public record Estado(int fase, String modo, int segundos, int limite, boolean pvp, int progreso, int meta,
						 int tamano, List<CasillaRed> casillas, List<EquipoRed> equipos, int miEquipo, int ganador)
		implements CustomPacketPayload {

		public static final int LOBBY = 0, PREPARANDO = 1, CUENTA_ATRAS = 2, JUGANDO = 3, FINAL = 4;

		public static final Type<Estado> TYPE = new Type<>(id("estado"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Estado> CODEC = StreamCodec.of(Estado::escribir, Estado::leer);

		@Override
		public Type<Estado> type() {
			return TYPE;
		}

		private static void escribir(RegistryFriendlyByteBuf buf, Estado e) {
			buf.writeVarInt(e.fase);
			buf.writeUtf(e.modo);
			buf.writeVarInt(e.segundos);
			buf.writeVarInt(e.limite);
			buf.writeBoolean(e.pvp);
			buf.writeVarInt(e.progreso);
			buf.writeVarInt(e.meta);
			buf.writeVarInt(e.tamano);
			buf.writeVarInt(e.casillas.size());
			for (CasillaRed c : e.casillas) {
				ItemStack.STREAM_CODEC.encode(buf, c.icono);
				buf.writeVarInt(c.logros.size());
				for (Logro l : c.logros) {
					buf.writeVarInt(l.equipo);
					buf.writeVarInt(l.segundo);
				}
			}
			buf.writeVarInt(e.equipos.size());
			for (EquipoRed q : e.equipos) {
				buf.writeVarInt(q.color);
				buf.writeVarInt(q.puntos);
				buf.writeVarInt(q.miembros.size());
				for (String m : q.miembros) buf.writeUtf(m);
			}
			buf.writeVarInt(e.miEquipo + 1);
			buf.writeVarInt(e.ganador + 3);
		}

		private static Estado leer(RegistryFriendlyByteBuf buf) {
			int fase = buf.readVarInt();
			String modo = buf.readUtf();
			int segundos = buf.readVarInt();
			int limite = buf.readVarInt();
			boolean pvp = buf.readBoolean();
			int progreso = buf.readVarInt();
			int meta = buf.readVarInt();
			int tamano = buf.readVarInt();
			int n = buf.readVarInt();
			List<CasillaRed> casillas = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				ItemStack icono = ItemStack.STREAM_CODEC.decode(buf);
				int nl = buf.readVarInt();
				List<Logro> logros = new ArrayList<>(nl);
				for (int j = 0; j < nl; j++) logros.add(new Logro(buf.readVarInt(), buf.readVarInt()));
				casillas.add(new CasillaRed(icono, logros));
			}
			int ne = buf.readVarInt();
			List<EquipoRed> equipos = new ArrayList<>(ne);
			for (int i = 0; i < ne; i++) {
				int color = buf.readVarInt();
				int puntos = buf.readVarInt();
				int nm = buf.readVarInt();
				List<String> miembros = new ArrayList<>(nm);
				for (int j = 0; j < nm; j++) miembros.add(buf.readUtf());
				equipos.add(new EquipoRed(color, puntos, miembros));
			}
			int miEquipo = buf.readVarInt() - 1;
			int ganador = buf.readVarInt() - 3;
			return new Estado(fase, modo, segundos, limite, pvp, progreso, meta, tamano, casillas, equipos, miEquipo, ganador);
		}
	}

	/** Servidor → cliente: algo que merece animación y sonido. */
	public record Evento(int tipo, int equipo, int casilla) implements CustomPacketPayload {
		/** BINGO: un equipo cumplió el objetivo pero la partida sigue (fin automático apagado). VICTORIA: terminó. */
		public static final int CASILLA = 0, VICTORIA = 1, INICIO = 2, BINGO = 3;

		public static final Type<Evento> TYPE = new Type<>(id("evento"));
		public static final StreamCodec<FriendlyByteBuf, Evento> CODEC = StreamCodec.of(
			(buf, e) -> {
				buf.writeVarInt(e.tipo);
				buf.writeVarInt(e.equipo + 1);
				buf.writeVarInt(e.casilla + 1);
			},
			buf -> new Evento(buf.readVarInt(), buf.readVarInt() - 1, buf.readVarInt() - 1));

		@Override
		public Type<Evento> type() {
			return TYPE;
		}
	}
}
