package dev.venatio.juego;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;

/** Los ocho equipos posibles. El orden (ordinal) es el que viaja por la red. */
public enum ColorEquipo {
	ROJO(ChatFormatting.RED, 0xFF5555),
	AZUL(ChatFormatting.BLUE, 0x5577FF),
	VERDE(ChatFormatting.GREEN, 0x55FF55),
	AMARILLO(ChatFormatting.YELLOW, 0xFFFF55),
	MORADO(ChatFormatting.DARK_PURPLE, 0xAA44DD),
	NARANJA(ChatFormatting.GOLD, 0xFFAA00),
	CIAN(ChatFormatting.AQUA, 0x55FFFF),
	ROSA(ChatFormatting.LIGHT_PURPLE, 0xFF77DD);

	public final ChatFormatting formato;
	public final int rgb;

	ColorEquipo(ChatFormatting formato, int rgb) {
		this.formato = formato;
		this.rgb = rgb;
	}

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	public MutableComponent nombre() {
		return Component.translatable("venatio.equipo." + id()).withStyle(formato);
	}

	public static ColorEquipo porOrdinal(int i) {
		return i >= 0 && i < values().length ? values()[i] : null;
	}
}
