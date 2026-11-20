package dev.venatio.prueba;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.datafixers.util.Pair;
import dev.venatio.Venatio;
import dev.venatio.ayudas.Ayudas;
import dev.venatio.ayudas.TipoAyuda;
import dev.venatio.cliente.AyudasCliente;
import dev.venatio.cliente.PantallaObjetivos;
import dev.venatio.cliente.PantallaTienda;
import dev.venatio.juego.ColorEquipo;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Partida;
import dev.venatio.red.PaquetesAyudas;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ayudas con un cliente real: tienda (tecla H), guía a bioma pagada con hierro, compra sin materiales,
 * guía a estructura, marca de equipo (tecla N) y minimapa. Deja capturas ayudas_*.png en screenshots/.
 */
public final class PruebaAyudas implements FabricClientGameTest {
	private static final List<String> BIOMAS_CANDIDATOS = List.of(
		"river", "plains", "forest", "ocean", "taiga", "birch_forest", "savanna", "desert", "swamp", "dark_forest",
		"beach", "snowy_plains", "jungle", "meadow", "cherry_grove", "badlands", "flower_forest", "old_growth_pine_taiga");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		// El minimapa lo dibuja otro módulo. Si no está, la prueba escucha el permiso (si no, el servidor no puede mandarlo).
		AtomicInteger permisoRecibido = new AtomicInteger(-1);
		boolean receptorPropio = ctx.computeOnClient(mc -> ClientPlayNetworking.registerGlobalReceiver(
			PaquetesAyudas.PermisoMinimapa.TYPE, (permiso, c) -> permisoRecibido.set(permiso.segundos())));
		try {
			jugar(ctx, receptorPropio, permisoRecibido);
		} finally {
			if (receptorPropio) ctx.runOnClient(mc -> ClientPlayNetworking.unregisterGlobalReceiver(PaquetesAyudas.PermisoMinimapa.TYPE.id()));
		}
	}

	private static void jugar(ClientGameTestContext ctx, boolean receptorPropio, AtomicInteger permisoRecibido) {
		// El mundo de prueba por defecto es plano y sin estructuras; las guías a estructura necesitan que se generen.
		try (TestSingleplayerContext sp = ctx.worldBuilder().adjustSettings(s -> s.setGenerateStructures(true)).create()) {
			ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 600);
			ctx.waitFor(mc -> AyudasCliente.hayTienda(), 200);
			ctx.waitTicks(20);

			// En el lobby la tienda se abre, pero avisa que solo funciona en partida.
			ctx.getInput().pressKey(tecla(ctx, "key.venatio.ayudas"));
			ctx.waitForScreen(PantallaTienda.class);
			ctx.waitTicks(5);
			captura(ctx, "ayudas_00_tienda_lobby");
			ctx.setScreen(() -> null);

			sp.getServer().runCommand("execute as @a run venatio equipo rojo");
			sp.getServer().runCommand("venatio iniciar");
			sp.getServer().waitFor(s -> fase() == Partida.Fase.JUGANDO, 20 * 180);
			sp.getServer().runCommand("effect give @a minecraft:resistance 600 4 true");
			sp.getServer().runCommand("effect give @a minecraft:saturation 600 0 true");
			ctx.waitTicks(60);
			paso("Partida en juego");

			// ---------------------------------------------------------------- tienda y guía a bioma
			sp.getServer().runCommand("give @a minecraft:iron_ingot 10");
			ctx.waitTicks(5);
			ctx.getInput().pressKey(tecla(ctx, "key.venatio.ayudas"));
			ctx.waitForScreen(PantallaTienda.class);
			ctx.waitTicks(10);
			captura(ctx, "ayudas_01_tienda");
			paso("Tienda abierta con la tecla H");

			String bioma = sp.getServer().computeOnServer(PruebaAyudas::biomaLejano);
			if (bioma == null) throw new AssertionError("No hay ningún bioma candidato a más de 150 bloques");
			Identifier idBioma = Identifier.withDefaultNamespace(bioma);
			paso("Bioma elegido para la guía: " + bioma);

			ctx.clickScreenButton("Elegir…"); // la primera fila es la guía a bioma
			ctx.waitForScreen(PantallaObjetivos.class);
			ctx.getInput().typeChars(bioma.replace('_', ' '));
			ctx.waitTicks(5);
			int indice = ctx.computeOnClient(mc -> ((PantallaObjetivos) mc.gui.screen()).indiceDe(idBioma));
			if (indice < 0) throw new AssertionError("La búsqueda no encontró " + bioma);
			for (int i = 0; i < indice; i++) ctx.getInput().pressKey(InputConstants.KEY_DOWN);
			ctx.waitTicks(5);
			Identifier elegido = ctx.computeOnClient(mc -> ((PantallaObjetivos) mc.gui.screen()).seleccionado());
			if (!idBioma.equals(elegido)) throw new AssertionError("Seleccionado " + elegido + " en vez de " + idBioma);
			captura(ctx, "ayudas_02_elegir_bioma");
			ctx.getInput().pressKey(InputConstants.KEY_RETURN);

			sp.getServer().waitFor(s -> Ayudas.tipoGuia(jugador(s).getUUID()) == TipoAyuda.GUIA_BIOMA, 20 * 40);
			int hierro = sp.getServer().computeOnServer(s -> contar(jugador(s), Items.IRON_INGOT));
			if (hierro != 2) throw new AssertionError("Se esperaban 2 lingotes de hierro después de pagar 8; hay " + hierro);
			ctx.waitFor(mc -> idBioma.equals(AyudasCliente.objetivoGuia()), 100);
			ctx.waitTicks(30);
			captura(ctx, "ayudas_03_flecha_bioma");
			paso("Guía a bioma comprada con 8 de hierro; la flecha apunta a " + sp.getServer().computeOnServer(s -> Ayudas.zonaGuia(jugador(s).getUUID())));

			// Dando la vuelta, la flecha cambia de sentido.
			sp.getServer().runCommand("execute as @a at @s run tp @s ~ ~ ~ ~180 0");
			ctx.waitTicks(10);
			captura(ctx, "ayudas_04_flecha_girada");

			// ---------------------------------------------------------------- sin materiales
			ctx.runOnClient(mc -> ClientPlayNetworking.send(new PaquetesAyudas.PedirGuia(PaquetesAyudas.PedirGuia.ESTRUCTURA,
				Identifier.withDefaultNamespace("trial_chambers"))));
			ctx.waitTicks(10);
			boolean cobroSinMateriales = sp.getServer().computeOnServer(s ->
				Ayudas.buscandoGuia(jugador(s).getUUID()) || Ayudas.tipoGuia(jugador(s).getUUID()) != TipoAyuda.GUIA_BIOMA);
			if (cobroSinMateriales) throw new AssertionError("Sin diamantes no debió empezar la guía a estructura");
			captura(ctx, "ayudas_05_sin_materiales");
			paso("Sin diamantes: la guía a estructura se rechazó con mensaje");

			// ---------------------------------------------------------------- guía a estructura
			sp.getServer().runCommand("give @a minecraft:diamond 3");
			ctx.waitTicks(5);
			ctx.setScreen(() -> new PantallaObjetivos(TipoAyuda.GUIA_ESTRUCTURA, new PantallaTienda()));
			ctx.getInput().typeChars("camaras");
			ctx.waitTicks(5);
			Identifier camaras = Identifier.withDefaultNamespace("trial_chambers");
			Identifier selEstructura = ctx.computeOnClient(mc -> ((PantallaObjetivos) mc.gui.screen()).seleccionado());
			if (!camaras.equals(selEstructura)) throw new AssertionError("Buscando \"camaras\" quedó seleccionado " + selEstructura);
			captura(ctx, "ayudas_06_elegir_estructura");
			ctx.getInput().pressKey(InputConstants.KEY_RETURN);
			sp.getServer().waitFor(s -> Ayudas.tipoGuia(jugador(s).getUUID()) == TipoAyuda.GUIA_ESTRUCTURA, 20 * 40);
			int diamantes = sp.getServer().computeOnServer(s -> contar(jugador(s), Items.DIAMOND));
			if (diamantes != 0) throw new AssertionError("La guía a estructura debía cobrar 3 diamantes; quedan " + diamantes);
			ctx.waitFor(mc -> camaras.equals(AyudasCliente.objetivoGuia()), 100);
			ctx.waitTicks(30);
			captura(ctx, "ayudas_07_flecha_estructura");
			paso("Guía a cámaras de prueba: zona " + sp.getServer().computeOnServer(s -> Ayudas.zonaGuia(jugador(s).getUUID()))
				+ ", jugador en " + sp.getServer().computeOnServer(s -> jugador(s).blockPosition()));

			// ---------------------------------------------------------------- marca de equipo
			// Columna de oro 12 bloques al norte: mirándola, la marca cae en un bloque conocido.
			BlockPos columna = sp.getServer().computeOnServer(s -> {
				ServerPlayer p = jugador(s);
				BlockPos base = p.blockPosition().north(12);
				for (int y = -1; y < 6; y++) p.level().setBlockAndUpdate(base.above(y), Blocks.GOLD_BLOCK.defaultBlockState());
				return base;
			});
			sp.getServer().runCommand("execute as @a at @s run tp @s ~ ~ ~ 180 0");
			ctx.waitTicks(10);
			ctx.getInput().pressKey(tecla(ctx, "key.venatio.marca"));
			sp.getServer().waitFor(s -> Ayudas.marcasDe(ColorEquipo.ROJO) == 1, 100);
			ctx.waitFor(mc -> AyudasCliente.marcasVisibles() == 1, 100);
			ctx.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
			ctx.waitTicks(15);
			captura(ctx, "ayudas_08_marca");
			paso("Marca puesta en la columna de oro en " + columna);
			sp.getServer().runCommand("execute as @a at @s run tp @s ~ ~ ~ 50 0");
			ctx.waitTicks(10);
			captura(ctx, "ayudas_09_marca_en_borde");
			sp.getServer().runCommand("execute as @a at @s run tp @s ~ ~ ~ 0 0");
			ctx.waitTicks(10);
			captura(ctx, "ayudas_09b_marca_detras");
			paso("Marca visible para el equipo, también fuera de la pantalla");

			// Segunda marca enseguida: enfriamiento.
			ctx.getInput().pressKey(tecla(ctx, "key.venatio.marca"));
			ctx.waitTicks(10);
			int marcas = sp.getServer().computeOnServer(s -> Ayudas.marcasDe(ColorEquipo.ROJO));
			if (marcas != 1) throw new AssertionError("La segunda marca debió esperar el enfriamiento; hay " + marcas);
			paso("La segunda marca respetó el enfriamiento");

			// ---------------------------------------------------------------- minimapa
			sp.getServer().runCommand("give @a minecraft:diamond 1");
			ctx.waitTicks(5);
			ctx.getInput().pressKey(tecla(ctx, "key.venatio.ayudas"));
			ctx.waitForScreen(PantallaTienda.class);
			ctx.waitTicks(5);
			ctx.clickScreenButton("Comprar");
			sp.getServer().waitFor(s -> Ayudas.segundosMinimapa(jugador(s).getUUID()) > 0, 100);
			int diamantesFin = sp.getServer().computeOnServer(s -> contar(jugador(s), Items.DIAMOND));
			if (diamantesFin != 0) throw new AssertionError("El minimapa debía cobrar 1 diamante; quedan " + diamantesFin);
			if (receptorPropio) ctx.waitFor(mc -> permisoRecibido.get() > 0, 100);
			paso("Minimapa comprado: " + sp.getServer().computeOnServer(s -> Ayudas.segundosMinimapa(jugador(s).getUUID()))
				+ " s; el cliente recibió el permiso: " + (receptorPropio ? permisoRecibido.get() + " s" : "(lo recibe el módulo del minimapa)"));

			ctx.getInput().pressKey(tecla(ctx, "key.venatio.ayudas"));
			ctx.waitForScreen(PantallaTienda.class);
			ctx.waitTicks(10);
			captura(ctx, "ayudas_10_tienda_enfriamientos");
			ctx.setScreen(() -> null);

			sp.getServer().runCommand("venatio terminar");
			sp.getServer().waitFor(s -> Juego.INSTANCIA.partida() == null, 20 * 30);
			ctx.waitFor(mc -> AyudasCliente.objetivoGuia() == null && AyudasCliente.marcasVisibles() == 0, 100);
			paso("Al terminar la partida se apagaron la guía y las marcas");
		}
	}

	/** Un bioma común que exista en el mundo de la partida a más de 150 bloques del jugador (para que la flecha se vea). */
	private static String biomaLejano(MinecraftServer s) {
		ServerPlayer p = jugador(s);
		ServerLevel nivel = p.level();
		BlockPos pos = p.blockPosition();
		for (String nombre : BIOMAS_CANDIDATOS) {
			ResourceKey<Biome> clave = ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace(nombre));
			Pair<BlockPos, Holder<Biome>> hallado = nivel.findClosestBiome3d(b -> b.is(clave), pos, 2048, 32, 64);
			if (hallado == null) continue;
			double dx = hallado.getFirst().getX() - pos.getX(), dz = hallado.getFirst().getZ() - pos.getZ();
			if (Math.sqrt(dx * dx + dz * dz) >= 150) return nombre;
		}
		return null;
	}

	/** Captura sin los avisos de logros y recetas, que tapan la esquina superior derecha. */
	private static void captura(ClientGameTestContext ctx, String nombre) {
		ctx.runOnClient(mc -> mc.gui.toastManager().clear());
		ctx.waitTicks(2);
		ctx.takeScreenshot(nombre);
	}

	private static KeyMapping tecla(ClientGameTestContext ctx, String nombre) {
		return ctx.computeOnClient(mc -> Arrays.stream(mc.options.keyMappings)
			.filter(k -> k.getName().equals(nombre)).findFirst().orElseThrow());
	}

	private static int contar(ServerPlayer p, Item item) {
		int n = 0;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			if (p.getInventory().getItem(i).is(item)) n += p.getInventory().getItem(i).getCount();
		}
		return n;
	}

	private static Partida.Fase fase() {
		Partida p = Juego.INSTANCIA.partida();
		return p == null ? null : p.fase();
	}

	private static ServerPlayer jugador(MinecraftServer s) {
		return s.getPlayerList().getPlayers().get(0);
	}

	private static void paso(String texto) {
		Venatio.LOG.info("PRUEBA AYUDAS ✔ {}", texto);
	}
}
