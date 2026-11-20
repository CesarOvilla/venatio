package dev.venatio.juego;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** Opciones de partida que el anfitrión activa o desactiva con /venatio opcion <id> si|no. */
public enum Opcion {
	VISION("vision", c -> c.vision, (c, v) -> c.vision = v),
	VELOCIDAD("velocidad", c -> c.velocidad, (c, v) -> c.velocidad = v),
	DEPTH_STRIDER("depth_strider", c -> c.depthStrider, (c, v) -> c.depthStrider = v),
	ELITRO("elitro", c -> c.elitro, (c, v) -> c.elitro = v),
	RECETAS("recetas", c -> c.recetas, (c, v) -> c.recetas = v),
	MINADO("minado", c -> c.minado, (c, v) -> c.minado = v),
	FIN_AUTOMATICO("fin_automatico", c -> c.finAutomatico, (c, v) -> c.finAutomatico = v),
	RASTREO_INDEPENDIENTE("rastreo_independiente", c -> c.rastreoIndependiente, (c, v) -> c.rastreoIndependiente = v),
	LOCALIZADOR("localizador", c -> c.localizador, (c, v) -> c.localizador = v),
	LOCALIZADOR_RIVALES("localizador_rivales", c -> c.localizadorRivales, (c, v) -> c.localizadorRivales = v);

	public final String id;
	private final Predicate<Configuracion> lector;
	private final BiConsumer<Configuracion, Boolean> escritor;

	Opcion(String id, Predicate<Configuracion> lector, BiConsumer<Configuracion, Boolean> escritor) {
		this.id = id;
		this.lector = lector;
		this.escritor = escritor;
	}

	public boolean valor(Configuracion c) {
		return lector.test(c);
	}

	public void poner(Configuracion c, boolean v) {
		escritor.accept(c, v);
	}

	public MutableComponent nombre() {
		return Component.translatable("venatio.opcion." + id);
	}
}
