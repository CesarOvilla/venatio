package dev.venatio.minimapa;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.venatio.red.PaquetesAyudas;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Solo para pruebas: /venatiodev minimapa &lt;segundos&gt; &lt;radio&gt; da el minimapa a quien lo ejecuta, sin pasar por Ayudas.
 * Requiere operador. segundos = 0 lo apaga.
 */
public final class ComandoMinimapaDev {
	private ComandoMinimapaDev() {}

	public static void registrar() {
		CommandRegistrationCallback.EVENT.register((d, registros, entorno) -> d.register(
			Commands.literal("venatiodev").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("minimapa")
					.then(Commands.argument("segundos", IntegerArgumentType.integer(0, 3600))
						.then(Commands.argument("radio", IntegerArgumentType.integer(8, 256))
							.executes(ComandoMinimapaDev::dar))))));
	}

	private static int dar(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer jugador = ctx.getSource().getPlayerOrException();
		int segundos = IntegerArgumentType.getInteger(ctx, "segundos");
		int radio = IntegerArgumentType.getInteger(ctx, "radio");
		ServerPlayNetworking.send(jugador, new PaquetesAyudas.PermisoMinimapa(segundos, radio));
		ctx.getSource().sendSuccess(() -> segundos == 0
			? Component.translatable("venatio.minimapa.dev.apagado")
			: Component.translatable("venatio.minimapa.dev.activado", segundos, radio), false);
		return 1;
	}
}
