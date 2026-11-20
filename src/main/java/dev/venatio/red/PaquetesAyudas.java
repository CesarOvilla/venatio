package dev.venatio.red;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Contrato compartido entre módulos: fichas (piden guías), ayudas (las cobran y guían) y minimapa (lo muestra).
 * Solo se registran aquí; cada módulo pone sus propios receptores.
 */
public final class PaquetesAyudas {
	private PaquetesAyudas() {}

	public static void registrar() {
		PayloadTypeRegistry.serverboundPlay().register(PedirGuia.TYPE, PedirGuia.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PermisoMinimapa.TYPE, PermisoMinimapa.CODEC);
	}

	/**
	 * Cliente → servidor: el jugador quiere comprar una guía hacia algo.
	 * tipo: BIOMA (id de bioma), ESTRUCTURA (id de estructura) o MOB (id de tipo de entidad).
	 */
	public record PedirGuia(int tipo, Identifier objetivo) implements CustomPacketPayload {
		public static final int BIOMA = 0, ESTRUCTURA = 1, MOB = 2;
		public static final Type<PedirGuia> TYPE = new Type<>(Identifier.fromNamespaceAndPath("venatio", "pedir_guia"));
		public static final StreamCodec<FriendlyByteBuf, PedirGuia> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.tipo);
				buf.writeIdentifier(p.objetivo);
			},
			buf -> new PedirGuia(buf.readVarInt(), buf.readIdentifier()));

		@Override
		public Type<PedirGuia> type() {
			return TYPE;
		}
	}

	/** Servidor → cliente: el minimapa queda habilitado `segundos` segundos con `radio` bloques; segundos = 0 lo apaga. */
	public record PermisoMinimapa(int segundos, int radio) implements CustomPacketPayload {
		public static final Type<PermisoMinimapa> TYPE = new Type<>(Identifier.fromNamespaceAndPath("venatio", "permiso_minimapa"));
		public static final StreamCodec<FriendlyByteBuf, PermisoMinimapa> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.segundos);
				buf.writeVarInt(p.radio);
			},
			buf -> new PermisoMinimapa(buf.readVarInt(), buf.readVarInt()));

		@Override
		public Type<PermisoMinimapa> type() {
			return TYPE;
		}
	}
}
