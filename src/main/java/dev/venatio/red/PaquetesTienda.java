package dev.venatio.red;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Paquetes propios del módulo de ayudas: tienda, guías y marcas de equipo.
 * Las guías se piden con {@link PaquetesAyudas.PedirGuia} y el minimapa se habilita con {@link PaquetesAyudas.PermisoMinimapa}.
 */
public final class PaquetesTienda {
	private PaquetesTienda() {}

	public static void registrar() {
		PayloadTypeRegistry.serverboundPlay().register(AbrirTienda.TYPE, AbrirTienda.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ComprarMinimapa.TYPE, ComprarMinimapa.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(PonerMarca.TYPE, PonerMarca.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Tienda.TYPE, Tienda.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Guia.TYPE, Guia.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(GuiaTerminada.TYPE, GuiaTerminada.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Marcas.TYPE, Marcas.CODEC);
	}

	private static Identifier id(String nombre) {
		return Identifier.fromNamespaceAndPath("venatio", "ayudas_" + nombre);
	}

	// ---------------------------------------------------------------- cliente → servidor

	/** El jugador abrió la tienda: el servidor responde con {@link Tienda} (enfriamientos al día). */
	public record AbrirTienda() implements CustomPacketPayload {
		public static final AbrirTienda INSTANCIA = new AbrirTienda();
		public static final Type<AbrirTienda> TYPE = new Type<>(id("abrir_tienda"));
		public static final StreamCodec<FriendlyByteBuf, AbrirTienda> CODEC = StreamCodec.unit(INSTANCIA);

		@Override
		public Type<AbrirTienda> type() {
			return TYPE;
		}
	}

	public record ComprarMinimapa() implements CustomPacketPayload {
		public static final ComprarMinimapa INSTANCIA = new ComprarMinimapa();
		public static final Type<ComprarMinimapa> TYPE = new Type<>(id("comprar_minimapa"));
		public static final StreamCodec<FriendlyByteBuf, ComprarMinimapa> CODEC = StreamCodec.unit(INSTANCIA);

		@Override
		public Type<ComprarMinimapa> type() {
			return TYPE;
		}
	}

	/** Tecla de marca: el servidor calcula el bloque que mira el jugador. */
	public record PonerMarca() implements CustomPacketPayload {
		public static final PonerMarca INSTANCIA = new PonerMarca();
		public static final Type<PonerMarca> TYPE = new Type<>(id("poner_marca"));
		public static final StreamCodec<FriendlyByteBuf, PonerMarca> CODEC = StreamCodec.unit(INSTANCIA);

		@Override
		public Type<PonerMarca> type() {
			return TYPE;
		}
	}

	// ---------------------------------------------------------------- servidor → cliente

	/**
	 * Una ayuda en la tienda. tipo: ordinal de TipoAyuda; item minecraft:air = gratis.
	 * enfriamiento y restante en segundos (restante: lo que le falta a este jugador para poder volver a usarla).
	 */
	public record Oferta(int tipo, Identifier item, int cantidad, int enfriamiento, int restante) {}

	/** Catálogo con precios y enfriamientos del jugador. minimapaRestante: segundos de minimapa comprado que le quedan. */
	public record Tienda(boolean activas, List<Oferta> ofertas, int minimapaSegundos, int minimapaRadio, int minimapaRestante,
						 int marcaDuracion, int marcaMaximo) implements CustomPacketPayload {
		public static final Type<Tienda> TYPE = new Type<>(id("tienda"));
		public static final StreamCodec<FriendlyByteBuf, Tienda> CODEC = StreamCodec.of(Tienda::escribir, Tienda::leer);

		@Override
		public Type<Tienda> type() {
			return TYPE;
		}

		private static void escribir(FriendlyByteBuf buf, Tienda t) {
			buf.writeBoolean(t.activas);
			buf.writeVarInt(t.ofertas.size());
			for (Oferta o : t.ofertas) {
				buf.writeVarInt(o.tipo);
				buf.writeIdentifier(o.item);
				buf.writeVarInt(o.cantidad);
				buf.writeVarInt(o.enfriamiento);
				buf.writeVarInt(o.restante);
			}
			buf.writeVarInt(t.minimapaSegundos);
			buf.writeVarInt(t.minimapaRadio);
			buf.writeVarInt(t.minimapaRestante);
			buf.writeVarInt(t.marcaDuracion);
			buf.writeVarInt(t.marcaMaximo);
		}

		private static Tienda leer(FriendlyByteBuf buf) {
			boolean activas = buf.readBoolean();
			int n = buf.readVarInt();
			List<Oferta> ofertas = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				ofertas.add(new Oferta(buf.readVarInt(), buf.readIdentifier(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
			}
			return new Tienda(activas, ofertas, buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
		}
	}

	/**
	 * Guía activa hacia una zona aproximada (no el punto exacto). tipo: PedirGuia.BIOMA/ESTRUCTURA/MOB;
	 * objetivo: id de lo buscado (para el nombre); dimension: id del mundo donde está la zona; radio: tamaño de la zona;
	 * segundos: lo que le queda a la guía.
	 */
	public record Guia(int tipo, Identifier objetivo, Identifier dimension, int x, int z, int radio, int segundos)
		implements CustomPacketPayload {
		public static final Type<Guia> TYPE = new Type<>(id("guia"));
		public static final StreamCodec<FriendlyByteBuf, Guia> CODEC = StreamCodec.of(
			(buf, g) -> {
				buf.writeVarInt(g.tipo);
				buf.writeIdentifier(g.objetivo);
				buf.writeIdentifier(g.dimension);
				buf.writeInt(g.x);
				buf.writeInt(g.z);
				buf.writeVarInt(g.radio);
				buf.writeVarInt(g.segundos);
			},
			buf -> new Guia(buf.readVarInt(), buf.readIdentifier(), buf.readIdentifier(), buf.readInt(), buf.readInt(),
				buf.readVarInt(), buf.readVarInt()));

		@Override
		public Type<Guia> type() {
			return TYPE;
		}
	}

	/** La guía se apagó: llegó a la zona, se acabó el tiempo o se canceló (fin de partida, guía nueva). */
	public record GuiaTerminada(int motivo) implements CustomPacketPayload {
		public static final int LLEGADA = 0, TIEMPO = 1, CANCELADA = 2;
		public static final Type<GuiaTerminada> TYPE = new Type<>(id("guia_terminada"));
		public static final StreamCodec<FriendlyByteBuf, GuiaTerminada> CODEC = StreamCodec.of(
			(buf, g) -> buf.writeVarInt(g.motivo), buf -> new GuiaTerminada(buf.readVarInt()));

		@Override
		public Type<GuiaTerminada> type() {
			return TYPE;
		}
	}

	/** Una marca de equipo. color: ordinal de ColorEquipo; segundos: lo que le queda. */
	public record MarcaRed(int id, String autor, int color, Identifier dimension, int x, int y, int z, int segundos) {}

	/** Todas las marcas vigentes del equipo del jugador (reemplaza la lista anterior). */
	public record Marcas(List<MarcaRed> marcas) implements CustomPacketPayload {
		public static final Type<Marcas> TYPE = new Type<>(id("marcas"));
		public static final StreamCodec<FriendlyByteBuf, Marcas> CODEC = StreamCodec.of(Marcas::escribir, Marcas::leer);

		@Override
		public Type<Marcas> type() {
			return TYPE;
		}

		private static void escribir(FriendlyByteBuf buf, Marcas m) {
			buf.writeVarInt(m.marcas.size());
			for (MarcaRed r : m.marcas) {
				buf.writeVarInt(r.id);
				buf.writeUtf(r.autor);
				buf.writeVarInt(r.color);
				buf.writeIdentifier(r.dimension);
				buf.writeInt(r.x);
				buf.writeInt(r.y);
				buf.writeInt(r.z);
				buf.writeVarInt(r.segundos);
			}
		}

		private static Marcas leer(FriendlyByteBuf buf) {
			int n = buf.readVarInt();
			List<MarcaRed> lista = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				lista.add(new MarcaRed(buf.readVarInt(), buf.readUtf(), buf.readVarInt(), buf.readIdentifier(),
					buf.readInt(), buf.readInt(), buf.readInt(), buf.readVarInt()));
			}
			return new Marcas(lista);
		}
	}
}
