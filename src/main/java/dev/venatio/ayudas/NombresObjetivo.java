package dev.venatio.ayudas;

import dev.venatio.red.PaquetesAyudas;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/** Nombre visible (clave de idioma) de lo que busca una guía. Lo usan el servidor (mensajes) y el cliente (HUD, tienda). */
public final class NombresObjetivo {
	private NombresObjetivo() {}

	public static Component de(int tipo, Identifier id) {
		return switch (tipo) {
			case PaquetesAyudas.PedirGuia.BIOMA -> bioma(id);
			case PaquetesAyudas.PedirGuia.ESTRUCTURA -> estructura(id);
			default -> mob(id);
		};
	}

	/** Traducción de vanilla: biome.minecraft.plains. */
	public static Component bioma(Identifier id) {
		return Component.translatable(Util.makeDescriptionId("biome", id));
	}

	/** Vanilla no traduce estructuras: venatio.ayudas.estructura.&lt;nombre&gt;, o el id si no está en la lista. */
	public static Component estructura(Identifier id) {
		return Component.translatableWithFallback("venatio.ayudas.estructura." + id.getPath(), id.getPath().replace('_', ' '));
	}

	/** Traducción de vanilla del tipo de entidad: entity.minecraft.zombie. */
	public static Component mob(Identifier id) {
		return BuiltInRegistries.ENTITY_TYPE.getOptional(id).map(t -> (Component) t.getDescription())
			.orElseGet(() -> Component.literal(id.getPath()));
	}
}
