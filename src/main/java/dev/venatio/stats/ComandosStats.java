package dev.venatio.stats;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Modo;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** /venatio ranking [temporada], /venatio stats [jugador], /venatio temporada y /venatio temporada nueva (anfitrión). */
final class ComandosStats {
	private static final int FILAS_CHAT = 10;

	private ComandosStats() {}

	static void registrar(CommandDispatcher<CommandSourceStack> d) {
		// Se fusiona con el /venatio de Comandos: Brigadier junta los nodos con el mismo nombre.
		d.register(Commands.literal("venatio")
			.then(Commands.literal("ranking")
				.executes(ctx -> ranking(ctx, Estadisticas.temporada()))
				.then(Commands.argument("temporada", IntegerArgumentType.integer(1))
					.executes(ctx -> ranking(ctx, IntegerArgumentType.getInteger(ctx, "temporada")))))
			.then(Commands.literal("stats")
				.executes(ctx -> stats(ctx, null))
				.then(Commands.argument("jugador", StringArgumentType.word())
					.suggests((ctx, b) -> SharedSuggestionProvider.suggest(nombresConocidos(), b))
					.executes(ctx -> stats(ctx, StringArgumentType.getString(ctx, "jugador")))))
			.then(Commands.literal("temporada")
				.executes(ComandosStats::temporada)
				.then(Commands.literal("nueva").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
					.executes(ComandosStats::nuevaTemporada))));
	}

	private static List<String> nombresConocidos() {
		List<String> nombres = new ArrayList<>();
		Estadisticas.Datos d = Estadisticas.datos();
		if (d != null) for (Estadisticas.Jugador j : d.jugadores.values()) if (!"?".equals(j.nombre)) nombres.add(j.nombre);
		return nombres;
	}

	private static boolean sinDatos(CommandContext<CommandSourceStack> ctx) {
		if (Estadisticas.cargadas()) return false;
		ctx.getSource().sendFailure(Component.translatable("venatio.stats.error.sin_datos"));
		return true;
	}

	// ---------------------------------------------------------------- ranking

	private static int ranking(CommandContext<CommandSourceStack> ctx, int temporada) {
		if (sinDatos(ctx)) return 0;
		List<Ranking.Fila> tabla = Ranking.tabla(temporada);
		MutableComponent texto = Component.empty().append(titulo(Component.translatable("venatio.stats.ranking.titulo", temporada)));
		if (tabla.isEmpty()) {
			texto.append("\n").append(Component.translatable("venatio.stats.ranking.vacio", temporada).withStyle(ChatFormatting.GRAY));
		}
		for (int i = 0; i < tabla.size() && i < FILAS_CHAT; i++) texto.append("\n").append(fila(tabla.get(i)));

		ServerPlayer p = ctx.getSource().getPlayer();
		if (p != null) {
			String uuid = p.getUUID().toString();
			for (int i = FILAS_CHAT; i < tabla.size(); i++) {
				if (tabla.get(i).uuid().equals(uuid)) {
					texto.append("\n").append(Component.translatable("venatio.stats.ranking.tu_puesto", tabla.get(i).puesto() + ".º", tabla.get(i).puntos())
						.withStyle(ChatFormatting.YELLOW));
				}
			}
		}
		ctx.getSource().sendSuccess(() -> texto, false);
		return 1;
	}

	private static Component fila(Ranking.Fila f) {
		return Component.translatable("venatio.stats.ranking.fila", f.puesto() + ".º", f.nombre(), f.puntos(), f.victorias(), f.casillas(), f.partidas());
	}

	// ---------------------------------------------------------------- stats

	private static int stats(CommandContext<CommandSourceStack> ctx, String nombre) {
		if (sinDatos(ctx)) return 0;
		Map.Entry<String, Estadisticas.Jugador> encontrado = null;
		Estadisticas.Datos d = Estadisticas.datos();
		if (nombre == null) {
			ServerPlayer p = ctx.getSource().getPlayer();
			if (p == null) {
				ctx.getSource().sendFailure(Component.translatable("venatio.stats.error.indica_jugador"));
				return 0;
			}
			nombre = p.getGameProfile().name();
			Estadisticas.Jugador j = d.jugadores.get(p.getUUID().toString());
			if (j != null) encontrado = Map.entry(p.getUUID().toString(), j);
		} else {
			for (Map.Entry<String, Estadisticas.Jugador> e : d.jugadores.entrySet()) {
				if (e.getValue().nombre.equalsIgnoreCase(nombre)) {
					encontrado = e;
					break;
				}
			}
		}
		if (encontrado == null) {
			ctx.getSource().sendFailure(Component.translatable("venatio.stats.error.sin_stats", nombre));
			return 0;
		}

		Estadisticas.Jugador j = encontrado.getValue();
		MutableComponent texto = Component.empty().append(titulo(Component.translatable("venatio.stats.stats.titulo", j.nombre)));
		linea(texto, Component.translatable("venatio.stats.stats.partidas", j.partidas, j.victoriasTotales()));
		if (j.victoriasTotales() > 0) {
			MutableComponent modos = Component.empty();
			boolean primero = true;
			for (Modo m : Modo.values()) {
				int v = j.victorias.getOrDefault(m.id(), 0);
				if (v <= 0) continue;
				if (!primero) modos.append(", ");
				modos.append(m.nombre()).append(" " + v);
				primero = false;
			}
			linea(texto, Component.translatable("venatio.stats.stats.por_modo", modos));
		}
		linea(texto, Component.translatable("venatio.stats.stats.casillas", j.casillas));
		linea(texto, j.bingoMasRapido == null ? Component.translatable("venatio.stats.stats.bingo_nunca")
			: Component.translatable("venatio.stats.stats.bingo", ModuloStats.reloj(j.bingoMasRapido)));
		if (j.casillaMasRapida != null) {
			linea(texto, Component.translatable("venatio.stats.stats.casilla_rapida", nombreItem(j.casillaMasRapida.item),
				ModuloStats.reloj(j.casillaMasRapida.segundos)));
		}
		linea(texto, Component.translatable("venatio.stats.stats.ayudas", j.ayudas));

		int temporada = Estadisticas.temporada();
		Ranking.Fila fila = null;
		for (Ranking.Fila f : Ranking.tabla(temporada)) if (f.uuid().equals(encontrado.getKey())) fila = f;
		linea(texto, fila == null ? Component.translatable("venatio.stats.stats.temporada_sin", temporada)
			: Component.translatable("venatio.stats.stats.temporada", temporada, fila.puesto() + ".º", fila.puntos()));
		ctx.getSource().sendSuccess(() -> texto, false);
		return 1;
	}

	private static void linea(MutableComponent texto, Component linea) {
		texto.append("\n").append(linea);
	}

	private static Component titulo(MutableComponent texto) {
		return texto.withColor(ModuloStats.COLOR_ORO).withStyle(ChatFormatting.BOLD);
	}

	private static Component nombreItem(String id) {
		Component logro = dev.venatio.carton.Objetivo.nombreDeClave(id);
		if (logro != null) return logro;
		Identifier ident = Identifier.tryParse(id);
		Item item = ident == null ? null : BuiltInRegistries.ITEM.getValue(ident);
		if (item == null || item == Items.AIR) return Component.literal(id);
		return new ItemStack(item).getHoverName();
	}

	// ---------------------------------------------------------------- temporadas

	private static int temporada(CommandContext<CommandSourceStack> ctx) {
		if (sinDatos(ctx)) return 0;
		ctx.getSource().sendSuccess(() -> Component.translatable("venatio.stats.temporada.actual",
			Estadisticas.temporada(), Estadisticas.datos().inicioTemporada), false);
		return 1;
	}

	private static int nuevaTemporada(CommandContext<CommandSourceStack> ctx) {
		if (sinDatos(ctx)) return 0;
		int n = Estadisticas.nuevaTemporada();
		ModuloStats.actualizarTableros();
		Juego.INSTANCIA.difundir(Component.translatable("venatio.stats.temporada.nueva", n).withColor(ModuloStats.COLOR_ORO));
		return 1;
	}
}
