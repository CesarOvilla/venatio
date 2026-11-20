package dev.venatio.juego;

/** Lo que el anfitrión ajusta en el lobby antes de iniciar. */
public final class Configuracion {
	public Modo modo = Modo.LINEA;
	/** Límite de tiempo en minutos; 0 = sin límite (Contrarreloj usa 25 si queda en 0). */
	public int minutos = 0;
	/** Semilla fija para repetir mundo y cartón; null = aleatoria. */
	public Long semilla = null;
	/** PvP activado y todos los equipos aparecen en el mismo punto. */
	public boolean pvp = false;
	/** Distancia entre los puntos de aparición de los equipos, en bloques. */
	public int distancia = 400;
	public int tamano = 5;

	public int limiteSegundos() {
		int min = minutos > 0 ? minutos : (modo == Modo.CONTRARRELOJ ? 25 : 0);
		return min * 60;
	}

	public Configuracion copia() {
		Configuracion c = new Configuracion();
		c.modo = modo;
		c.minutos = minutos;
		c.semilla = semilla;
		c.pvp = pvp;
		c.distancia = distancia;
		c.tamano = tamano;
		return c;
	}
}
