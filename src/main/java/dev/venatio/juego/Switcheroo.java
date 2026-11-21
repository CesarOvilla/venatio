package dev.venatio.juego;

import dev.venatio.carton.Objetivo;
import dev.venatio.red.PaquetesEventos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Modo Switcheroo sobre el Rastreo compartido: el primer equipo que consigue el item gana un punto y todos pasan
 * al siguiente; si nadie lo consigue en {@link Configuracion#switcheroo} minutos, cambia solo y nadie puntúa.
 */
final class Switcheroo {
	private Switcheroo() {}

	/** Segundos que dura cada item. */
	static int duracion(Partida p) {
		return p.cfg.switcheroo * 60;
	}

	/** Segundo de partida en que el item actual cambia solo. */
	static int cambioEn(Partida p) {
		return p.rastreo.inicioComun() + duracion(p);
	}

	/** Cada segundo de juego: si al item se le acabó el tiempo, todos pasan al siguiente sin puntos. */
	static void tick(Partida p) {
		if (p.cfg.modo != Modo.SWITCHEROO || p.rastreo == null || p.fase != Partida.Fase.JUGANDO) return;
		if (p.segundos() < cambioEn(p)) return;
		Objetivo vencido = p.rastreo.actual(null);
		ItemStack icono = vencido.icono();
		p.rastreo.avanzar(null, p.segundos());
		p.eventos.anotarVencido(icono, p.segundos());
		Juego j = Juego.INSTANCIA;
		j.difundir(Component.translatable("venatio.switcheroo.cambio", icono.getHoverName(), p.rastreo.actual(null).icono().getHoverName()));
		j.enviarEvento(new PaquetesEventos.Aviso(PaquetesEventos.Aviso.CAMBIO, -1, icono, ""));
		j.enviarEstadoATodos();
		p.eventos.enviarHud();
	}

	/** Un equipo consiguió primero el item (Partida ya lo anotó y pasó al siguiente). */
	static void punto(Partida p, ServerPlayer quien, ColorEquipo equipo, ItemStack item) {
		Juego j = Juego.INSTANCIA;
		j.difundir(Component.translatable("venatio.switcheroo.punto", quien.getDisplayName(), item.getHoverName(), equipo.nombre()));
		j.enviarEvento(new PaquetesEventos.Aviso(PaquetesEventos.Aviso.PUNTO, equipo.ordinal(), item.copyWithCount(1), ""));
		p.eventos.enviarHud();
	}
}
