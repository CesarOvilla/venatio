package dev.venatio.cliente;

import dev.venatio.Venatio;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import java.util.List;

/**
 * Sonidos propios de Venatio (assets/venatio/sounds.json; los genera tools/arte/sonidos.py).
 * Solo los toca el cliente, como sonidos de interfaz: no hace falta registrarlos en el servidor.
 */
public final class Sonidos {
	/** Volumen base de interfaz; el de cada evento se ajusta además en sounds.json. */
	private static final float VOLUMEN = 0.6f;

	public static final SoundEvent CASILLA = evento("casilla");
	public static final SoundEvent CASILLA_RIVAL = evento("casilla_rival");
	public static final SoundEvent BINGO = evento("bingo");
	public static final SoundEvent VICTORIA = evento("victoria");
	public static final SoundEvent DERROTA = evento("derrota");
	public static final SoundEvent FIN = evento("fin");
	public static final SoundEvent CUENTA_TIC = evento("cuenta_tic");
	public static final SoundEvent CUENTA_YA = evento("cuenta_ya");
	public static final SoundEvent COMPRA = evento("compra");
	public static final SoundEvent MARCA = evento("marca");
	public static final SoundEvent GUIA_LLEGADA = evento("guia_llegada");
	public static final SoundEvent RASTREO_ENCONTRADO = evento("rastreo_encontrado");
	public static final SoundEvent CARTON_ABRIR = evento("carton_abrir");

	/** Todos, para comprobar en las pruebas que el juego los encuentra. */
	public static final List<SoundEvent> TODOS = List.of(CASILLA, CASILLA_RIVAL, BINGO, VICTORIA, DERROTA, FIN, CUENTA_TIC, CUENTA_YA,
		COMPRA, MARCA, GUIA_LLEGADA, RASTREO_ENCONTRADO, CARTON_ABRIR);

	/** Cuántas veces se pidió tocar cada sonido (para las pruebas). */
	private static final java.util.Map<Identifier, Integer> tocados = new java.util.HashMap<>();

	private Sonidos() {}

	private static SoundEvent evento(String nombre) {
		return SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Venatio.MOD_ID, nombre));
	}

	public static void tocar(SoundEvent sonido) {
		tocar(sonido, 1f);
	}

	public static void tocar(SoundEvent sonido, float tono) {
		tocados.merge(sonido.location(), 1, Integer::sum);
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sonido, tono, VOLUMEN));
	}

	/** Veces que se tocó un sonido desde que arrancó el juego (para las pruebas). */
	public static int veces(SoundEvent sonido) {
		return tocados.getOrDefault(sonido.location(), 0);
	}
}
