package dev.venatio.carton;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.List;

/**
 * Casilla de logro (opción "Casillas de logros"): en vez de un item pide hacer algo, como entrar a un bioma, matar un mob
 * o dormir en una cama. Se cumple por jugador, igual que los items: lo logra cualquiera del equipo y la casilla es del equipo.
 *
 * @param objetivos   según el tipo: biomas, tipos de mob, comida, estadísticas propias de vanilla o bloques
 * @param umbral      ESTADISTICA: valor que hay que sumar desde que empezó la partida; ALTURA: la altura (negativa = bajar)
 * @param relacionado item cuya receta y procedencia ayudan a lograrlo (la cama para dormir, la comida para comer); null si no hay
 */
public record Reto(String id, Tipo tipo, int dificultad, Identifier icono, Identifier relacionado, List<Identifier> objetivos, int umbral) {

	public enum Tipo {
		/** Pisar cualquiera de los biomas. */
		BIOMA,
		/** Matar un mob de cualquiera de los tipos (estadística "killed"). */
		MATAR,
		/** Terminar de comer o beber el item (lo cuenta el disparador de logros CONSUME_ITEM). */
		COMER,
		/** Sumar el umbral en cualquiera de las estadísticas propias de vanilla (custom). */
		ESTADISTICA,
		/** Minar cualquiera de los bloques (estadística "mined"). */
		MINAR,
		/** Estar a esa altura o más arriba (umbral positivo) o a esa o más abajo (negativo). */
		ALTURA,
		/** Estar en el Nether de la partida. */
		NETHER,
		/** Acostarse en una cama (o en una de paja). */
		DORMIR,
		/** Domesticar cualquier animal (lo cuenta el disparador de logros TAME_ANIMAL). */
		DOMESTICAR
	}

	/** Marca propia en el icono: solo vive en el servidor (custom_data no viaja al cliente). */
	static final String MARCA = "venatio_reto";

	public Item itemIcono() {
		return item(icono);
	}

	/** Item relacionado o null. */
	public Item itemRelacionado() {
		return relacionado == null ? null : item(relacionado);
	}

	private static Item item(Identifier id) {
		return BuiltInRegistries.ITEM.getOptional(id).orElse(Items.BARRIER);
	}

	/** "Entra a un pantano", "Mata un creeper"… */
	public MutableComponent nombre() {
		return Component.translatable("venatio.reto." + id);
	}

	/** Cómo lograrlo, para la ficha. */
	public Component como() {
		return switch (tipo) {
			case BIOMA -> Component.translatable("venatio.reto.como.bioma");
			case MATAR -> {
				Component mob = BuiltInRegistries.ENTITY_TYPE.getOptional(objetivos.get(0)).map(EntityType::getDescription)
					.orElse(Component.literal(objetivos.get(0).getPath()));
				yield Component.translatable("venatio.reto.como.matar", mob);
			}
			case COMER -> Component.translatable("venatio.reto.como.comer", new ItemStack(item(objetivos.get(0))).getHoverName());
			case MINAR -> Component.translatable("venatio.reto.como.minar");
			default -> Component.translatable("venatio.reto." + id + ".como");
		};
	}

	/** Icono de la casilla: el item representativo con el nombre del logro. */
	public ItemStack pila() {
		ItemStack st = new ItemStack(itemIcono());
		st.set(DataComponents.ITEM_NAME, nombre());
		CompoundTag tag = new CompoundTag();
		tag.putString(MARCA, id);
		st.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return st;
	}

	/** El logro de un icono de casilla, o null si no es de logro. Solo funciona en el servidor. */
	public static Reto de(ItemStack icono) {
		CustomData datos = icono.get(DataComponents.CUSTOM_DATA);
		if (datos == null) return null;
		String id = datos.copyTag().getStringOr(MARCA, "");
		return id.isEmpty() ? null : Retos.porId(id);
	}

	/** Grupo para no llenar el cartón de un solo tipo de logro. */
	int grupo() {
		return switch (tipo) {
			case BIOMA -> 0;
			case MATAR -> 1;
			case COMER -> 2;
			default -> 3;
		};
	}
}
