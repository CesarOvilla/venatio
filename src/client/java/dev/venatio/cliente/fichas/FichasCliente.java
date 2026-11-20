package dev.venatio.cliente.fichas;

import dev.venatio.red.PaquetesAyudas;
import dev.venatio.red.PaquetesFicha;
import dev.venatio.red.PaquetesFicha.Ficha;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fichas e items posibles recibidos del servidor. Se piden una vez por conexión; solo se usa en el hilo del cliente. */
public final class FichasCliente {
	private static final Map<Item, Ficha> fichas = new HashMap<>();
	/** Item → momento (ms) en que se pidió; si no llega respuesta en REINTENTO ms se vuelve a pedir. */
	private static final Map<Item, Long> pedidas = new HashMap<>();
	private static final long REINTENTO = 3000;
	private static List<String> itemsPosibles;
	private static long itemsPedidosEn = -REINTENTO;

	private FichasCliente() {}

	public static void registrar() {
		ClientPlayNetworking.registerGlobalReceiver(Ficha.TYPE, (f, ctx) ->
			BuiltInRegistries.ITEM.getOptional(f.item()).ifPresent(item -> {
				fichas.put(item, f);
				pedidas.remove(item);
			}));
		ClientPlayNetworking.registerGlobalReceiver(PaquetesFicha.ItemsPosibles.TYPE, (p, ctx) -> itemsPosibles = p.entradas());
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> limpiar());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> limpiar());
	}

	private static void limpiar() {
		fichas.clear();
		pedidas.clear();
		itemsPosibles = null;
		itemsPedidosEn = -REINTENTO;
	}

	/** La ficha si ya llegó; si no, la pide (una vez) y devuelve null. */
	static Ficha ficha(Item item) {
		Ficha f = fichas.get(item);
		long ahora = Util.getMillis();
		if (f == null && ahora - pedidas.getOrDefault(item, -REINTENTO) >= REINTENTO
			&& ClientPlayNetworking.canSend(PaquetesFicha.PedirFicha.TYPE)) {
			pedidas.put(item, ahora);
			ClientPlayNetworking.send(new PaquetesFicha.PedirFicha(BuiltInRegistries.ITEM.getKey(item)));
		}
		return f;
	}

	/** Entradas del pool ("minecraft:cake" o "#minecraft:signs"); null mientras llegan. */
	static List<String> itemsPosibles() {
		long ahora = Util.getMillis();
		if (itemsPosibles == null && ahora - itemsPedidosEn >= REINTENTO
			&& ClientPlayNetworking.canSend(PaquetesFicha.PedirItemsPosibles.TYPE)) {
			itemsPedidosEn = ahora;
			ClientPlayNetworking.send(PaquetesFicha.PedirItemsPosibles.INSTANCIA);
		}
		return itemsPosibles;
	}

	/** Envía la petición de guía al módulo de ayudas. false si el servidor no la acepta. */
	static boolean pedirGuia(int tipo, Identifier objetivo) {
		if (!ClientPlayNetworking.canSend(PaquetesAyudas.PedirGuia.TYPE)) return false;
		ClientPlayNetworking.send(new PaquetesAyudas.PedirGuia(tipo, objetivo));
		return true;
	}

	/** Items de un tag de items (los tags llegan sincronizados del servidor). */
	static List<ItemStack> itemsDelGrupo(Identifier tag) {
		List<ItemStack> lista = new ArrayList<>();
		for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, tag))) lista.add(new ItemStack(h));
		return lista;
	}

	/** Item que representa una entrada del pool: el item, o el primero de su grupo. */
	static ItemStack icono(String entrada) {
		if (entrada.startsWith("#")) {
			List<ItemStack> grupo = itemsDelGrupo(Identifier.parse(entrada.substring(1)));
			return grupo.isEmpty() ? new ItemStack(Items.BARRIER) : grupo.get(0);
		}
		return BuiltInRegistries.ITEM.getOptional(Identifier.parse(entrada)).map(ItemStack::new).orElse(new ItemStack(Items.BARRIER));
	}
}
