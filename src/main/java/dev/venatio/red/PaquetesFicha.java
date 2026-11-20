package dev.venatio.red;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;

import java.util.List;
import java.util.Optional;

/** Paquetes del módulo de fichas: cómo se consigue un item y qué items pueden salir en el cartón. */
public final class PaquetesFicha {
	private PaquetesFicha() {}

	public static void registrar() {
		PayloadTypeRegistry.serverboundPlay().register(PedirFicha.TYPE, PedirFicha.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(PedirItemsPosibles.TYPE, PedirItemsPosibles.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Ficha.TYPE, Ficha.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ItemsPosibles.TYPE, ItemsPosibles.CODEC);
	}

	private static Identifier id(String nombre) {
		return Identifier.fromNamespaceAndPath("venatio", nombre);
	}

	private static final StreamCodec<io.netty.buffer.ByteBuf, List<Identifier>> IDS = Identifier.STREAM_CODEC.apply(ByteBufCodecs.list());
	private static final StreamCodec<io.netty.buffer.ByteBuf, List<String>> TEXTOS = ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list());

	/** Cliente → servidor: quiero la ficha de este item. */
	public record PedirFicha(Identifier item) implements CustomPacketPayload {
		public static final Type<PedirFicha> TYPE = new Type<>(id("pedir_ficha"));
		public static final StreamCodec<FriendlyByteBuf, PedirFicha> CODEC = StreamCodec.of(
			(buf, p) -> buf.writeIdentifier(p.item), buf -> new PedirFicha(buf.readIdentifier()));

		@Override
		public Type<PedirFicha> type() {
			return TYPE;
		}
	}

	/** Cliente → servidor: quiero la lista de items que pueden salir en el cartón. */
	public record PedirItemsPosibles() implements CustomPacketPayload {
		public static final PedirItemsPosibles INSTANCIA = new PedirItemsPosibles();
		public static final Type<PedirItemsPosibles> TYPE = new Type<>(id("pedir_items_posibles"));
		public static final StreamCodec<FriendlyByteBuf, PedirItemsPosibles> CODEC = StreamCodec.unit(INSTANCIA);

		@Override
		public Type<PedirItemsPosibles> type() {
			return TYPE;
		}
	}

	/** Servidor → cliente: entradas del pool tal cual ("minecraft:cake", o "#minecraft:signs" para un grupo). */
	public record ItemsPosibles(List<String> entradas) implements CustomPacketPayload {
		public static final Type<ItemsPosibles> TYPE = new Type<>(id("items_posibles"));
		public static final StreamCodec<FriendlyByteBuf, ItemsPosibles> CODEC = StreamCodec.of(
			(buf, p) -> TEXTOS.encode(buf, p.entradas), buf -> new ItemsPosibles(TEXTOS.decode(buf)));

		@Override
		public Type<ItemsPosibles> type() {
			return TYPE;
		}
	}

	/** Receta del soporte para pociones (no tiene RecipeDisplay en vanilla). */
	public record Destilacion(SlotDisplay entrada, SlotDisplay reactivo, SlotDisplay resultado) {
		static final StreamCodec<RegistryFriendlyByteBuf, Destilacion> CODEC = StreamCodec.composite(
			SlotDisplay.STREAM_CODEC, Destilacion::entrada,
			SlotDisplay.STREAM_CODEC, Destilacion::reactivo,
			SlotDisplay.STREAM_CODEC, Destilacion::resultado,
			Destilacion::new);
	}

	/**
	 * De dónde sale un item.
	 * <ul>
	 *   <li>MOB: id = tipo de entidad; biomas y estructuras donde aparece.</li>
	 *   <li>ESTRUCTURA: id = grupo de estructura (venatio.fichas.estructura.&lt;path&gt;).</li>
	 *   <li>BLOQUE: id = bloque.</li>
	 *   <li>ALDEANO: id = profesión (o minecraft:wandering_trader); modo = nivel mínimo (0 si no aplica).</li>
	 *   <li>BIOMA: id = bioma (pista escrita a mano).</li>
	 *   <li>PESCA y TRUEQUE (piglins): id informativo.</li>
	 * </ul>
	 * guia: a qué apunta el botón "Guíame" (bioma, estructura o mob, según el tipo); vacío si no se puede guiar.
	 */
	public record Fuente(int tipo, Identifier id, int modo, Optional<Identifier> guia, List<Identifier> estructuras,
						 List<Identifier> biomas) {
		public static final int MOB = 0, ESTRUCTURA = 1, BLOQUE = 2, ALDEANO = 3, BIOMA = 4, PESCA = 5, TRUEQUE = 6;

		// Modos de MOB
		public static final int MATAR = 0, CARGADO = 1, TRASQUILAR = 2, CEPILLAR = 3, REGALO_GATO = 4, REGALO_HEROE = 5,
			SOLTAR = 6, ESTORNUDO = 7, DESENTIERRA = 8, CRECER = 9, PONE = 10, CURADO = 11;
		// Modos de ESTRUCTURA
		public static final int COFRE = 0, BARRIL = 1, ARQUEOLOGIA = 2, DISPENSADOR = 3, VASIJA = 4, GENERADOR = 5,
			ARCON = 6, NATURAL = 7;
		// Modos de BLOQUE
		public static final int ROMPER = 0, COSECHAR = 1, TALLAR = 2, LABRAR = 3;
		// Modos de PESCA
		public static final int PESCA_GENERAL = 0, PESCA_PEZ = 1, PESCA_BASURA = 2, PESCA_TESORO = 3;

		static final StreamCodec<RegistryFriendlyByteBuf, Fuente> CODEC = StreamCodec.of(
			(buf, f) -> {
				buf.writeVarInt(f.tipo);
				buf.writeIdentifier(f.id);
				buf.writeVarInt(f.modo);
				buf.writeOptional(f.guia, FriendlyByteBuf::writeIdentifier);
				IDS.encode(buf, f.estructuras);
				IDS.encode(buf, f.biomas);
			},
			buf -> new Fuente(buf.readVarInt(), buf.readIdentifier(), buf.readVarInt(), buf.readOptional(FriendlyByteBuf::readIdentifier),
				IDS.decode(buf), IDS.decode(buf)));
	}

	/**
	 * Servidor → cliente: todo lo que la pantalla de ficha muestra de un item.
	 * grupo: si el item cuenta para una casilla de "cualquier X" del pool, el tag de esa casilla.
	 */
	public record Ficha(Identifier item, List<RecipeDisplay> recetas, List<Destilacion> destilaciones, List<Fuente> fuentes,
						String pista, Optional<Identifier> grupo) implements CustomPacketPayload {
		public static final Type<Ficha> TYPE = new Type<>(id("ficha"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Ficha> CODEC = StreamCodec.of(
			(buf, f) -> {
				buf.writeIdentifier(f.item);
				RecipeDisplay.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, f.recetas);
				Destilacion.CODEC.apply(ByteBufCodecs.list()).encode(buf, f.destilaciones);
				Fuente.CODEC.apply(ByteBufCodecs.list()).encode(buf, f.fuentes);
				buf.writeUtf(f.pista);
				buf.writeOptional(f.grupo, FriendlyByteBuf::writeIdentifier);
			},
			buf -> new Ficha(buf.readIdentifier(),
				RecipeDisplay.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
				Destilacion.CODEC.apply(ByteBufCodecs.list()).decode(buf),
				Fuente.CODEC.apply(ByteBufCodecs.list()).decode(buf),
				buf.readUtf(),
				buf.readOptional(FriendlyByteBuf::readIdentifier)));

		@Override
		public Type<Ficha> type() {
			return TYPE;
		}
	}
}
