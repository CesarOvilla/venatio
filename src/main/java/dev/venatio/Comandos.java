package dev.venatio;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.venatio.juego.Ajustes;
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
			modo.then(Commands.literal(m.id()).executes(ctx -> responder(ctx, Ajustes.modo(m), null)));
		}
		raiz.then(modo);

		raiz.then(anfitrion("tiempo").then(Commands.argument("minutos", IntegerArgumentType.integer(0, Ajustes.MAX_MINUTOS))
			.executes(ctx -> responder(ctx, Ajustes.tiempo(IntegerArgumentType.getInteger(ctx, "minutos")), null))));

		raiz.then(anfitrion("semilla")
			.then(Commands.literal("aleatoria").executes(ctx -> responder(ctx, Ajustes.semilla(null), null)))
			.then(Commands.argument("numero", LongArgumentType.longArg())
				.executes(ctx -> responder(ctx, Ajustes.semilla(LongArgumentType.getLong(ctx, "numero")), null))));

		raiz.then(anfitrion("pvp")
			.then(Commands.literal("si").executes(ctx -> responder(ctx, Ajustes.pvp(true), null)))
			.then(Commands.literal("no").executes(ctx -> responder(ctx, Ajustes.pvp(false), null))));

		raiz.then(anfitrion("distancia").then(Commands.argument("bloques", IntegerArgumentType.integer(0, Ajustes.MAX_DISTANCIA))
			.executes(ctx -> responder(ctx, Ajustes.distancia(IntegerArgumentType.getInteger(ctx, "bloques")), null))));

		LiteralArgumentBuilder<CommandSourceStack> opcion = anfitrion("opcion");
		for (Opcion o : Opcion.values()) {
			opcion.then(Commands.literal(o.id)
				.then(Commands.literal("si").executes(ctx -> responder(ctx, Ajustes.opcion(o, true), null)))
				.then(Commands.literal("no").executes(ctx -> responder(ctx, Ajustes.opcion(o, false), null))));
		}
		raiz.then(opcion);

		raiz.then(anfitrion("iniciar").executes(ctx -> responder(ctx, j.iniciar(), null)));
		raiz.then(anfitrion("terminar").executes(ctx -> responder(ctx, j.terminar(), null)));

		d.register(raiz);
	}

	private static LiteralArgumentBuilder<CommandSourceStack> anfitrion(String nombre) {
		return Commands.literal(nombre).requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
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
