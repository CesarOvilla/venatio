package dev.venatio;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Modo;
import dev.venatio.juego.Opcion;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/** /venatio …: todos los comandos del juego, en español. Los de configuración son para el anfitrión (operador). */
public final class Comandos {
	private Comandos() {}

	public static void registrar(CommandDispatcher<CommandSourceStack> d) {
		Juego j = Juego.INSTANCIA;
		LiteralArgumentBuilder<CommandSourceStack> raiz = Commands.literal("venatio").executes(Comandos::ayuda);

		// Equipos (cualquiera)
		LiteralArgumentBuilder<CommandSourceStack> equipo = Commands.literal("equipo");
		for (ColorEquipo c : ColorEquipo.values()) {
			equipo.then(Commands.literal(c.id()).executes(ctx -> responder(ctx, j.unir(ctx.getSource().getPlayerOrException(), c), null)));
		}
		equipo.then(Commands.literal("salir").executes(ctx -> responder(ctx, j.salir(ctx.getSource().getPlayerOrException()), null)));
		raiz.then(equipo);

		// Anfitrión
		raiz.then(anfitrion("equipos").then(Commands.literal("aleatorios")
			.then(Commands.argument("cantidad", IntegerArgumentType.integer(1, 8))
				.executes(ctx -> responder(ctx, j.aleatorios(IntegerArgumentType.getInteger(ctx, "cantidad")), null)))));

		LiteralArgumentBuilder<CommandSourceStack> modo = anfitrion("modo");
		for (Modo m : Modo.values()) {
			modo.then(Commands.literal(m.id()).executes(ctx -> configurar(ctx, () -> j.config.modo = m,
				Component.translatable("venatio.config.modo", m.nombre()))));
		}
		raiz.then(modo);

		raiz.then(anfitrion("tiempo").then(Commands.argument("minutos", IntegerArgumentType.integer(0, 300)).executes(ctx -> {
			int min = IntegerArgumentType.getInteger(ctx, "minutos");
			return configurar(ctx, () -> j.config.minutos = min, min == 0
				? Component.translatable("venatio.config.sin_tiempo")
				: Component.translatable("venatio.config.tiempo", min));
		})));

		raiz.then(anfitrion("semilla")
			.then(Commands.literal("aleatoria").executes(ctx -> configurar(ctx, () -> j.config.semilla = null,
				Component.translatable("venatio.config.semilla_aleatoria"))))
			.then(Commands.argument("numero", LongArgumentType.longArg()).executes(ctx -> {
				long s = LongArgumentType.getLong(ctx, "numero");
				return configurar(ctx, () -> j.config.semilla = s, Component.translatable("venatio.config.semilla", String.valueOf(s)));
			})));

		raiz.then(anfitrion("pvp")
			.then(Commands.literal("si").executes(ctx -> configurar(ctx, () -> j.config.pvp = true, Component.translatable("venatio.config.pvp_si"))))
			.then(Commands.literal("no").executes(ctx -> configurar(ctx, () -> j.config.pvp = false, Component.translatable("venatio.config.pvp_no")))));

		raiz.then(anfitrion("distancia").then(Commands.argument("bloques", IntegerArgumentType.integer(0, 10000)).executes(ctx -> {
			int b = IntegerArgumentType.getInteger(ctx, "bloques");
			return configurar(ctx, () -> j.config.distancia = b, Component.translatable("venatio.config.distancia", b));
		})));

		LiteralArgumentBuilder<CommandSourceStack> opcion = anfitrion("opcion");
		for (Opcion o : Opcion.values()) {
			opcion.then(Commands.literal(o.id)
				.then(Commands.literal("si").executes(ctx -> configurar(ctx, () -> o.poner(j.config, true),
					Component.translatable("venatio.config.opcion", o.nombre(), Component.translatable("venatio.config.si")))))
				.then(Commands.literal("no").executes(ctx -> configurar(ctx, () -> o.poner(j.config, false),
					Component.translatable("venatio.config.opcion", o.nombre(), Component.translatable("venatio.config.no"))))));
		}
		raiz.then(opcion);

		raiz.then(anfitrion("iniciar").executes(ctx -> responder(ctx, j.iniciar(), null)));
		raiz.then(anfitrion("terminar").executes(ctx -> responder(ctx, j.terminar(), null)));

		d.register(raiz);
	}

	private static LiteralArgumentBuilder<CommandSourceStack> anfitrion(String nombre) {
		return Commands.literal(nombre).requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
	}

	private static int configurar(CommandContext<CommandSourceStack> ctx, Runnable cambio, Component confirmacion) {
		if (Juego.INSTANCIA.partida() != null) {
			ctx.getSource().sendFailure(Component.translatable("venatio.error.partida_en_curso"));
			return 0;
		}
		cambio.run();
		Juego.INSTANCIA.actualizarTableros();
		Juego.INSTANCIA.enviarEstadoATodos();
		Juego.INSTANCIA.difundir(confirmacion);
		return 1;
	}

	/** error null = todo bien; si hay mensaje de éxito se manda solo al que ejecutó el comando. */
	private static int responder(CommandContext<CommandSourceStack> ctx, Component error, Supplier<Component> exito) {
		if (error != null) {
			ctx.getSource().sendFailure(error);
			return 0;
		}
		if (exito != null) ctx.getSource().sendSuccess(exito, false);
		return 1;
	}

	private static int ayuda(CommandContext<CommandSourceStack> ctx) {
		ctx.getSource().sendSuccess(() -> Component.translatable("venatio.ayuda", Venatio.version()), false);
		return 1;
	}
}
