package dev.venatio.juego;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

public final class Jugadores {
	private static final Identifier CONGELADO = Identifier.fromNamespaceAndPath("venatio", "congelado");

	private Jugadores() {}

	/** Deja al jugador como recién llegado: sin inventario, efectos ni experiencia, con vida y comida llenas. */
	public static void reiniciar(ServerPlayer p, GameType modo) {
		p.getInventory().clearContent();
		p.containerMenu.setCarried(ItemStack.EMPTY);
		p.removeAllEffects();
		p.setHealth(p.getMaxHealth());
		p.getFoodData().setFoodLevel(20);
		p.getFoodData().setSaturation(5f);
		p.setExperienceLevels(0);
		p.setExperiencePoints(0);
		p.clearFire();
		p.resetFallDistance();
		p.setGameMode(modo);
		congelar(p, false);
	}

	/** Impide caminar y saltar (cuenta atrás). Modificador temporal: no se guarda si el jugador sale. */
	public static void congelar(ServerPlayer p, boolean congelado) {
		AttributeInstance velocidad = p.getAttribute(Attributes.MOVEMENT_SPEED);
		AttributeInstance salto = p.getAttribute(Attributes.JUMP_STRENGTH);
		for (AttributeInstance a : new AttributeInstance[]{velocidad, salto}) {
			if (a == null) continue;
			a.removeModifier(CONGELADO);
			if (congelado) a.addTransientModifier(new AttributeModifier(CONGELADO, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
	}

	public static void titulo(ServerPlayer p, Component titulo, Component subtitulo, int entrada, int duracion, int salida) {
		p.connection.send(new ClientboundSetTitlesAnimationPacket(entrada, duracion, salida));
		p.connection.send(new ClientboundSetSubtitleTextPacket(subtitulo == null ? Component.empty() : subtitulo));
		p.connection.send(new ClientboundSetTitleTextPacket(titulo));
	}
}
