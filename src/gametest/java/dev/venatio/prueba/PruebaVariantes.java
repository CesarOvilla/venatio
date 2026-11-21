package dev.venatio.prueba;

import com.mojang.blaze3d.platform.InputConstants;
import dev.venatio.Venatio;
import dev.venatio.carton.Carton;
import dev.venatio.carton.Objetivo;
import dev.venatio.cliente.Animaciones;
import dev.venatio.cliente.PantallaCarton;
import dev.venatio.cliente.PantallaDraft;
import dev.venatio.cliente.PantallaFinal;
import dev.venatio.cliente.VariantesCliente;
import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Partida;
import dev.venatio.juego.ResumenPartida;
import dev.venatio.juego.Variantes;
import dev.venatio.mundo.Lobby;
import dev.venatio.red.Paquetes;
import dev.venatio.red.PaquetesVariantes;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Variantes del cartón con un cliente real y bots de Carpet. Player0 y Bot1 en Rojo, Bot2 en Azul.
 * Primera partida (Contrarreloj de 2 minutos) con las cuatro a la vez: draft desde la pantalla, cartón oculto (solo la
 * central, una tapada no se marca, el cliente no recibe lo tapado, destapar vecinas, vista de otro equipo), relevo (lo
 * que consigue Bot1 en una fila de Player0 no cuenta; si Bot1 se va, su fila pasa a Player0) y casilla secreta (vale 2,
 * decide la Contrarreloj y se revela al final). Segunda partida, Lockout con cartón oculto: la casilla que toma el rival
 * se destapa como tomada. Capturas variantes_* en screenshots/.
 */
public final class PruebaVariantes implements FabricClientGameTest {
	private static final long SEMILLA = 515151L;
	/** Cartón de 5×5: la central, la secreta de Rojo (a su izquierda, fila 3 de Player0), la que toma Azul (arriba de la central) y una lejana. */
	private static final int CENTRO = 12, SECRETA_ROJO = 11, ARRIBA = 7, LEJANA = 24, SECRETA_AZUL = 0;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 600);
			idioma(ctx, "es_mx");
			ctx.waitTicks(20);
			sp.getServer().runCommand("player Bot1 spawn");
			sp.getServer().runCommand("player Bot2 spawn");
			sp.getServer().waitFor(s -> bot(s, "Bot1") != null && bot(s, "Bot2") != null
				&& Lobby.esLobby(bot(s, "Bot1").level()) && Lobby.esLobby(bot(s, "Bot2").level()), 200);
			sp.getServer().runCommand("execute as Player0 run venatio equipo rojo");
			sp.getServer().runCommand("execute as Bot1 run venatio equipo rojo");
			sp.getServer().runCommand("execute as Bot2 run venatio equipo azul");

			cuatroVariantes(ctx, sp);
			lockoutOculto(ctx, sp);

			for (String o : new String[]{"oculto", "draft", "secreta", "relevo"}) sp.getServer().runCommand("venatio opcion " + o + " no");
			sp.getServer().runCommand("venatio modo linea");
			sp.getServer().runCommand("venatio tiempo 0");
			sp.getServer().runCommand("venatio semilla aleatoria");
			sp.getServer().runCommand("player Bot2 kill");
			idioma(ctx, "en_us");
		}
	}

	// ---------------------------------------------------------------- las cuatro juntas

	private static void cuatroVariantes(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runCommand("venatio modo contrarreloj");
		sp.getServer().runCommand("venatio tiempo 2");
		sp.getServer().runCommand("venatio semilla " + SEMILLA);
		sp.getServer().runCommand("venatio vetos 1");
		for (String o : new String[]{"oculto", "draft", "secreta", "relevo"}) sp.getServer().runCommand("venatio opcion " + o + " si");
		sp.getServer().runCommand("venatio iniciar");

		draft(ctx, sp);
		sp.getServer().waitFor(s -> fase() == Partida.Fase.JUGANDO, 20 * 180);
		ctx.waitTicks(40);

		// Casillas de prueba con items fuera del pool, y casillas secretas conocidas.
		sp.getServer().runOnServer(s -> {
			Carton c = carton();
			c.reemplazar(CENTRO, Objetivo.de("minecraft:bedrock"));
			c.reemplazar(SECRETA_ROJO, Objetivo.de("minecraft:command_block"));
			c.reemplazar(ARRIBA, Objetivo.de("minecraft:structure_block"));
			c.reemplazar(LEJANA, Objetivo.de("minecraft:jigsaw"));
			variantes().ponerSecreta(ColorEquipo.ROJO, SECRETA_ROJO);
			variantes().ponerSecreta(ColorEquipo.AZUL, SECRETA_AZUL);
			Juego.INSTANCIA.enviarEstadoATodos();
		});
		ctx.waitTicks(10);

		oculto(ctx, sp);
		relevoYDestape(ctx, sp);
		secreta(ctx, sp);
		vistaDeOtroEquipo(ctx);
		relevoDesconexion(sp);
		finalContrarreloj(ctx, sp);
	}

	private static void draft(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		// La pantalla del draft se abre sola, con el cartón completo (todavía no se tapa).
		ctx.waitForScreen(PantallaDraft.class);
		ctx.waitTicks(20);
		comprobar(sp.getServer().computeOnServer(s -> fase() == Partida.Fase.PREPARANDO && variantes().draftEnCurso()),
			"el draft corre antes de la cuenta atrás");
		comprobar(ctx.computeOnClient(mc -> {
			Paquetes.Estado e = VariantesCliente.estado();
			if (!e.variantes().draftEnCurso() || e.variantes().oculto()) return false;
			for (int i = 0; i < e.casillas().size(); i++) if (VariantesCliente.tapada(e, i, e.miEquipo())) return false;
			return true;
		}), "durante el draft el cartón se ve completo aunque sea oculto");
		ctx.takeScreenshot("variantes_01_draft");

		String antes = sp.getServer().computeOnServer(s -> carton().casillas.get(0).objetivo.texto());
		int[] c0 = ctx.computeOnClient(mc -> ((PantallaDraft) mc.gui.screen()).centroDe(0));
		clic(ctx, c0[0], c0[1]);
		ctx.waitFor(pantalla(PantallaDraft.class, p -> p.elegida() == 0), 40);
		ctx.waitTicks(3);
		ctx.takeScreenshot("variantes_02_draft_elegida");
		clic(ctx, c0[0], c0[1]);
		sp.getServer().waitFor(s -> !carton().casillas.get(0).objetivo.texto().equals(antes), 100);
		String despues = sp.getServer().computeOnServer(s -> carton().casillas.get(0).objetivo.texto());
		ctx.waitFor(mc -> {
			PaquetesVariantes.Draft d = VariantesCliente.estado().variantes().draft();
			return d != null && d.vetados().size() == 1 && d.vetados().get(0).jugador().equals("Player0")
				&& d.vetados().get(0).casilla() == 0 && d.vetados().get(0).equipo() == ColorEquipo.ROJO.ordinal();
		}, 100);
		ctx.getInput().setCursorPos(0, 0);
		ctx.waitTicks(20);
		ctx.takeScreenshot("variantes_03_draft_vetada");
		paso("Draft: dos clics en la casilla 0 de la pantalla la vetaron (" + antes + " → " + despues + "); la lista dice que fue Player0 de Rojo");

		// Bot2 no tiene cliente: veta por el servidor. Rojo ya no puede vetar otra. Con todos los equipos listos, el draft termina.
		Component[] errores = sp.getServer().computeOnServer(s -> new Component[]{
			variantes().vetar(jugador(s), 3), variantes().vetar(bot(s, "Bot2"), 0), variantes().vetar(bot(s, "Bot2"), 4)});
		comprobar(errores[0] != null, "Rojo ya usó su veto");
		comprobar(errores[1] != null, "la casilla ya vetada no se puede vetar otra vez");
		comprobar(errores[2] == null, "Bot2 vetó la casilla 4: " + (errores[2] == null ? "" : errores[2].getString()));
		sp.getServer().waitFor(s -> fase() != Partida.Fase.PREPARANDO, 20 * 150);
		ctx.waitFor(mc -> !(mc.gui.screen() instanceof PantallaDraft), 100);
		paso("Azul vetó la casilla 4; un segundo veto de Rojo y un veto repetido se rechazan; el draft terminó y su pantalla se cerró sola");
	}

	/** Solo se ve la central; una tapada no se marca aunque tengas el item; el cliente no recibe lo tapado. */
	private static void oculto(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		comprobar(sp.getServer().computeOnServer(s -> {
			for (int i = 0; i < 25; i++) if (variantes().destapada(ColorEquipo.ROJO, i) != (i == CENTRO)) return false;
			return true;
		}), "en el servidor, Rojo solo tiene destapada la central");
		List<ItemStack> reales = sp.getServer().computeOnServer(s -> {
			List<ItemStack> l = new ArrayList<>();
			for (Carton.Casilla c : carton().casillas) l.add(c.icono.copy());
			return l;
		});
		String error = ctx.computeOnClient(mc -> {
			Paquetes.Estado e = VariantesCliente.estado();
			if (!e.variantes().oculto()) return "el estado no dice que el cartón está oculto";
			for (int i = 0; i < e.casillas().size(); i++) {
				boolean tapada = VariantesCliente.tapada(e, i, e.miEquipo());
				if (tapada != (i != CENTRO)) return "la casilla " + i + (tapada ? " se ve tapada" : " se ve destapada");
				Paquetes.CasillaRed c = e.casillas().get(i);
				if (tapada && (ItemStack.isSameItemSameComponents(c.icono(), reales.get(i)) || !c.icono().is(Items.PAPER) || c.esReto()
					|| c.cantidad() != 1 || !c.progreso().isEmpty())) {
					return "la casilla tapada " + i + " trae lo que pide: " + c.icono();
				}
			}
			return ItemStack.isSameItemSameComponents(e.casillas().get(CENTRO).icono(), reales.get(CENTRO)) ? null : "la central no trae su item";
		});
		comprobar(error == null, "cartón oculto en el cliente: " + error);
		ctx.takeScreenshot("variantes_04_oculto_hud");
		ctx.setScreen(PantallaCarton::new);
		ctx.waitTicks(20);
		int[] lejana = ctx.computeOnClient(mc -> ((PantallaCarton) mc.gui.screen()).centroDe(LEJANA));
		apuntar(ctx, lejana[0], lejana[1]);
		ctx.waitTicks(5);
		ctx.takeScreenshot("variantes_05_oculto_pantalla_b");
		ctx.getInput().setCursorPos(0, 0);
		ctx.setScreen(() -> null);
		paso("Cartón oculto: solo la central a la vista (HUD y pantalla B); las 24 tapadas llegan al cliente como \"Casilla tapada\" sin su item");

		sp.getServer().runCommand("give Player0 minecraft:jigsaw");
		ctx.waitTicks(40);
		comprobar(sp.getServer().computeOnServer(s -> !tiene(LEJANA, ColorEquipo.ROJO)), "una casilla tapada no se marca aunque se tenga el item");
		paso("Player0 tiene el bloque rompecabezas de la casilla " + LEJANA + " (tapada) y no se marcó");
	}

	/** Relevo: la central es de la fila 3, de Player0. Si la consigue Bot1 no cuenta; si la consigue Player0, sí, y destapa sus vecinas. */
	private static void relevoYDestape(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		String filas = sp.getServer().computeOnServer(s -> {
			UUID p0 = jugador(s).getUUID(), b1 = bot(s, "Bot1").getUUID();
			StringBuilder sb = new StringBuilder();
			for (int f = 0; f < 5; f++) {
				UUID d = variantes().duenoDeFila(ColorEquipo.ROJO, f);
				UUID esperado = f % 2 == 0 ? p0 : b1;
				if (!esperado.equals(d)) return null;
				sb.append(f + 1).append(f % 2 == 0 ? ":Player0 " : ":Bot1 ");
			}
			return sb.toString().trim();
		});
		comprobar(filas != null, "relevo: filas 1, 3 y 5 de Player0 y 2 y 4 de Bot1");

		sp.getServer().runCommand("give Bot1 minecraft:bedrock");
		ctx.waitTicks(40);
		comprobar(sp.getServer().computeOnServer(s -> !tiene(CENTRO, ColorEquipo.ROJO)), "la central (fila de Player0) no cuenta si la consigue Bot1");
		paso("Relevo: filas " + filas + "; Bot1 tiene la piedra base de la central (fila 3, de Player0) y no contó");

		sp.getServer().runCommand("give Player0 minecraft:bedrock");
		sp.getServer().waitFor(s -> tiene(CENTRO, ColorEquipo.ROJO), 100);
		String quien = sp.getServer().computeOnServer(s -> nombre(s, carton().casillas.get(CENTRO).jugadores.get(ColorEquipo.ROJO)));
		comprobar(quien.equals("Player0"), "la central se anota a Player0 (fue " + quien + ")");
		// La animación de destapar, capturada a la mitad con el reloj de las animaciones parado.
		ctx.waitFor(mc -> VariantesCliente.destapando(SECRETA_ROJO), 100);
		ctx.runOnClient(mc -> Animaciones.fijarReloj(Animaciones.ahora() + 330));
		ctx.waitTicks(2);
		ctx.takeScreenshot("variantes_06_destapando");
		ctx.runOnClient(mc -> Animaciones.fijarReloj(null));
		ctx.waitTicks(20);
		comprobar(sp.getServer().computeOnServer(s -> {
			for (int i = 0; i < 25; i++) {
				boolean debe = i == CENTRO || i == ARRIBA || i == SECRETA_ROJO || i == 13 || i == 17;
				if (variantes().destapada(ColorEquipo.ROJO, i) != debe) return false;
			}
			return true;
		}), "al conseguir la central se destapan sus 4 vecinas y nada más");
		comprobar(ctx.computeOnClient(mc -> {
			Paquetes.Estado e = VariantesCliente.estado();
			for (int i : new int[]{ARRIBA, SECRETA_ROJO, 13, 17}) if (VariantesCliente.tapada(e, i, e.miEquipo())) return false;
			return e.casillas().get(SECRETA_ROJO).icono().is(Items.COMMAND_BLOCK) && VariantesCliente.tapada(e, 6, e.miEquipo());
		}), "el cliente recibe las 4 vecinas destapadas, con su item");
		paso("Relevo: Player0 consiguió la central y contó; se destaparon sus 4 vecinas (animación capturada a la mitad)");
	}

	/** Azul hace 2 casillas antes; Rojo hace la central y su secreta: 2 casillas que valen 3. */
	private static void secreta(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runCommand("give Bot2 minecraft:bedrock");
		sp.getServer().waitFor(s -> tiene(CENTRO, ColorEquipo.AZUL), 100);
		sp.getServer().runCommand("give Bot2 minecraft:structure_block");
		sp.getServer().waitFor(s -> tiene(ARRIBA, ColorEquipo.AZUL), 100);
		// Rojo llega a 2 casillas después que Azul: sin la casilla secreta, el desempate le daría la partida a Azul.
		ctx.waitTicks(50);
		sp.getServer().runCommand("give Player0 minecraft:command_block");
		sp.getServer().waitFor(s -> tiene(SECRETA_ROJO, ColorEquipo.ROJO), 100);
		int[] v = sp.getServer().computeOnServer(s -> new int[]{carton().puntos(ColorEquipo.ROJO), partida().valor(ColorEquipo.ROJO),
			carton().puntos(ColorEquipo.AZUL), partida().valor(ColorEquipo.AZUL)});
		comprobar(v[0] == 2 && v[1] == 3 && v[2] == 2 && v[3] == 2, "Rojo 2 casillas valen 3 (secreta doble), Azul 2 valen 2: "
			+ v[0] + "/" + v[1] + " y " + v[2] + "/" + v[3]);
		ctx.waitTicks(10);
		int[] marcador = ctx.computeOnClient(mc -> {
			int[] m = new int[2];
			for (Paquetes.EquipoRed q : VariantesCliente.estado().equipos()) m[q.color() == ColorEquipo.ROJO.ordinal() ? 0 : 1] = q.puntos();
			return m;
		});
		comprobar(marcador[0] == 3 && marcador[1] == 2, "el marcador de Player0 dice Rojo 3, Azul 2 (es " + marcador[0] + ", " + marcador[1] + ")");
		comprobar(ctx.computeOnClient(mc -> VariantesCliente.estado().variantes().secretas().size() == 1),
			"antes del final, Rojo solo conoce su casilla secreta");
		ctx.takeScreenshot("variantes_07_secreta_relevo_hud");
		paso("Casilla secreta: Rojo consiguió la suya (bloque de comandos) y suma 3 con 2 casillas; Azul 2; Rojo no ve la secreta de Azul");
	}

	/** Pantalla B: mi cartón con la secreta y las caras del relevo; el de Azul no muestra lo que Azul no destapó. */
	private static void vistaDeOtroEquipo(ClientGameTestContext ctx) {
		ctx.setScreen(PantallaCarton::new);
		ctx.waitTicks(20);
		int[] secreta = ctx.computeOnClient(mc -> ((PantallaCarton) mc.gui.screen()).centroDe(SECRETA_ROJO));
		apuntar(ctx, secreta[0], secreta[1]);
		ctx.waitTicks(5);
		ctx.takeScreenshot("variantes_08_pantalla_b_secreta_relevo");
		ctx.getInput().setCursorPos(0, 0);
		ctx.getInput().pressKey(InputConstants.KEY_RIGHT);
		ctx.waitTicks(5);
		String error = ctx.computeOnClient(mc -> {
			Paquetes.Estado e = VariantesCliente.estado();
			int rojo = ColorEquipo.ROJO.ordinal(), azul = ColorEquipo.AZUL.ordinal();
			// 10 está a la izquierda de la secreta de Rojo: Rojo la destapó, Azul no. 2 está arriba de la que tomó Azul: al revés.
			if (VariantesCliente.tapada(e, 10, rojo)) return "Rojo no ve la casilla 10";
			if (!VariantesCliente.tapada(e, 10, azul)) return "en la vista de Azul se ve la casilla 10, que Azul no destapó";
			if (!e.variantes().destapada(azul, 2) || !VariantesCliente.tapada(e, 2, azul)) return "la casilla 2 de Azul se ve aunque Rojo no la destapó";
			if (VariantesCliente.tapada(e, CENTRO, azul)) return "en la vista de Azul no se ve la central";
			return null;
		});
		comprobar(error == null, "vista de otro equipo: " + error);
		ctx.takeScreenshot("variantes_09_vista_azul");
		ctx.setScreen(() -> null);
		paso("Pantalla B: mi cartón con la secreta resaltada y las caras del relevo; en el de Azul, lo que Azul no destapó sigue tapado");
	}

	private static void relevoDesconexion(TestSingleplayerContext sp) {
		sp.getServer().runCommand("player Bot1 kill");
		sp.getServer().waitFor(s -> bot(s, "Bot1") == null, 100);
		sp.getServer().waitFor(s -> {
			UUID p0 = jugador(s).getUUID();
			for (int f = 0; f < 5; f++) if (!p0.equals(variantes().duenoDeFila(ColorEquipo.ROJO, f))) return false;
			return true;
		}, 100);
		paso("Relevo: Bot1 murió y salió del server; sus filas 2 y 4 pasaron a Player0");
	}

	/** Se acaba el tiempo: gana Rojo por la casilla secreta, que se revela en la pantalla final con lo demás destapado. */
	private static void finalContrarreloj(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().waitFor(s -> fase() == Partida.Fase.FINAL, 20 * 150);
		ResumenPartida r = sp.getServer().computeOnServer(s -> ResumenPartida.de(partida()));
		comprobar(r.ganador() == ColorEquipo.ROJO, "gana Rojo al acabarse el tiempo (ganó " + r.ganador() + ")");
		comprobar(r.equipos().get(0).color() == ColorEquipo.ROJO && r.equipos().get(0).casillas() == 3
			&& r.equipos().get(1).casillas() == 2, "marcador final: Rojo 3, Azul 2");
		ctx.waitForScreen(PantallaFinal.class);
		ctx.waitTicks(60);
		List<String> lineas = ctx.computeOnClient(mc -> VariantesCliente.lineasSecretas().stream().map(Component::getString).toList());
		comprobar(lineas.size() == 2, "se revelan las 2 casillas secretas: " + lineas);
		comprobar(lineas.stream().anyMatch(l -> l.startsWith("Casilla secreta de Rojo:") && l.endsWith("(+1)")),
			"la secreta de Rojo se revela con (+1): " + lineas);
		comprobar(lineas.stream().anyMatch(l -> l.startsWith("Casilla secreta de Azul:") && l.contains("no la consiguió")),
			"la de Azul, sin conseguir: " + lineas);
		comprobar(ctx.computeOnClient(mc -> {
			Paquetes.Estado e = VariantesCliente.estado();
			for (int i = 0; i < e.casillas().size(); i++) if (VariantesCliente.tapada(e, i, e.miEquipo()) || e.casillas().get(i).icono().is(Items.PAPER)) return false;
			return true;
		}), "al final se destapa todo");
		ctx.takeScreenshot("variantes_10_final");
		paso("Contrarreloj: ganó Rojo 3 a 2 por la casilla secreta (sin ella perdía el desempate). Final: " + String.join(" | ", lineas));
		ctx.setScreen(() -> null);
		sp.getServer().waitFor(s -> Juego.INSTANCIA.partida() == null, 20 * 30);
	}

	// ---------------------------------------------------------------- Lockout con cartón oculto

	private static void lockoutOculto(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runCommand("venatio modo lockout");
		sp.getServer().runCommand("venatio tiempo 0");
		for (String o : new String[]{"draft", "secreta", "relevo"}) sp.getServer().runCommand("venatio opcion " + o + " no");
		sp.getServer().runCommand("venatio iniciar");
		sp.getServer().waitFor(s -> fase() == Partida.Fase.JUGANDO, 20 * 180);
		ctx.waitTicks(30);
		sp.getServer().runOnServer(s -> {
			carton().reemplazar(CENTRO, Objetivo.de("minecraft:bedrock"));
			carton().reemplazar(ARRIBA, Objetivo.de("minecraft:structure_block"));
			Juego.INSTANCIA.enviarEstadoATodos();
		});
		sp.getServer().runCommand("give Bot2 minecraft:bedrock");
		sp.getServer().waitFor(s -> tiene(CENTRO, ColorEquipo.AZUL), 100);
		sp.getServer().runCommand("give Bot2 minecraft:structure_block");
		sp.getServer().waitFor(s -> tiene(ARRIBA, ColorEquipo.AZUL), 100);
		ctx.waitTicks(20);
		comprobar(sp.getServer().computeOnServer(s -> variantes().destapada(ColorEquipo.ROJO, ARRIBA) && !variantes().destapada(ColorEquipo.ROJO, 13)),
			"Lockout: la que tomó Azul se destapa para Rojo; la vecina de Azul no");
		String error = ctx.computeOnClient(mc -> {
			Paquetes.Estado e = VariantesCliente.estado();
			int mio = e.miEquipo();
			Paquetes.CasillaRed c = e.casillas().get(ARRIBA);
			if (VariantesCliente.tapada(e, ARRIBA, mio) || !c.icono().is(Items.STRUCTURE_BLOCK)) return "la tomada por Azul sigue tapada";
			if (c.logros().stream().noneMatch(l -> l.equipo() == ColorEquipo.AZUL.ordinal())) return "no dice que es de Azul";
			if (!VariantesCliente.tapada(e, 13, mio)) return "se ve una vecina que solo destapó Azul";
			return null;
		});
		comprobar(error == null, "Lockout oculto en el cliente: " + error);
		ctx.takeScreenshot("variantes_11_lockout_tomada");
		paso("Lockout oculto: Azul tomó la central y la de arriba; para Rojo se destaparon como tomadas, sin destapar sus vecinas");
		sp.getServer().runCommand("venatio terminar");
		sp.getServer().waitFor(s -> Juego.INSTANCIA.partida() == null, 20 * 30);
		ctx.setScreen(() -> null);
	}

	// ---------------------------------------------------------------- utilidades

	private static Partida partida() {
		return Juego.INSTANCIA.partida();
	}

	private static Carton carton() {
		return partida().carton();
	}

	private static Variantes variantes() {
		return partida().variantes();
	}

	private static boolean tiene(int indice, ColorEquipo equipo) {
		Partida p = partida();
		return p != null && p.carton().casillas.get(indice).logros.containsKey(equipo);
	}

	private static Partida.Fase fase() {
		Partida p = partida();
		return p == null ? null : p.fase();
	}

	private static ServerPlayer bot(MinecraftServer s, String nombre) {
		return s.getPlayerList().getPlayerByName(nombre);
	}

	private static ServerPlayer jugador(MinecraftServer s) {
		return bot(s, "Player0");
	}

	private static String nombre(MinecraftServer s, UUID uuid) {
		ServerPlayer p = uuid == null ? null : s.getPlayerList().getPlayer(uuid);
		return p == null ? "?" : p.getGameProfile().name();
	}

	@SuppressWarnings("unchecked")
	private static <T extends Screen> Predicate<Minecraft> pantalla(Class<T> tipo, Predicate<T> condicion) {
		return mc -> tipo.isInstance(mc.gui.screen()) && condicion.test((T) mc.gui.screen());
	}

	/** Mueve el ratón a coordenadas de interfaz. */
	private static void apuntar(ClientGameTestContext ctx, int x, int y) {
		double escala = ctx.computeOnClient(mc -> (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth());
		ctx.getInput().setCursorPos(x * escala + escala / 2, y * escala + escala / 2);
	}

	private static void clic(ClientGameTestContext ctx, int x, int y) {
		apuntar(ctx, x, y);
		ctx.waitTick();
		ctx.getInput().pressMouse(0);
		ctx.waitTicks(2);
	}

	private static void idioma(ClientGameTestContext ctx, String codigo) {
		CompletableFuture<Void> recarga = ctx.computeOnClient(mc -> {
			mc.options.languageCode = codigo;
			mc.getLanguageManager().setSelected(codigo);
			return mc.reloadResourcePacks();
		});
		ctx.waitFor(mc -> recarga.isDone() && mc.gui.overlay() == null, 20 * 60);
	}

	private static void comprobar(boolean condicion, String que) {
		if (!condicion) throw new AssertionError("Falló: " + que);
	}

	private static void paso(String texto) {
		Venatio.LOG.info("PRUEBA VARIANTES ✔ {}", texto);
	}
}
