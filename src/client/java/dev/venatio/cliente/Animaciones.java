package dev.venatio.cliente;

import dev.venatio.juego.Modo;
import dev.venatio.red.Paquetes;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Animaciones del cliente: cuándo empezó cada una y por dónde va. Todas se miden con {@link #ahora()},
 * así una prueba puede parar el reloj y capturar una animación a la mitad. Solo se usa en el hilo del cliente.
 */
public final class Animaciones {
	/** Casilla conseguida por mi equipo: pulso del item, destello y anillo. */
	public static final long CASILLA_MS = 700;
	/** Casilla de un rival: un parpadeo del borde con su color. */
	public static final long RIVAL_MS = 450;
	/** Entrada del cartón del HUD: se desliza y las casillas aparecen en diagonal. */
	public static final long ENTRADA_MS = 850;
	/** "¡A cazar!" */
	public static final long YA_MS = 950;
	/** Banner de bingo a media partida. */
	public static final long BANNER_BINGO_MS = 3200;
	/** Banner y confeti del final de la partida (lo único que dura más de un segundo). */
	public static final long BANNER_FINAL_MS = 6000;
	public static final long CONFETI_MS = 3600;
	public static final long BARRA_DESTELLO_MS = 650;
	public static final long ITEM_NUEVO_MS = 400;
	public static final long MARCADOR_MS = 500;

	record Casilla(long inicio, int equipo, boolean propia) {
		long duracion() {
			return propia ? CASILLA_MS : RIVAL_MS;
		}
	}

	/** equipo: ordinal del ganador o -1; final_: terminó la partida (si no, es un bingo y la partida sigue). */
	record Banner(long inicio, int equipo, boolean final_) {
		long duracion() {
			return final_ ? BANNER_FINAL_MS : BANNER_BINGO_MS;
		}
	}

	private static Long relojFijo;

	static final Map<Integer, Casilla> casillas = new HashMap<>();
	/** Equipo → momento en que sumó su última casilla (pulso de su emblema en el marcador). */
	static final Map<Integer, Long> marcador = new HashMap<>();
	private static long entradaCarton = Long.MIN_VALUE;
	/** La entrada está pedida pero no ha empezado: se espera a que no haya pantalla delante (la de "cargando terreno"). */
	private static boolean entradaPendiente;
	private static long entradaPedida;
	/** Momento (ms) en que termina la cuenta atrás, o MIN_VALUE si no hay. */
	static long cuentaFin = Long.MIN_VALUE;
	private static int ultimoTic = -1;
	static long yaInicio = Long.MIN_VALUE;
	static Banner banner;
	/** Rastreo: fracción de la barra que se está mostrando (persigue a la real). */
	private static float barraMostrada = -1;
	private static long barraActualizada;
	static long barraDestello = Long.MIN_VALUE;
	static long itemNuevo = Long.MIN_VALUE;

	private Animaciones() {}

	// ---------------------------------------------------------------- reloj

	public static long ahora() {
		return relojFijo != null ? relojFijo : Util.getMillis();
	}

	/** Para las pruebas: detiene el reloj de las animaciones en este instante (null lo suelta). */
	public static void fijarReloj(Long ms) {
		relojFijo = ms;
	}

	/** Progreso 0..1 de una animación que empezó en inicio y dura duracion; -1 si no está en curso. */
	static float progreso(long inicio, long duracion) {
		if (inicio == Long.MIN_VALUE) return -1;
		long t = ahora() - inicio;
		return t < 0 || t > duracion ? -1 : (float) t / duracion;
	}

	static float suaveFuera(float t) {
		float u = 1 - Mth.clamp(t, 0, 1);
		return 1 - u * u * u;
	}

	/** Llega pasándose un poco y vuelve. */
	static float rebote(float t) {
		float u = Mth.clamp(t, 0, 1) - 1;
		return 1 + 2.4f * u * u * u + 1.4f * u * u;
	}

	// ---------------------------------------------------------------- lo que llega del servidor

	static void limpiar() {
		casillas.clear();
		marcador.clear();
		entradaCarton = cuentaFin = yaInicio = barraDestello = itemNuevo = Long.MIN_VALUE;
		entradaPendiente = false;
		ultimoTic = -1;
		banner = null;
		barraMostrada = -1;
	}

	/** Llegó un estado nuevo: detecta el arranque del cartón, de la cuenta atrás y el cambio de item en Rastreo. */
	static void alCambiarEstado(Paquetes.Estado antes, Paquetes.Estado e) {
		long ahora = ahora();
		boolean habia = antes != null && !antes.casillas().isEmpty();
		boolean hay = !e.casillas().isEmpty();
		boolean enCuenta = e.fase() == Paquetes.Estado.CUENTA_ATRAS;
		boolean empiezaCuenta = enCuenta && (antes == null || antes.fase() != Paquetes.Estado.CUENTA_ATRAS);
		if (hay && (!habia || empiezaCuenta)) {
			entradaCarton = Long.MIN_VALUE;
			entradaPendiente = true;
			entradaPedida = Util.getMillis();
		}
		if (!hay) {
			casillas.clear();
			barraMostrada = -1;
		}

		if (enCuenta) {
			// El paquete trae segundos enteros (redondeados hacia arriba): se queda con la estimación más temprana.
			// Con el reloj de verdad: el final de la cuenta lo marca el servidor, no una prueba con el reloj parado.
			long fin = Util.getMillis() + e.segundos() * 1000L;
			cuentaFin = empiezaCuenta || cuentaFin == Long.MIN_VALUE ? fin : Math.min(cuentaFin, fin);
		} else {
			cuentaFin = Long.MIN_VALUE;
			ultimoTic = -1;
		}

		if (hay && habia && Modo.porId(e.modo()).esRastreo()
			&& !ItemStack.isSameItemSameComponents(antes.casillas().get(0).icono(), e.casillas().get(0).icono())) {
			itemNuevo = ahora;
		}
	}

	static void evento(Paquetes.Evento ev, Paquetes.Estado e) {
		long ahora = ahora();
		int miEquipo = e == null ? -1 : e.miEquipo();
		boolean rastreo = e != null && Modo.porId(e.modo()).esRastreo();
		switch (ev.tipo()) {
			case Paquetes.Evento.CASILLA -> {
				boolean propia = ev.equipo() == miEquipo;
				marcador.put(ev.equipo(), ahora);
				if (rastreo) {
					if (propia) barraDestello = ahora;
					Sonidos.tocar(propia ? Sonidos.RASTREO_ENCONTRADO : Sonidos.CASILLA_RIVAL);
				} else {
					casillas.put(ev.casilla(), new Casilla(ahora, ev.equipo(), propia));
					Sonidos.tocar(propia ? Sonidos.CASILLA : Sonidos.CASILLA_RIVAL);
				}
			}
			case Paquetes.Evento.BINGO -> {
				banner = new Banner(ahora, ev.equipo(), false);
				Sonidos.tocar(Sonidos.BINGO);
			}
			case Paquetes.Evento.VICTORIA -> {
				banner = new Banner(ahora, ev.equipo(), true);
				// Gana mi equipo: victoria. Gana otro: derrota. Sin ganador (empate o cancelada): campanas neutras.
				// Un espectador oye la fanfarria corta.
				Sonidos.tocar(ev.equipo() < 0 ? Sonidos.FIN : miEquipo < 0 ? Sonidos.BINGO
					: ev.equipo() == miEquipo ? Sonidos.VICTORIA : Sonidos.DERROTA);
			}
			case Paquetes.Evento.INICIO -> {
				yaInicio = ahora;
				cuentaFin = Long.MIN_VALUE;
				Sonidos.tocar(Sonidos.CUENTA_YA);
			}
			default -> {}
		}
	}

	// ---------------------------------------------------------------- consultas para el dibujo

	/** Animación en curso de una casilla, o null. Las terminadas se van borrando. */
	static Casilla casilla(int indice) {
		Casilla c = casillas.get(indice);
		if (c == null) return null;
		if (ahora() - c.inicio() > c.duracion()) {
			casillas.remove(indice);
			return null;
		}
		return c;
	}

	/** Segundo que toca mostrar en la cuenta atrás (10…1), o 0 si no hay cuenta. */
	static int segundoCuenta() {
		if (cuentaFin == Long.MIN_VALUE) return 0;
		long resta = cuentaFin - ahora();
		return resta <= 0 ? 0 : (int) ((resta + 999) / 1000);
	}

	/** Cuánto lleva en pantalla el número actual de la cuenta atrás, 0..1. */
	static float faseCuenta() {
		long resta = cuentaFin - ahora();
		return resta <= 0 ? 1 : 1 - ((resta - 1) % 1000 + 1) / 1000f;
	}

	// Para las pruebas: cuándo empezó cada animación (MIN_VALUE = no ha empezado).

	public static long finDeCuenta() {
		return cuentaFin;
	}

	public static long inicioEntrada() {
		return entradaCarton;
	}

	public static long inicioYa() {
		return yaInicio;
	}

	public static long inicioDestello() {
		return barraDestello;
	}

	public static long inicioBanner() {
		return banner == null ? Long.MIN_VALUE : banner.inicio();
	}

	public static boolean animando(int casilla) {
		return casillas.containsKey(casilla);
	}

	/** Milisegundos que lleva la entrada del cartón: 0 si aún no empieza, un número enorme si no hay ninguna en curso. */
	static long tiempoEntrada() {
		if (entradaPendiente) return 0;
		return entradaCarton == Long.MIN_VALUE ? Long.MAX_VALUE / 2 : Math.max(0, ahora() - entradaCarton);
	}

	/** Un tic por cada segundo de la cuenta atrás; los tres últimos, más agudos. */
	static void alTick(Minecraft mc) {
		if (entradaPendiente && (mc.gui.screen() == null || Util.getMillis() - entradaPedida > 4000)) {
			entradaPendiente = false;
			entradaCarton = ahora();
		}
		int s = segundoCuenta();
		if (s > 0 && s != ultimoTic && EstadoCliente.estado != null && EstadoCliente.estado.fase() == Paquetes.Estado.CUENTA_ATRAS) {
			Sonidos.tocar(Sonidos.CUENTA_TIC, s <= 3 ? 1.3f : 1f);
		}
		ultimoTic = s;
		if (banner != null && ahora() - banner.inicio() > Math.max(banner.duracion(), CONFETI_MS)) banner = null;
	}

	/** Fracción de la barra del Rastreo que se dibuja: se acerca a la real poco a poco en vez de saltar. */
	static float barra(float real) {
		long ahora = ahora();
		if (barraMostrada < 0) barraMostrada = real;
		else {
			float dt = Mth.clamp((ahora - barraActualizada) / 1000f, 0, 0.25f);
			barraMostrada += (real - barraMostrada) * Math.min(1, dt * 7);
			if (Math.abs(real - barraMostrada) < 0.002f) barraMostrada = real;
		}
		barraActualizada = ahora;
		return barraMostrada;
	}
}
