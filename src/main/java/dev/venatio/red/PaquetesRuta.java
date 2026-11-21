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
import java.util.UUID;

/** Paquetes de la ruta al final de la partida y del modo espectador. */
public final class PaquetesRuta {
	/**
	 * Máximo de datos por trozo de ruta. El límite de un paquete personalizado hacia el cliente es 1 MiB;
	 * se deja de sobra para los iconos y la cabecera.
	 */
	public static final int TAM_TROZO = 256 * 1024;

	private PaquetesRuta() {}

	public static void registrar() {
		PayloadTypeRegistry.clientboundPlay().register(RutaTrozo.TYPE, RutaTrozo.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(EstadoEspectador.TYPE, EstadoEspectador.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(AccionEspectador.TYPE, AccionEspectador.CODEC);
	}

	private static Identifier id(String nombre) {
		return Identifier.fromNamespaceAndPath("venatio", nombre);
	}

	/**
	 * Servidor → cliente: un trozo de la ruta comprimida (DatosRuta.comprimir). Los trozos de un envío comparten número de envío; el
	 * cliente los junta y, cuando tiene los total, la descomprime. iconos (los items de las casillas) solo va en el
	 * trozo 0. abrir: al completarla, abrir la pantalla de la ruta (la pidió con /venatio ruta).
	 */
	public record RutaTrozo(int envio, int indice, int total, boolean abrir, List<ItemStack> iconos, byte[] datos)
		implements CustomPacketPayload {

		public static final Type<RutaTrozo> TYPE = new Type<>(id("ruta_trozo"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RutaTrozo> CODEC = StreamCodec.of(RutaTrozo::escribir, RutaTrozo::leer);

		@Override
		public Type<RutaTrozo> type() {
			return TYPE;
		}

		private static void escribir(RegistryFriendlyByteBuf buf, RutaTrozo t) {
			buf.writeVarInt(t.envio);
			buf.writeVarInt(t.indice);
			buf.writeVarInt(t.total);
			buf.writeBoolean(t.abrir);
			buf.writeVarInt(t.iconos.size());
			for (ItemStack s : t.iconos) ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, s);
			buf.writeByteArray(t.datos);
		}

		private static RutaTrozo leer(RegistryFriendlyByteBuf buf) {
			int envio = buf.readVarInt();
			int indice = buf.readVarInt();
			int total = buf.readVarInt();
			boolean abrir = buf.readBoolean();
			int n = buf.readVarInt();
			List<ItemStack> iconos = new ArrayList<>(n);
			for (int i = 0; i < n; i++) iconos.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
			return new RutaTrozo(envio, indice, total, abrir, iconos, buf.readByteArray(TAM_TROZO));
		}
	}

	/**
	 * Servidor → cliente: el modo espectador de este jugador. activo false = ya no mira (o nunca miró).
	 * libre: vista libre (no sigue a nadie). jugador/nombre/equipo: a quién sigue (o siguió por última vez).
	 */
	public record EstadoEspectador(boolean activo, boolean libre, UUID jugador, String nombre, int equipo)
		implements CustomPacketPayload {

		public static final EstadoEspectador INACTIVO = new EstadoEspectador(false, true, new UUID(0, 0), "", -1);

		public static final Type<EstadoEspectador> TYPE = new Type<>(id("estado_espectador"));
		public static final StreamCodec<FriendlyByteBuf, EstadoEspectador> CODEC = StreamCodec.of(
			(buf, e) -> {
				buf.writeBoolean(e.activo);
				buf.writeBoolean(e.libre);
				buf.writeUUID(e.jugador);
				buf.writeUtf(e.nombre);
				buf.writeVarInt(e.equipo + 1);
			},
			buf -> new EstadoEspectador(buf.readBoolean(), buf.readBoolean(), buf.readUUID(), buf.readUtf(), buf.readVarInt() - 1));

		@Override
		public Type<EstadoEspectador> type() {
			return TYPE;
		}
	}

	/** Cliente → servidor: el espectador pide cambiar a quién sigue. */
	public record AccionEspectador(int tipo) implements CustomPacketPayload {
		public static final int SIGUIENTE = 0, ANTERIOR = 1, EQUIPO_SIGUIENTE = 2, EQUIPO_ANTERIOR = 3, LIBRE = 4;

		public static final Type<AccionEspectador> TYPE = new Type<>(id("accion_espectador"));
		public static final StreamCodec<FriendlyByteBuf, AccionEspectador> CODEC = StreamCodec.of(
			(buf, a) -> buf.writeVarInt(a.tipo), buf -> new AccionEspectador(buf.readVarInt()));

		@Override
		public Type<AccionEspectador> type() {
			return TYPE;
		}
	}
}
