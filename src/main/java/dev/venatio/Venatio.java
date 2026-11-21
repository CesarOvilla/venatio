package dev.venatio;

import dev.venatio.info.Fichas;
import dev.venatio.ayudas.Ayudas;
import dev.venatio.juego.Eventos;
import dev.venatio.juego.Juego;
import dev.venatio.localizador.Localizador;
import dev.venatio.minimapa.ComandoMinimapaDev;
import dev.venatio.red.Paquetes;
import dev.venatio.red.PaquetesAyudas;
import dev.venatio.red.PaquetesEventos;
import dev.venatio.red.PaquetesStats;
import dev.venatio.stats.ModuloStats;
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
		PaquetesAyudas.registrar();
		PaquetesStats.registrar();
		PaquetesEventos.registrar();
		Fichas.registrar();
		Juego.INSTANCIA.registrar();
		Eventos.registrar();
		ModuloStats.registrar();
		Ayudas.registrar();
		Localizador.registrar();
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> Comandos.registrar(dispatcher));
		ComandoMinimapaDev.registrar(); // solo pruebas: /venatiodev minimapa <segundos> <radio>
	}

	public static String version() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}
}
