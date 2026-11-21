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

/** Paquetes de la pantalla final y del panel del anfitrión. */
public final class PaquetesStats {
	private PaquetesStats() {}

	public static void registrar() {
		PayloadTypeRegistry.clientboundPlay().register(FinalPartida.TYPE, FinalPartida.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ConfigPanel.TYPE, ConfigPanel.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(AccionPanel.TYPE, AccionPanel.CODEC);
	}

	private static Identifier id(String nombre) {
		return Identifier.fromNamespaceAndPath("venatio", nombre);
	}

	// ---------------------------------------------------------------- pantalla final

	/** Una casilla conseguida: cuál, quién, de qué equipo (ordinal) y en qué segundo. */
	public record Hito(int casilla, ItemStack icono, String jugador, int equipo, int segundo) {}

	/** Marcador final de un equipo. puntos: los que suma al ranking de la temporada. */
	public record EquipoFinal(int color, int casillas, int puesto, int puntos, List<String> miembros) {}

	/**
	 * Servidor → cliente al terminar la partida: línea de tiempo y marcador final.
	 * ganador: ordinal del color o -1. cuenta: false si la partida no cuenta para las estadísticas (cancelada).
	 */
	public record FinalPartida(String modo, int ganador, boolean empate, boolean cuenta, int duracion, int total,
							   List<Hito> hitos, List<EquipoFinal> equipos, String destacado, int destacadoCasillas)
		implements CustomPacketPayload {

		public static final Type<FinalPartida> TYPE = new Type<>(id("final_partida"));
		public static final StreamCodec<RegistryFriendlyByteBuf, FinalPartida> CODEC = StreamCodec.of(FinalPartida::escribir, FinalPartida::leer);

		@Override
		public Type<FinalPartida> type() {
			return TYPE;
		}

		private static void escribir(RegistryFriendlyByteBuf buf, FinalPartida f) {
			buf.writeUtf(f.modo);
			buf.writeVarInt(f.ganador + 1);
			buf.writeBoolean(f.empate);
			buf.writeBoolean(f.cuenta);
			buf.writeVarInt(f.duracion);
			buf.writeVarInt(f.total);
			buf.writeVarInt(f.hitos.size());
			for (Hito h : f.hitos) {
				buf.writeVarInt(h.casilla);
				ItemStack.STREAM_CODEC.encode(buf, h.icono);
				buf.writeUtf(h.jugador);
				buf.writeVarInt(h.equipo);
				buf.writeVarInt(h.segundo);
			}
			buf.writeVarInt(f.equipos.size());
			for (EquipoFinal e : f.equipos) {
				buf.writeVarInt(e.color);
				buf.writeVarInt(e.casillas);
				buf.writeVarInt(e.puesto);
				buf.writeVarInt(e.puntos);
				buf.writeVarInt(e.miembros.size());
				for (String m : e.miembros) buf.writeUtf(m);
			}
			buf.writeUtf(f.destacado);
			buf.writeVarInt(f.destacadoCasillas);
		}

		private static FinalPartida leer(RegistryFriendlyByteBuf buf) {
			String modo = buf.readUtf();
			int ganador = buf.readVarInt() - 1;
			boolean empate = buf.readBoolean();
			boolean cuenta = buf.readBoolean();
			int duracion = buf.readVarInt();
			int total = buf.readVarInt();
			int nh = buf.readVarInt();
			List<Hito> hitos = new ArrayList<>(nh);
			for (int i = 0; i < nh; i++) {
				hitos.add(new Hito(buf.readVarInt(), ItemStack.STREAM_CODEC.decode(buf), buf.readUtf(), buf.readVarInt(), buf.readVarInt()));
			}
			int ne = buf.readVarInt();
			List<EquipoFinal> equipos = new ArrayList<>(ne);
			for (int i = 0; i < ne; i++) {
				int color = buf.readVarInt();
				int casillas = buf.readVarInt();
				int puesto = buf.readVarInt();
				int puntos = buf.readVarInt();
				int nm = buf.readVarInt();
				List<String> miembros = new ArrayList<>(nm);
				for (int j = 0; j < nm; j++) miembros.add(buf.readUtf());
				equipos.add(new EquipoFinal(color, casillas, puesto, puntos, miembros));
			}
			return new FinalPartida(modo, ganador, empate, cuenta, duracion, total, hitos, equipos, buf.readUtf(), buf.readVarInt());
		}
	}

	// ---------------------------------------------------------------- panel del anfitrión

	public record OpcionRed(String id, boolean valor) {}

	/** Ajuste que no es sí/no: el panel lo muestra como botón que rota entre valores (ver AjustesPanel). */
	public record AjusteRed(String id, String grupo, String valor, List<String> valores, String claveNombre, String prefijoValor) {}

	/**
	 * Servidor → cliente: configuración actual para el panel del anfitrión.
	 * abrir: true = abrir la pantalla (clic en el atril); false = solo refrescarla si está abierta.
	 * editable: quien la recibe es operador. semilla: solo vale si semillaFija.
	 */
	public record ConfigPanel(boolean abrir, boolean editable, boolean enPartida, String modo, int minutos, boolean pvp,
							  int distancia, boolean semillaFija, long semilla, List<OpcionRed> opciones, int maxMinutos, int maxDistancia,
							  List<AjusteRed> ajustes)
		implements CustomPacketPayload {

		public static final Type<ConfigPanel> TYPE = new Type<>(id("config_panel"));
		public static final StreamCodec<FriendlyByteBuf, ConfigPanel> CODEC = StreamCodec.of(ConfigPanel::escribir, ConfigPanel::leer);

		@Override
		public Type<ConfigPanel> type() {
			return TYPE;
		}

		private static void escribir(FriendlyByteBuf buf, ConfigPanel c) {
			buf.writeBoolean(c.abrir);
			buf.writeBoolean(c.editable);
			buf.writeBoolean(c.enPartida);
			buf.writeUtf(c.modo);
			buf.writeVarInt(c.minutos);
			buf.writeBoolean(c.pvp);
			buf.writeVarInt(c.distancia);
			buf.writeBoolean(c.semillaFija);
			buf.writeLong(c.semilla);
			buf.writeVarInt(c.opciones.size());
			for (OpcionRed o : c.opciones) {
				buf.writeUtf(o.id);
				buf.writeBoolean(o.valor);
			}
			buf.writeVarInt(c.maxMinutos);
			buf.writeVarInt(c.maxDistancia);
			buf.writeVarInt(c.ajustes.size());
			for (AjusteRed a : c.ajustes) {
				buf.writeUtf(a.id);
				buf.writeUtf(a.grupo);
				buf.writeUtf(a.valor);
				buf.writeVarInt(a.valores.size());
				for (String v : a.valores) buf.writeUtf(v);
				buf.writeUtf(a.claveNombre);
				buf.writeUtf(a.prefijoValor);
			}
		}

		private static ConfigPanel leer(FriendlyByteBuf buf) {
			boolean abrir = buf.readBoolean();
			boolean editable = buf.readBoolean();
			boolean enPartida = buf.readBoolean();
			String modo = buf.readUtf();
			int minutos = buf.readVarInt();
			boolean pvp = buf.readBoolean();
			int distancia = buf.readVarInt();
			boolean semillaFija = buf.readBoolean();
			long semilla = buf.readLong();
			int n = buf.readVarInt();
			List<OpcionRed> opciones = new ArrayList<>(n);
			for (int i = 0; i < n; i++) opciones.add(new OpcionRed(buf.readUtf(), buf.readBoolean()));
			int maxMinutos = buf.readVarInt(), maxDistancia = buf.readVarInt();
			int na = buf.readVarInt();
			List<AjusteRed> ajustes = new ArrayList<>(na);
			for (int i = 0; i < na; i++) {
				String id = buf.readUtf(), grupo = buf.readUtf(), valor = buf.readUtf();
				int nv = buf.readVarInt();
				List<String> valores = new ArrayList<>(nv);
				for (int k = 0; k < nv; k++) valores.add(buf.readUtf());
				ajustes.add(new AjusteRed(id, grupo, valor, valores, buf.readUtf(), buf.readUtf()));
			}
			return new ConfigPanel(abrir, editable, enPartida, modo, minutos, pvp, distancia, semillaFija, semilla, opciones,
				maxMinutos, maxDistancia, ajustes);
		}
	}

	/**
	 * Cliente → servidor: el anfitrión tocó algo en el panel. El servidor vuelve a comprobar que sea operador.
	 * texto y numero dependen del tipo: MODO (id), TIEMPO (minutos), PVP (0/1), DISTANCIA (bloques),
	 * OPCION (id, 0/1), SEMILLA (numero; texto "aleatoria" para quitarla), ALEATORIOS (equipos), INICIAR.
	 */
	public record AccionPanel(int tipo, String texto, long numero) implements CustomPacketPayload {
		public static final int MODO = 0, TIEMPO = 1, PVP = 2, DISTANCIA = 3, OPCION = 4, SEMILLA = 5, ALEATORIOS = 6, INICIAR = 7, AJUSTE = 8;

		public static final Type<AccionPanel> TYPE = new Type<>(id("accion_panel"));
		public static final StreamCodec<FriendlyByteBuf, AccionPanel> CODEC = StreamCodec.of(
			(buf, a) -> {
				buf.writeVarInt(a.tipo);
				buf.writeUtf(a.texto, 64);
				buf.writeLong(a.numero);
			},
			buf -> new AccionPanel(buf.readVarInt(), buf.readUtf(64), buf.readLong()));

		@Override
		public Type<AccionPanel> type() {
			return TYPE;
		}
	}
}
