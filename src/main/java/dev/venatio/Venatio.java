package dev.venatio;

import dev.venatio.juego.Juego;
import dev.venatio.red.Paquetes;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Venatio implements ModInitializer {
	public static final String MOD_ID = "venatio";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		Paquetes.registrar();
		Juego.INSTANCIA.registrar();
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> Comandos.registrar(dispatcher));
	}

	public static String version() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}
}
