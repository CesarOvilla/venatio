package dev.venatio.prueba;

import dev.venatio.Venatio;
import dev.venatio.carton.Objetivo;
import dev.venatio.juego.Juego;
import dev.venatio.juego.Partida;
import dev.venatio.juego.Regalos;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Method;

/**
 * Opción "Élitro y cohetes" como en MCBingo: se dan al empezar, se reponen al acabarse y al reaparecer, desaparecen al
 * morir sin conservar inventario, y no cuentan para el cartón (el cohete del cartón hay que craftearlo).
 */
public final class PruebaRegalos implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.waitFor(mc -> mc.level != null && mc.level.dimension().toString().contains("venatio:lobby"), 600);
			sp.getServer().runCommand("venatio opcion elitro si");
			sp.getServer().runCommand("venatio opcion depth_strider si");
			sp.getServer().runCommand("execute as Player0 run venatio equipo rojo");
			sp.getServer().runCommand("venatio iniciar");
			sp.getServer().waitFor(s -> fase() == Partida.Fase.JUGANDO, 20 * 180);
			ctx.waitTicks(30);

			// ---------------- Lo que se da al empezar
			comprobar(sp.getServer().computeOnServer(s -> {
				ServerPlayer p = jugador(s);
				ItemStack pecho = p.getItemBySlot(EquipmentSlot.CHEST), pies = p.getItemBySlot(EquipmentSlot.FEET), slot8 = p.getInventory().getItem(8);
				return pecho.is(Items.ELYTRA) && Regalos.esRegalo(pecho) && pecho.has(DataComponents.UNBREAKABLE)
					&& pies.is(Items.LEATHER_BOOTS) && Regalos.esRegalo(pies)
					&& slot8.is(Items.FIREWORK_ROCKET) && slot8.getCount() == Regalos.COHETES && Regalos.esRegalo(slot8);
			}), "élitro puesto, botas puestas y 64 cohetes en la última casilla de la barra");
			ctx.takeScreenshot("regalos_01_inicio");
			paso("Al empezar: élitro y botas puestos, 64 cohetes en la casilla 9, todos irrompibles y marcados");

			// ---------------- No cuentan para el cartón; un cohete crafteado (sin marca) sí
			comprobar(sp.getServer().computeOnServer(s -> buscar(jugador(s), "minecraft:firework_rocket") == null),
				"los cohetes regalados no cuentan");
			comprobar(sp.getServer().computeOnServer(s -> buscar(jugador(s), "minecraft:elytra") == null
				&& buscar(jugador(s), "minecraft:leather_boots") == null), "el élitro y las botas regalados no cuentan");
			sp.getServer().runCommand("give Player0 minecraft:firework_rocket 3");
			ctx.waitTicks(5);
			comprobar(sp.getServer().computeOnServer(s -> {
				ItemStack hallado = buscar(jugador(s), "minecraft:firework_rocket");
				return hallado != null && !Regalos.esRegalo(hallado) && hallado.getCount() == 3;
			}), "un cohete sin marca (como uno crafteado) sí cuenta y no se apila con los regalados");
			paso("Los regalados no cuentan para el cartón; un cohete crafteado sí, y no se mezcla con ellos");

			// ---------------- Se reponen al acabarse
			sp.getServer().runCommand("clear Player0 minecraft:firework_rocket");
			sp.getServer().runCommand("item replace entity Player0 armor.chest with minecraft:air");
			sp.getServer().waitFor(s -> {
				ServerPlayer p = jugador(s);
				ItemStack pecho = p.getItemBySlot(EquipmentSlot.CHEST);
				return pecho.is(Items.ELYTRA) && Regalos.esRegalo(pecho) && contarRegalo(p, Items.FIREWORK_ROCKET) == Regalos.COHETES;
			}, 60);
			paso("Sin cohetes ni élitro, en menos de 3 s vuelven 64 cohetes y el élitro puesto");

			// ---------------- Morir sin conservar inventario: desaparecen (no quedan en el suelo) y se reponen al reaparecer
			sp.getServer().runOnServer(s -> Juego.INSTANCIA.partida().mundos().mundo().getGameRules().set(GameRules.KEEP_INVENTORY, false, s));
			ServerPlayer antes = sp.getServer().computeOnServer(PruebaRegalos::jugador);
			sp.getServer().runCommand("kill Player0");
			ctx.waitForScreen(DeathScreen.class);
			ctx.waitTicks(25);
			int regalosEnSuelo = sp.getServer().computeOnServer(s -> {
				var nivel = Juego.INSTANCIA.partida().mundos().mundo();
				AABB zona = new AABB(antes.blockPosition()).inflate(8);
				return (int) nivel.getEntitiesOfClass(ItemEntity.class, zona, e -> Regalos.esRegalo(e.getItem())).size();
			});
			comprobar(regalosEnSuelo == 0, "los regalos desaparecieron al morir (había " + regalosEnSuelo + " en el suelo)");
			ctx.clickScreenButton("deathScreen.respawn");
			sp.getServer().waitFor(s -> jugador(s) != antes && jugador(s).isAlive()
				&& jugador(s).getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA) && contarRegalo(jugador(s), Items.FIREWORK_ROCKET) == Regalos.COHETES, 200);
			ctx.waitFor(mc -> mc.gui.screen() == null, 100);
			ctx.waitTicks(10);
			ctx.takeScreenshot("regalos_02_reaparecio");
			paso("Al morir sin conservar inventario no quedan regalos en el suelo, y al reaparecer vuelven élitro y cohetes");

			sp.getServer().runCommand("venatio terminar");
			sp.getServer().waitFor(s -> Juego.INSTANCIA.partida() == null, 20 * 30);
			sp.getServer().runCommand("venatio opcion elitro no");
			sp.getServer().runCommand("venatio opcion depth_strider no");
		}
	}

	private static ItemStack buscar(ServerPlayer p, String id) {
		try {
			Method m = Partida.class.getDeclaredMethod("buscar", ServerPlayer.class, Objetivo.class);
			m.setAccessible(true);
			return (ItemStack) m.invoke(null, p, Objetivo.de(id));
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	private static int contarRegalo(ServerPlayer p, net.minecraft.world.item.Item item) {
		int n = 0;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (st.is(item) && Regalos.esRegalo(st)) n += st.getCount();
		}
		return n;
	}

	private static Partida.Fase fase() {
		Partida p = Juego.INSTANCIA.partida();
		return p == null ? null : p.fase();
	}

	private static ServerPlayer jugador(MinecraftServer s) {
		return s.getPlayerList().getPlayerByName("Player0");
	}

	private static void comprobar(boolean condicion, String que) {
		if (!condicion) throw new AssertionError("Falló: " + que);
	}

	private static void paso(String texto) {
		Venatio.LOG.info("PRUEBA REGALOS ✔ {}", texto);
	}
}
