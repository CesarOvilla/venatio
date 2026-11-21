package dev.venatio.prueba;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.datafixers.util.Pair;
import dev.venatio.Venatio;
import dev.venatio.ayudas.Ayudas;
import dev.venatio.ayudas.Descuento;
import dev.venatio.ayudas.TipoAyuda;
import dev.venatio.cliente.Animaciones;
import dev.venatio.cliente.HudSorpresas;
import dev.venatio.cliente.PantallaFinal;
import dev.venatio.cliente.PantallaTienda;
import dev.venatio.cliente.Sonidos;
import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Eventos;
import dev.venatio.juego.Juego;
import dev.venatio.juego.LugarSeguro;
import dev.venatio.juego.Partida;
import dev.venatio.juego.TipoEvento;
import dev.venatio.mundo.Lobby;
import dev.venatio.red.PaquetesAyudas;
import dev.venatio.red.PaquetesStats;
import dev.venatio.red.PaquetesTienda;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;

/**
 * Switcheroo con un bot rival, los eventos sorpresa lanzados con /venatio evento y el descuento al último lugar.
 * Player0 (cliente real) va en Rojo y Bot1 (Carpet) en Azul. Dos partidas: Switcheroo con 1 minuto por item y meta 3,
 * y 5 en línea con eventos y descuento. Deja capturas eventos_*.png y descuento_*.png en screenshots/.
 */
public final class PruebaEventos implements FabricClientGameTest {
	private static final List<String> BIOMAS_CANDIDATOS = List.of(
		"plains", "forest", "taiga", "birch_forest", "savanna", "desert", "swamp", "dark_forest", "river",
		"snowy_plains", "jungle", "meadow", "cherry_grove", "badlands", "flower_forest", "old_growth_pine_taiga", "ocean");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			try {
				ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 600);
				ctx.waitTicks(40);
				sp.getServer().runCommand("player Bot1 spawn");
				sp.getServer().waitFor(s -> bot(s) != null && Lobby.esLobby(bot(s).level()), 200);
				sp.getServer().runCommand("execute as Player0 run venatio equipo rojo");
				sp.getServer().runCommand("execute as Bot1 run venatio equipo azul");
				sp.getServer().runCommand("venatio tiempo 0");
				ctx.waitTicks(10);
				comprobar(sp.getServer().computeOnServer(s -> Eventos.lanzarDesdeComando(TipoEvento.NOCHE) != null),
					"en el lobby no se puede lanzar un evento");

				switcheroo(ctx, sp);
				eventosYDescuento(ctx, sp);
			} finally {
				sp.getServer().runCommand("player Bot1 kill");
				for (String c : List.of("venatio modo linea", "venatio meta 10", "venatio switcheroo 3", "venatio eventos_minutos 8",
					"venatio opcion eventos no", "venatio opcion descuento si", "venatio opcion rastreo_independiente no")) {
					sp.getServer().runCommand(c);
				}
			}
		} finally {
			ctx.runOnClient(mc -> Animaciones.fijarReloj(null));
		}
	}

	// ---------------------------------------------------------------- Switcheroo

	private static void switcheroo(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runCommand("venatio modo switcheroo");
		sp.getServer().runCommand("venatio switcheroo 1");
		sp.getServer().runCommand("venatio meta 3");
		sp.getServer().runCommand("venatio opcion rastreo_independiente si"); // Switcheroo la ignora: siempre es compartido
		sp.getServer().runCommand("venatio iniciar");
		sp.getServer().waitFor(s -> fase() == Partida.Fase.CUENTA_ATRAS, 20 * 180);
		comprobar(sp.getServer().computeOnServer(s -> Eventos.lanzarDesdeComando(TipoEvento.NOCHE) != null
			&& partida().eventos().anunciado() == null), "en la cuenta atrás no se puede lanzar un evento");
		sp.getServer().waitFor(s -> fase() == Partida.Fase.JUGANDO, 20 * 30);
		comprobar(sp.getServer().computeOnServer(s -> !partida().rastreo().independiente()), "Switcheroo es compartido aunque la opción diga independiente");
		ctx.waitFor(mc -> HudSorpresas.segundosSwitcheroo() >= 0, 100);
		ctx.waitTicks(40);
		int queda = ctx.computeOnClient(mc -> HudSorpresas.segundosSwitcheroo());
		comprobar(queda > 50 && queda <= 60, "con el ajuste en 1 min la cuenta del item empieza en 1:00 (va en " + queda + ")");
		captura(ctx, "eventos_01_switcheroo");
		paso("Switcheroo en juego: todos buscan " + actual(sp) + "; cambia en " + queda + " s");

		String primero = actual(sp);
		sp.getServer().runCommand("execute as Player0 run venatio saltar");
		ctx.waitTicks(5);
		comprobar(actual(sp).equals(primero), "en Switcheroo /venatio saltar no cambia el item");

		// El rival lo consigue primero: punto para Azul y el item cambia para todos con el reloj de nuevo en 1:00.
		long t = congelar(ctx);
		sp.getServer().runCommand("give Bot1 " + primero);
		sp.getServer().waitFor(s -> puntos(ColorEquipo.AZUL) == 1, 100);
		ctx.waitFor(mc -> HudSorpresas.hayBanner(), 100);
		reloj(ctx, t + 700);
		captura(ctx, "eventos_02_switcheroo_punto_rival");
		reloj(ctx, null);
		comprobar(!actual(sp).equals(primero) && puntos(sp, ColorEquipo.ROJO) == 0, "el item cambió para todos y Rojo no puntuó");
		comprobar(sp.getServer().computeOnServer(s -> partida().rastreo().inicioComun() >= partida().segundos() - 2),
			"el reloj del item volvió a empezar");
		paso("Bot1 (Azul) consiguió " + primero + " primero: punto para Azul; ahora todos buscan " + actual(sp));

		String segundo = actual(sp);
		sp.getServer().runCommand("give Player0 " + segundo);
		sp.getServer().waitFor(s -> puntos(ColorEquipo.ROJO) == 1, 100);
		ctx.waitTicks(30);
		captura(ctx, "eventos_03_switcheroo_punto_propio");
		paso("Player0 (Rojo) consiguió " + segundo + ": 1 a 1");

		// Nadie lo consigue a tiempo. Se adelanta el reloj del item para que le queden 8 s y se deja correr de verdad.
		int ticAntes = veces(ctx, Sonidos.CUENTA_TIC), cambioAntes = veces(ctx, Sonidos.SWITCHEROO_CAMBIO);
		String tercero = actual(sp);
		sp.getServer().runOnServer(s -> {
			Partida p = partida();
			p.rastreo().adelantar(p.rastreo().inicioComun() + 60 - p.segundos() - 8);
		});
		ctx.waitFor(mc -> HudSorpresas.segundosSwitcheroo() >= 0 && HudSorpresas.segundosSwitcheroo() <= 6, 200);
		captura(ctx, "eventos_04_switcheroo_ultimos_segundos");
		sp.getServer().waitFor(s -> !actualEnServidor().equals(tercero), 20 * 15);
		ctx.waitFor(mc -> HudSorpresas.inicioBanner() != Long.MIN_VALUE && veces(Sonidos.SWITCHEROO_CAMBIO) > cambioAntes, 100);
		long banner = ctx.computeOnClient(mc -> HudSorpresas.inicioBanner());
		reloj(ctx, banner + 700);
		captura(ctx, "eventos_05_switcheroo_cambio");
		reloj(ctx, null);
		comprobar(puntos(sp, ColorEquipo.ROJO) == 1 && puntos(sp, ColorEquipo.AZUL) == 1, "cuando el item cambia por tiempo nadie puntúa");
		int tics = veces(ctx, Sonidos.CUENTA_TIC) - ticAntes;
		comprobar(tics >= 4, "los últimos segundos sonaron con tic (" + tics + ")");
		comprobar(sp.getServer().computeOnServer(s -> {
			List<PaquetesStats.Hito> h = partida().eventos().hitosFinales();
			return h.size() == 1 && h.get(0).equipo() == -1 && h.get(0).jugador().equals("venatio.switcheroo.linea_nadie");
		}), "el item que cambió sin dueño quedó para la línea de tiempo");
		ctx.waitFor(mc -> HudSorpresas.segundosSwitcheroo() >= 55, 100);
		paso("Nadie consiguió " + tercero + " a tiempo: cambió solo a " + actual(sp) + " sin puntos (" + tics + " tics de aviso)");

		// Player0 llega a la meta de 3: gana Rojo.
		for (int i = 0; i < 2; i++) {
			int esperado = 2 + i;
			sp.getServer().runCommand("give Player0 " + actual(sp));
			sp.getServer().waitFor(s -> puntos(ColorEquipo.ROJO) >= esperado || fase() == Partida.Fase.FINAL, 100);
		}
		sp.getServer().waitFor(s -> fase() == Partida.Fase.FINAL, 100);
		comprobar(puntos(sp, ColorEquipo.ROJO) == 3 && puntos(sp, ColorEquipo.AZUL) == 1, "Rojo llegó a la meta con 3 y Azul se quedó en 1");
		ctx.waitForScreen(PantallaFinal.class);
		ctx.waitTicks(50);
		captura(ctx, "eventos_06_switcheroo_final");
		paso("Rojo llegó a la meta: pantalla final con los puntos y el item que cambió sin dueño");
		ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
		volverAlLobby(ctx, sp);
	}

	// ---------------------------------------------------------------- eventos y descuento

	private static void eventosYDescuento(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runCommand("venatio modo linea");
		sp.getServer().runCommand("venatio opcion eventos si");
		sp.getServer().runCommand("venatio eventos_minutos 5");
		sp.getServer().runCommand("venatio opcion descuento si");
		sp.getServer().runCommand("venatio iniciar");
		sp.getServer().waitFor(s -> fase() == Partida.Fase.JUGANDO, 20 * 180);
		sp.getServer().runCommand("effect give @a minecraft:resistance 9999 4 true");
		sp.getServer().runCommand("effect give @a minecraft:saturation 9999 0 true");
		ctx.waitTicks(40);
		comprobar(sp.getServer().computeOnServer(s -> partida().eventos().anunciado() == null && partida().eventos().activo() == null),
			"con eventos cada 5 min no sale ninguno en los primeros segundos");

		horda(ctx, sp);
		descuento(ctx, sp);

		// Noche con su anuncio completo; los demás se lanzan igual con /venatio evento, pero sin esperar los 10 s.
		lanzar(ctx, sp, TipoEvento.NOCHE, true);
		comprobar(sp.getServer().computeOnServer(s -> {
			ServerClockManager.ServerClockInstance r = reloj();
			return r.isPaused() && Math.floorMod(r.totalTicks(), 24000) == 18000;
		}), "noche eterna: el reloj del mundo quedó parado a medianoche");
		ctx.waitTicks(40);
		captura(ctx, "eventos_20_noche");
		paso("Noche eterna: medianoche con el reloj detenido");

		lanzar(ctx, sp, TipoEvento.VELOCIDAD, false);
		comprobar(sp.getServer().computeOnServer(s -> !reloj().isPaused()), "al terminar la noche el reloj sigue andando");
		comprobar(sp.getServer().computeOnServer(s -> efecto(jugador(s), MobEffects.SPEED) >= 1 && efecto(jugador(s), MobEffects.HASTE) == 1
			&& efecto(bot(s), MobEffects.SPEED) >= 1), "velocidad para todos: Velocidad II y Prisa II");
		ctx.waitTicks(30);
		captura(ctx, "eventos_21_velocidad");
		paso("Velocidad para todos: Velocidad II y Prisa II, con su barra en el indicador");

		teletransporte(ctx, sp);
		intercambio(ctx, sp);
		lluvia(ctx, sp);

		lanzar(ctx, sp, TipoEvento.LUNAR, false);
		comprobar(sp.getServer().computeOnServer(s -> efecto(jugador(s), MobEffects.JUMP_BOOST) == 2 && efecto(jugador(s), MobEffects.SLOW_FALLING) == 0),
			"gravedad lunar: salto III y caída lenta");
		ctx.waitTicks(20);
		captura(ctx, "eventos_25_lunar");
		paso("Gravedad lunar: salto alto y caída lenta");

		lanzar(ctx, sp, TipoEvento.NIEBLA, false);
		comprobar(sp.getServer().computeOnServer(s -> efecto(jugador(s), MobEffects.DARKNESS) == 0 && efecto(bot(s), MobEffects.DARKNESS) == 0),
			"niebla: oscuridad para todos");
		ctx.waitTicks(40);
		captura(ctx, "eventos_26_niebla");
		paso("Niebla: oscuridad para todos");
		sp.getServer().runCommand("effect clear @a minecraft:darkness");

		tormenta(ctx, sp);

		lanzar(ctx, sp, TipoEvento.MERCADER, false);
		comprobar(sp.getServer().computeOnServer(s -> !partida().mundos().mundo().getWeatherData().isThundering()), "al terminar la tormenta se despeja");
		ctx.waitTicks(10);
		comprobar(sp.getServer().computeOnServer(s -> cerca(jugador(s), WanderingTrader.class, 10) >= 1 && cerca(bot(s), WanderingTrader.class, 10) >= 1),
			"un mercader nómada junto a cada equipo");
		ctx.waitTicks(20);
		captura(ctx, "eventos_28_mercader");
		paso("Mercader nómada junto a Player0 (Rojo) y junto a Bot1 (Azul)");

		lanzar(ctx, sp, TipoEvento.BRILLO, false);
		comprobar(sp.getServer().computeOnServer(s -> efecto(jugador(s), MobEffects.GLOWING) == 0 && efecto(bot(s), MobEffects.GLOWING) == 0),
			"todos a la vista: todos brillan");
		ctx.waitTicks(20);
		captura(ctx, "eventos_29_brillo");
		paso("Todos a la vista: todos brillan");

		int xpAntes = sp.getServer().computeOnServer(s -> jugador(s).totalExperience);
		lanzar(ctx, sp, TipoEvento.EXPERIENCIA, false);
		ctx.waitTicks(10);
		captura(ctx, "eventos_30_experiencia");
		sp.getServer().waitFor(s -> jugador(s).totalExperience - xpAntes >= 40, 20 * 10);
		paso("Lluvia de experiencia: Player0 juntó " + sp.getServer().computeOnServer(s -> jugador(s).totalExperience - xpAntes) + " puntos");

		// El programador automático: anuncia uno al azar (que no sea el último) sin que nadie lo pida.
		sp.getServer().runOnServer(s -> partida().eventos().programar(2));
		sp.getServer().waitFor(s -> partida().eventos().anunciado() != null, 20 * 10);
		TipoEvento auto = sp.getServer().computeOnServer(s -> partida().eventos().anunciado());
		comprobar(auto != TipoEvento.EXPERIENCIA, "el evento automático no repite el último");
		paso("Con la opción activa, el programador anunció solo: " + auto.id);

		// Línea de tiempo: los 12 eventos que empezaron.
		int sucesos = sp.getServer().computeOnServer(s -> partida().eventos().hitosFinales().size());
		comprobar(sucesos == TipoEvento.values().length, "los " + TipoEvento.values().length + " eventos van a la línea de tiempo (hay " + sucesos + ")");
		sp.getServer().runCommand("venatio terminar");
		ctx.waitForScreen(PantallaFinal.class);
		ctx.waitTicks(60);
		captura(ctx, "eventos_40_final_linea_de_tiempo");
		ctx.getInput().setCursorPos(120, 300);
		ctx.getInput().scroll(-8);
		ctx.waitTicks(10);
		captura(ctx, "eventos_41_final_linea_de_tiempo_abajo");
		paso("Pantalla final: casillas y eventos en la línea de tiempo");
		ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
		volverAlLobby(ctx, sp);
	}

	/** Horda con el anuncio completo, antes del primer minuto: sin creepers. */
	private static void horda(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		int anuncioAntes = veces(ctx, Sonidos.EVENTO_ANUNCIO), inicioAntes = veces(ctx, Sonidos.EVENTO_INICIO);
		int[] antes = sp.getServer().computeOnServer(s -> new int[]{cerca(jugador(s), Monster.class, 16), cerca(jugador(s), Creeper.class, 16),
			cerca(bot(s), Monster.class, 16)});
		long t = congelar(ctx);
		sp.getServer().runCommand("venatio evento horda");
		comprobar(sp.getServer().computeOnServer(s -> partida().eventos().anunciado() == TipoEvento.HORDA), "/venatio evento horda la anunció");
		ctx.waitFor(mc -> HudSorpresas.hayBanner() && "horda".equals(HudSorpresas.eventoEnHud()), 60);
		reloj(ctx, t + 800);
		captura(ctx, "eventos_10_anuncio_banner");
		reloj(ctx, null);
		ctx.waitTicks(70);
		captura(ctx, "eventos_11_anuncio_indicador");
		comprobar(veces(ctx, Sonidos.EVENTO_ANUNCIO) > anuncioAntes, "sonó el anuncio");
		comprobar(sp.getServer().computeOnServer(s -> partida().eventos().activo() == null), "durante el anuncio todavía no pasa nada");

		ctx.waitFor(mc -> HudSorpresas.inicioTexto() != Long.MIN_VALUE, 20 * 15);
		congelar(ctx);
		long inicio = ctx.computeOnClient(mc -> HudSorpresas.inicioTexto());
		reloj(ctx, inicio + 450);
		captura(ctx, "eventos_12_horda_empieza");
		reloj(ctx, null);
		int segundo = sp.getServer().computeOnServer(s -> partida().segundos());
		comprobar(segundo < 60, "la horda salió antes del primer minuto (segundo " + segundo + ")");
		sp.getServer().waitFor(s -> cerca(jugador(s), Monster.class, 16) >= antes[0] + 2, 40);
		int[] despues = sp.getServer().computeOnServer(s -> new int[]{cerca(jugador(s), Monster.class, 16), cerca(jugador(s), Creeper.class, 16),
			cerca(bot(s), Monster.class, 16)});
		comprobar(despues[0] - antes[0] >= 2 && despues[0] - antes[0] <= 3, "2 o 3 monstruos junto a Player0 (" + (despues[0] - antes[0]) + ")");
		comprobar(despues[2] - antes[2] >= 2, "y junto a Bot1 (" + (despues[2] - antes[2]) + ")");
		comprobar(despues[1] == antes[1], "sin creepers en el primer minuto");
		comprobar(veces(ctx, Sonidos.EVENTO_INICIO) > inicioAntes, "sonó el inicio del evento");
		ctx.waitTicks(10);
		captura(ctx, "eventos_13_horda");
		paso("Horda anunciada 10 s antes (banner, sonido e indicador): " + (despues[0] - antes[0]) + " monstruos junto a Player0 y "
			+ (despues[2] - antes[2]) + " junto a Bot1, sin creepers en el segundo " + segundo);
		for (String tipo : List.of("zombie", "skeleton", "spider", "creeper")) sp.getServer().runCommand("kill @e[type=minecraft:" + tipo + "]");
	}

	/** Con Bot1 dos casillas arriba, a Rojo las ayudas le cuestan 20 % menos: la guía a bioma, 6 de hierro en vez de 8. */
	private static void descuento(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		comprobar(sp.getServer().computeOnServer(s -> !Descuento.aplica(jugador(s))), "sin ventaja no hay descuento");
		sp.getServer().runCommand("give Bot1 " + item(sp, 0));
		sp.getServer().runCommand("give Bot1 " + item(sp, 1));
		sp.getServer().waitFor(s -> partida().carton().puntos(ColorEquipo.AZUL) == 2, 100);
		comprobar(sp.getServer().computeOnServer(s -> Ayudas.precioPara(jugador(s), TipoAyuda.GUIA_BIOMA).cantidad() == 6
			&& Ayudas.precioPara(jugador(s), TipoAyuda.GUIA_ESTRUCTURA).cantidad() == 2
			&& Ayudas.precioPara(jugador(s), TipoAyuda.MINIMAPA).cantidad() == 1
			&& Ayudas.precioPara(bot(s), TipoAyuda.GUIA_BIOMA).cantidad() == 8), "Rojo (último por 2) paga 6, 2 y 1; Azul, el precio normal");

		sp.getServer().runCommand("give Player0 minecraft:iron_ingot 10");
		ctx.waitTicks(5);
		ctx.getInput().pressKey(tecla(ctx, "key.venatio.ayudas"));
		ctx.waitForScreen(PantallaTienda.class);
		ctx.waitFor(mc -> {
			PaquetesTienda.Oferta o = PantallaTienda.oferta(TipoAyuda.GUIA_BIOMA);
			return o != null && o.cantidad() == 6 && o.original() == 8;
		}, 100);
		ctx.waitTicks(10);
		captura(ctx, "descuento_01_tienda");
		ctx.setScreen(() -> null);
		paso("La tienda muestra la guía a bioma a 6 (8 tachado) con el descuento por ir en último lugar");

		String bioma = sp.getServer().computeOnServer(PruebaEventos::biomaLejano);
		comprobar(bioma != null, "hay un bioma para la guía");
		ctx.runOnClient(mc -> ClientPlayNetworking.send(new PaquetesAyudas.PedirGuia(PaquetesAyudas.PedirGuia.BIOMA, Identifier.withDefaultNamespace(bioma))));
		sp.getServer().waitFor(s -> Ayudas.tipoGuia(jugador(s).getUUID()) == TipoAyuda.GUIA_BIOMA, 20 * 40);
		int hierro = sp.getServer().computeOnServer(s -> contar(jugador(s), Items.IRON_INGOT));
		comprobar(hierro == 4, "la guía a bioma cobró 6 de hierro (quedan " + hierro + " de 10)");
		paso("Guía a " + bioma + " comprada con 6 de hierro en vez de 8");

		// Rojo empata: se acaba el descuento.
		sp.getServer().runCommand("give Player0 " + item(sp, 5));
		sp.getServer().runCommand("give Player0 " + item(sp, 10));
		sp.getServer().waitFor(s -> partida().carton().puntos(ColorEquipo.ROJO) == 2, 100);
		comprobar(sp.getServer().computeOnServer(s -> !Descuento.aplica(jugador(s)) && Ayudas.precioPara(jugador(s), TipoAyuda.GUIA_BIOMA).cantidad() == 8),
			"con el empate ya no hay descuento");
		paso("Con 2 a 2 la guía vuelve a costar 8");
	}

	private static void teletransporte(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		Vec3 antes0 = sp.getServer().computeOnServer(s -> jugador(s).position());
		Vec3 antesBot = sp.getServer().computeOnServer(s -> bot(s).position());
		float vida = sp.getServer().computeOnServer(s -> jugador(s).getHealth());
		lanzar(ctx, sp, TipoEvento.TELETRANSPORTE, false);
		BlockPos destino = sp.getServer().computeOnServer(s -> partida().eventos().destinoTeletransporte(jugador(s).getUUID()));
		comprobar(destino != null, "Player0 fue teletransportado");
		double distancia = Math.hypot(destino.getX() + 0.5 - antes0.x, destino.getZ() + 0.5 - antes0.z);
		comprobar(distancia >= 149 && distancia <= 301, "a entre 150 y 300 bloques (" + Math.round(distancia) + ")");
		comprobar(sp.getServer().computeOnServer(s -> LugarSeguro.seguro(partida().mundos().mundo(), destino)), "el destino es seguro");
		comprobar(sp.getServer().computeOnServer(s -> bot(s).position().distanceTo(antesBot) > 100), "Bot1 también se movió");
		ctx.waitTicks(60);
		comprobar(sp.getServer().computeOnServer(s -> {
			ServerPlayer p = jugador(s);
			return p.isAlive() && p.onGround() && !p.isInWater() && !p.isInLava() && !p.isOnFire() && p.getHealth() >= vida
				&& p.blockPosition().distManhattan(destino) <= 2;
		}), "Player0 quedó de pie en suelo firme, sin agua, lava ni fuego y sin perder vida");
		ctx.waitTicks(40);
		captura(ctx, "eventos_22_teletransporte");
		paso("Teletransporte: Player0 apareció a " + Math.round(distancia) + " bloques, en " + destino + ", a salvo");
	}

	private static void intercambio(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().waitFor(s -> jugador(s).onGround() && bot(s).onGround(), 100);
		Vec3 p0 = sp.getServer().computeOnServer(s -> jugador(s).position());
		Vec3 b1 = sp.getServer().computeOnServer(s -> bot(s).position());
		lanzar(ctx, sp, TipoEvento.INTERCAMBIO, false);
		ctx.waitTicks(5);
		comprobar(sp.getServer().computeOnServer(s -> jugador(s).position().distanceTo(b1) < 1.5 && bot(s).position().distanceTo(p0) < 1.5),
			"Player0 y Bot1 cambiaron de lugar");
		ctx.waitTicks(60);
		captura(ctx, "eventos_23_intercambio");
		paso("Intercambio: Player0 (Rojo) y Bot1 (Azul) cambiaron de lugar");
	}

	private static void lluvia(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		List<Item> items = List.of(Items.BREAD, Items.IRON_INGOT, Items.COAL, Items.OAK_LOG);
		int[] antes = sp.getServer().computeOnServer(s -> items.stream().mapToInt(i -> contar(jugador(s), i)).toArray());
		lanzar(ctx, sp, TipoEvento.LLUVIA, false);
		ctx.waitTicks(8);
		captura(ctx, "eventos_24_lluvia");
		ctx.waitTicks(50);
		int[] total = sp.getServer().computeOnServer(s -> {
			ServerPlayer p = jugador(s);
			int[] t = new int[items.size()];
			for (int k = 0; k < items.size(); k++) {
				Item item = items.get(k);
				int suelo = p.level().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(8), e -> e.getItem().is(item))
					.stream().mapToInt(e -> e.getItem().getCount()).sum();
				t[k] = contar(p, item) - antes[k] + suelo;
			}
			return t;
		});
		comprobar(Arrays.equals(total, new int[]{4, 3, 6, 6}), "cayeron 4 panes, 3 de hierro, 6 de carbón y 6 troncos junto a Player0 " + Arrays.toString(total));
		paso("Lluvia de items: 4 panes, 3 lingotes de hierro, 6 de carbón y 6 troncos alrededor de Player0");
	}

	/** Tormenta: llueve y truena, y un rayo encima de Player0 (sin resistencia) no le quita vida ni lo prende. */
	private static void tormenta(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		lanzar(ctx, sp, TipoEvento.TORMENTA, false);
		comprobar(sp.getServer().computeOnServer(s -> partida().mundos().mundo().getWeatherData().isThundering()), "tormenta: truena");
		sp.getServer().runCommand("effect clear Player0 minecraft:resistance");
		ctx.waitTicks(5);
		float vida = sp.getServer().computeOnServer(s -> jugador(s).getHealth());
		sp.getServer().runCommand("execute at Player0 run summon minecraft:lightning_bolt ~ ~ ~");
		ctx.waitTicks(40);
		float despues = sp.getServer().computeOnServer(s -> jugador(s).getHealth());
		comprobar(despues >= vida && !sp.getServer().computeOnServer(s -> jugador(s).isOnFire()),
			"el rayo no lastimó a Player0 (" + vida + " → " + despues + ")");
		sp.getServer().runCommand("execute at Player0 run fill ~-3 ~-1 ~-3 ~3 ~2 ~3 minecraft:air replace #minecraft:fire");
		sp.getServer().runCommand("effect give Player0 minecraft:resistance 9999 4 true");
		ctx.waitTicks(80);
		captura(ctx, "eventos_27_tormenta");
		paso("Tormenta: lluvia y truenos; un rayo encima de Player0 no le hizo daño");
	}

	/** /venatio evento: comprueba el anuncio en el servidor y en el HUD, y espera a que empiece (o lo adelanta). */
	private static void lanzar(ClientGameTestContext ctx, TestSingleplayerContext sp, TipoEvento t, boolean esperarAnuncio) {
		sp.getServer().runCommand("venatio evento " + t.id);
		comprobar(sp.getServer().computeOnServer(s -> partida().eventos().anunciado() == t), "/venatio evento " + t.id + " lo anunció");
		ctx.waitFor(mc -> t.id.equals(HudSorpresas.eventoEnHud()), 60);
		if (!esperarAnuncio) sp.getServer().runOnServer(s -> partida().eventos().adelantarAnuncio());
		sp.getServer().waitFor(s -> partida().eventos().activo() == t, 20 * (Eventos.ANUNCIO + 5));
	}

	// ---------------------------------------------------------------- utilidades

	private static void volverAlLobby(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().waitFor(s -> Juego.INSTANCIA.partida() == null, 20 * 30);
		ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 200);
		ctx.runOnClient(mc -> mc.gui.setScreen(null));
		ctx.waitTicks(30);
	}

	private static ServerClockManager.ServerClockInstance reloj() {
		ServerLevel mundo = partida().mundos().mundo();
		return mundo.clockManager().getInstance(mundo.dimensionType().defaultClock().orElseThrow());
	}

	/** Nivel del efecto (0 = nivel I) o -1 si no lo tiene. */
	private static int efecto(ServerPlayer p, Holder<MobEffect> efecto) {
		MobEffectInstance i = p.getEffect(efecto);
		return i == null ? -1 : i.getAmplifier();
	}

	private static int cerca(ServerPlayer p, Class<? extends Entity> clase, double radio) {
		return p.level().getEntitiesOfClass(clase, p.getBoundingBox().inflate(radio)).size();
	}

	/** Un bioma común que exista en el mundo de la partida a más de 150 bloques del jugador. */
	private static String biomaLejano(MinecraftServer s) {
		ServerPlayer p = jugador(s);
		BlockPos pos = p.blockPosition();
		for (String nombre : BIOMAS_CANDIDATOS) {
			ResourceKey<Biome> clave = ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace(nombre));
			Pair<BlockPos, Holder<Biome>> hallado = p.level().findClosestBiome3d(b -> b.is(clave), pos, 2048, 32, 64);
			if (hallado == null) continue;
			double dx = hallado.getFirst().getX() - pos.getX(), dz = hallado.getFirst().getZ() - pos.getZ();
			if (Math.sqrt(dx * dx + dz * dz) >= 150) return nombre;
		}
		return null;
	}

	private static String item(TestSingleplayerContext sp, int indice) {
		return sp.getServer().computeOnServer(s -> BuiltInRegistries.ITEM.getKey(partida().carton().casillas.get(indice).icono.getItem()).toString());
	}

	private static String actual(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(s -> actualEnServidor());
	}

	private static String actualEnServidor() {
		return BuiltInRegistries.ITEM.getKey(partida().rastreo().actual(null).icono().getItem()).toString();
	}

	private static int puntos(ColorEquipo c) {
		Partida p = partida();
		return p == null ? -1 : p.carton().puntos(c);
	}

	private static int puntos(TestSingleplayerContext sp, ColorEquipo c) {
		return sp.getServer().computeOnServer(s -> puntos(c));
	}

	private static int contar(ServerPlayer p, Item item) {
		int n = 0;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			if (p.getInventory().getItem(i).is(item)) n += p.getInventory().getItem(i).getCount();
		}
		return n;
	}

	/** Captura sin los avisos de logros y recetas, que tapan la esquina superior derecha. */
	private static void captura(ClientGameTestContext ctx, String nombre) {
		ctx.runOnClient(mc -> mc.gui.toastManager().clear());
		ctx.waitTicks(2);
		ctx.takeScreenshot(nombre);
	}

	/** Detiene el reloj de las animaciones en el instante actual y lo devuelve. */
	private static long congelar(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> {
			long t = Animaciones.ahora();
			Animaciones.fijarReloj(t);
			return t;
		});
	}

	private static void reloj(ClientGameTestContext ctx, Long ms) {
		ctx.runOnClient(mc -> Animaciones.fijarReloj(ms));
		ctx.waitTicks(2);
	}

	private static int veces(ClientGameTestContext ctx, SoundEvent sonido) {
		return ctx.computeOnClient(mc -> Sonidos.veces(sonido));
	}

	private static int veces(SoundEvent sonido) {
		return Sonidos.veces(sonido);
	}

	private static KeyMapping tecla(ClientGameTestContext ctx, String nombre) {
		return ctx.computeOnClient(mc -> Arrays.stream(mc.options.keyMappings)
			.filter(k -> k.getName().equals(nombre)).findFirst().orElseThrow());
	}

	private static Partida partida() {
		return Juego.INSTANCIA.partida();
	}

	private static Partida.Fase fase() {
		Partida p = partida();
		return p == null ? null : p.fase();
	}

	private static ServerPlayer jugador(MinecraftServer s) {
		return s.getPlayerList().getPlayerByName("Player0");
	}

	private static ServerPlayer bot(MinecraftServer s) {
		return s.getPlayerList().getPlayerByName("Bot1");
	}

	private static void comprobar(boolean condicion, String que) {
		if (!condicion) throw new AssertionError("Falló: " + que);
	}

	private static void paso(String texto) {
		Venatio.LOG.info("PRUEBA EVENTOS ✔ {}", texto);
	}
}
