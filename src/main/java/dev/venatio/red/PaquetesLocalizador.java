package dev.venatio.red;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Paquetes de la barra de localización (servidor → cliente). */
public final class PaquetesLocalizador {
	private PaquetesLocalizador() {}

	public static void registrar() {
		PayloadTypeRegistry.clientboundPlay().register(Localizacion.TYPE, Localizacion.CODEC);
	}

	/**
	 * Un jugador que quien recibe puede ver en la barra. color: ordinal de ColorEquipo; x, y, z: posición de sus ojos;
	 * companero: es de su mismo equipo.
	 */
	public record PuntoRed(UUID uuid, String nombre, int color, float x, float y, float z, boolean companero) {}

	/**
	 * Jugadores visibles para quien recibe, todos en su mundo (dimension). Llega cada 10 ticks durante la partida y reemplaza
	 * la lista anterior. activa = false: no hay barra (fuera de la partida o apagada por el anfitrión).
	 */
	public record Localizacion(boolean activa, Identifier dimension, List<PuntoRed> puntos) implements CustomPacketPayload {
		public static final Localizacion APAGADA = new Localizacion(false, Identifier.withDefaultNamespace("overworld"), List.of());
		public static final Type<Localizacion> TYPE = new Type<>(Identifier.fromNamespaceAndPath("venatio", "localizacion"));
		public static final StreamCodec<FriendlyByteBuf, Localizacion> CODEC = StreamCodec.of(Localizacion::escribir, Localizacion::leer);

		@Override
		public Type<Localizacion> type() {
			return TYPE;
		}

		private static void escribir(FriendlyByteBuf buf, Localizacion l) {
			buf.writeBoolean(l.activa);
			buf.writeIdentifier(l.dimension);
			buf.writeVarInt(l.puntos.size());
			for (PuntoRed p : l.puntos) {
				buf.writeUUID(p.uuid);
				buf.writeUtf(p.nombre);
				buf.writeVarInt(p.color);
				buf.writeFloat(p.x);
				buf.writeFloat(p.y);
				buf.writeFloat(p.z);
				buf.writeBoolean(p.companero);
			}
		}

		private static Localizacion leer(FriendlyByteBuf buf) {
			boolean activa = buf.readBoolean();
			Identifier dimension = buf.readIdentifier();
			int n = buf.readVarInt();
			List<PuntoRed> puntos = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				puntos.add(new PuntoRed(buf.readUUID(), buf.readUtf(), buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
					buf.readBoolean()));
			}
			return new Localizacion(activa, dimension, puntos);
		}
	}
}
