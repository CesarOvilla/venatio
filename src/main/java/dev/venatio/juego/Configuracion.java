package dev.venatio.juego;

import dev.venatio.carton.Dificultad;
import dev.venatio.carton.Reglas;

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
	/** Rastreo carrera: items para ganar. */
	public int meta = 10;

	// Opciones de partida (las de MCBingo más el minado en veta)
	public boolean vision = true;
	public boolean velocidad = false;
	public boolean depthStrider = false;
	public boolean elitro = false;
	public boolean recetas = false;
	public boolean minado = true;
	/** Conservar el inventario al morir (gamerule keepInventory en los mundos de la partida). */
	public boolean conservarInventario = true;
	/** Terminar en cuanto alguien gana; si no, se anuncia y se sigue jugando hasta que el anfitrión termine o se acabe el tiempo. */
	public boolean finAutomatico = true;
	/** Rastreo: cada equipo avanza por su propia lista en vez de buscar todos el mismo item. */
	public boolean rastreoIndependiente = false;
	/** Barra de localización propia (en lugar de la de experiencia, como la vanilla). */
	public boolean localizador = true;
	/** En la barra también se ven los rivales que no se esconden; si no, solo los compañeros. */
	public boolean localizadorRivales = true;
	/** Eventos sorpresa para todos cada cierto tiempo (noche eterna, horda, lluvia de items…). */
	public boolean eventos = false;
	/** Las ayudas cuestan 20 % menos al equipo que va en último lugar por 2 o más. */
	public boolean descuento = true;
	/** Switcheroo: minutos que dura cada item antes de cambiar solo (1, 2, 3 o 5). */
	public int switcheroo = 3;
	/** Eventos sorpresa: minutos aproximados entre un evento y el siguiente (5, 8 o 10). */
	public int eventosMinutos = 8;

	// Cartón ampliado
	/** Peso de cada nivel (fácil, media, difícil) al armar el cartón. */
	public Dificultad dificultad = Dificultad.NORMAL;
	/** ~25–30 % de las casillas son logros (entrar a un bioma, matar un mob, dormir…) en vez de items. */
	public boolean logros = false;
	/** ~30 % de las casillas de item piden varias unidades, que se juntan entre todo el equipo. */
	public boolean cantidades = false;

	public Reglas reglas() {
		return new Reglas(dificultad, logros, cantidades);
	}

	public int limiteSegundos() {
		int min = minutos > 0 ? minutos : modo == Modo.CONTRARRELOJ ? 25 : modo == Modo.RASTREO ? 15
			: modo == Modo.SWITCHEROO ? 20 : 0;
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
		c.vision = vision;
		c.velocidad = velocidad;
		c.depthStrider = depthStrider;
		c.elitro = elitro;
		c.recetas = recetas;
		c.minado = minado;
		c.conservarInventario = conservarInventario;
		c.finAutomatico = finAutomatico;
		c.meta = meta;
		c.rastreoIndependiente = rastreoIndependiente;
		c.localizador = localizador;
		c.localizadorRivales = localizadorRivales;
		c.dificultad = dificultad;
		c.logros = logros;
		c.cantidades = cantidades;
		c.eventos = eventos;
		c.descuento = descuento;
		c.switcheroo = switcheroo;
		c.eventosMinutos = eventosMinutos;
		return c;
	}
}
