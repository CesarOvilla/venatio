package dev.venatio.red;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Paquetes de Switcheroo y de los eventos sorpresa (servidor → cliente). */
public final class PaquetesEventos {
	private PaquetesEventos() {}

	public static void registrar() {
		PayloadTypeRegistry.clientboundPlay().register(Sorpresas.TYPE, Sorpresas.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Aviso.TYPE, Aviso.CODEC);
	}

	private static Identifier id(String nombre) {
		return Identifier.fromNamespaceAndPath("venatio", nombre);
	}

	/**
	 * Cada segundo de juego y en cada cambio: lo que el HUD necesita. Todo en segundos de partida.
	 * cambio: cuándo cambia solo el item de Switcheroo (-1 si no es Switcheroo); duracion: lo que dura cada item.
	 * evento: id del evento anunciado o en curso ("" si no hay); desde: cuándo empieza (antes es el anuncio);
	 * hasta: cuándo deja de mostrarse.
	 */
	public record Sorpresas(int cambio, int duracion, String evento, int desde, int hasta) implements CustomPacketPayload {
		public static final Sorpresas NADA = new Sorpresas(-1, 0, "", -1, -1);
		public static final Type<Sorpresas> TYPE = new Type<>(id("sorpresas"));
		public static final StreamCodec<FriendlyByteBuf, Sorpresas> CODEC = StreamCodec.of(
			(buf, s) -> {
				buf.writeVarInt(s.cambio + 1);
				buf.writeVarInt(s.duracion);
				buf.writeUtf(s.evento);
				buf.writeVarInt(s.desde + 1);
				buf.writeVarInt(s.hasta + 1);
			},
			buf -> new Sorpresas(buf.readVarInt() - 1, buf.readVarInt(), buf.readUtf(), buf.readVarInt() - 1, buf.readVarInt() - 1));

		@Override
		public Type<Sorpresas> type() {
			return TYPE;
		}
	}

	/**
	 * Algo que merece banner y sonido. PUNTO: un equipo consiguió primero el item de Switcheroo (item = ese item).
	 * CAMBIO: el item de Switcheroo cambió sin que nadie lo consiguiera (item = el que se fue). ANUNCIO, EMPIEZA y TERMINA:
	 * un evento sorpresa (evento = su id).
	 */
	public record Aviso(int tipo, int equipo, ItemStack item, String evento) implements CustomPacketPayload {
		public static final int PUNTO = 0, CAMBIO = 1, ANUNCIO = 2, EMPIEZA = 3, TERMINA = 4;
		public static final Type<Aviso> TYPE = new Type<>(id("aviso"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Aviso> CODEC = StreamCodec.of(
			(buf, a) -> {
				buf.writeVarInt(a.tipo);
				buf.writeVarInt(a.equipo + 1);
				ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, a.item);
				buf.writeUtf(a.evento);
			},
			buf -> new Aviso(buf.readVarInt(), buf.readVarInt() - 1, ItemStack.OPTIONAL_STREAM_CODEC.decode(buf), buf.readUtf()));

		@Override
		public Type<Aviso> type() {
			return TYPE;
		}
	}
}
