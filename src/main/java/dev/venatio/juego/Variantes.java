package dev.venatio.juego;

import dev.venatio.carton.Carton;
import dev.venatio.red.Paquetes;
import dev.venatio.red.PaquetesVariantes;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Variantes del cartón de una partida, todas opcionales y combinables entre sí y con los modos de bingo:
 * <ul>
 *   <li><b>Cartón oculto:</b> cada equipo empieza viendo la casilla central; cada casilla que consigue destapa sus cuatro
 *   vecinas. Una tapada no se puede marcar, y el servidor no manda lo que pide. En Lockout, la que toma otro equipo se
 *   destapa para todos como tomada. Al final se destapa todo.</li>
 *   <li><b>Draft:</b> ver {@link Draft}. El cartón se tapa (si es oculto) cuando termina.</li>
 *   <li><b>Casilla secreta:</b> una por equipo, distinta si se puede; vale doble en todo lo que cuenta casillas.</li>
 *   <li><b>Relevo:</b> las filas se reparten entre los miembros del equipo; una casilla solo cuenta si la consigue el
 *   dueño de su fila. Si se desconecta, la fila pasa a otro miembro conectado y vuelve cuando regresa.</li>
 * </ul>
 * En Rastreo y Switcheroo no hay cartón: ninguna aplica.
 */
public final class Variantes {
	private final Partida partida;
	private final boolean oculto, secreta, relevo;
	/** null si la partida no tiene draft. */
	private final Draft draft;

	/** Cartón oculto: lo que destapó cada equipo. */
	private final Map<ColorEquipo, BitSet> destapadas = new EnumMap<>(ColorEquipo.class);
	/** El cartón está tapado: desde que termina el draft (o desde el principio) hasta el final. */
	private boolean tapado;
	private final Map<ColorEquipo, Integer> secretas = new EnumMap<>(ColorEquipo.class);
	/** Relevo: dueño de cada fila al repartir y dueño ahora (cambia si se desconecta). */
	private final Map<ColorEquipo, UUID[]> filasOriginales = new EnumMap<>(ColorEquipo.class);
	private final Map<ColorEquipo, UUID[]> filas = new EnumMap<>(ColorEquipo.class);

	Variantes(Partida partida) {
		this.partida = partida;
		boolean hayCarton = partida.rastreo == null;
		Configuracion cfg = partida.cfg;
		oculto = hayCarton && cfg.oculto;
		secreta = hayCarton && cfg.secreta;
		relevo = hayCarton && cfg.relevo;
		draft = hayCarton && cfg.draft ? new Draft(partida, cfg.vetos) : null;
		if (oculto && draft == null) tapar();
	}

	private Carton carton() {
		return partida.carton;
	}

	// ---------------------------------------------------------------- ciclo de la partida

	/** El draft corre mientras se genera el mundo. */
	void tickPreparando() {
		if (draft == null || !draft.tick()) return;
		if (oculto) tapar();
		Juego.INSTANCIA.enviarEstadoATodos();
	}

	public boolean draftEnCurso() {
		return draft != null && draft.enCurso();
	}

	/** Empieza la cuenta atrás: se reparten las casillas secretas y las filas del relevo. */
	void alEmpezarCuenta() {
		if (draftEnCurso()) {
			draft.terminar();
			if (oculto) tapar();
		}
		if (secreta) elegirSecretas();
		if (relevo) repartirFilas();
		for (ServerPlayer p : partida.jugadoresEnLinea()) {
			if (oculto) p.sendSystemMessage(Component.translatable("venatio.oculto.aviso").withStyle(ChatFormatting.GOLD));
			if (secreta) p.sendSystemMessage(Component.translatable("venatio.secreta.aviso").withStyle(ChatFormatting.GOLD));
			if (relevo) avisarFilas(p);
		}
	}

	/** Cada segundo de juego: el relevo revisa quién sigue conectado. */
	void tickSegundo() {
		if (relevo) revisarFilas();
	}

	/** Terminó la partida: se destapa todo y las casillas secretas se ven. */
	void alTerminar() {
		tapado = false;
	}

	/** Líneas para el chat del final: "Casilla secreta de Rojo: Diamante (+1)". */
	List<Component> lineasFinal() {
		List<Component> lineas = new ArrayList<>();
		for (Map.Entry<ColorEquipo, Integer> s : secretas.entrySet()) {
			Carton.Casilla cas = carton().casillas.get(s.getValue());
			boolean conseguida = cas.logros.containsKey(s.getKey());
			lineas.add(Component.translatable("venatio.secreta.final", s.getKey().nombre(), cas.icono.getHoverName(),
				Component.translatable(conseguida ? "venatio.secreta.mas_uno" : "venatio.secreta.sin")
					.withStyle(conseguida ? ChatFormatting.GOLD : ChatFormatting.GRAY)));
		}
		return lineas;
	}

	// ---------------------------------------------------------------- detección y puntos

	/** Cartón oculto: la casilla está tapada para el equipo y no se puede marcar. */
	boolean tapada(int indice, ColorEquipo equipo) {
		return tapado && !destapadas.get(equipo).get(indice);
	}

	/** Quiénes del equipo pueden conseguir la casilla: con relevo, solo el dueño de su fila. */
	List<ServerPlayer> quienesCuentan(int indice, ColorEquipo equipo, List<ServerPlayer> activos) {
		if (!relevo || activos.isEmpty()) return activos;
		UUID[] duenos = filas.get(equipo);
		if (duenos == null) return activos;
		UUID dueno = duenos[indice / carton().tamano];
		List<ServerPlayer> lista = new ArrayList<>(1);
		for (ServerPlayer p : activos) if (p.getUUID().equals(dueno)) lista.add(p);
		return lista;
	}

	/** El equipo consiguió la casilla: destapa sus vecinas; en Lockout, además, se destapa para todos como tomada. */
	void alMarcar(int indice, ColorEquipo equipo) {
		if (!tapado) return;
		int n = carton().tamano, fila = indice / n, col = indice % n;
		BitSet mias = destapadas.get(equipo);
		mias.set(indice);
		if (fila > 0) mias.set(indice - n);
		if (fila < n - 1) mias.set(indice + n);
		if (col > 0) mias.set(indice - 1);
		if (col < n - 1) mias.set(indice + 1);
		if (partida.cfg.modo.exclusivo()) for (BitSet b : destapadas.values()) b.set(indice);
	}

	/** Anuncia una casilla conseguida. Con el cartón oculto, quien la tiene tapada lee solo que fue una casilla tapada. */
	void anunciar(int indice, ServerPlayer quien, ColorEquipo equipo, Component mensaje) {
		if (!tapado) {
			Juego.INSTANCIA.difundir(mensaje);
			return;
		}
		Component tapada = Component.translatable("venatio.oculto.conseguida", quien.getDisplayName(), equipo.nombre());
		for (ServerPlayer p : Juego.INSTANCIA.server().getPlayerList().getPlayers()) {
			p.sendSystemMessage(ocultaPara(partida.equipoDe(p), indice) ? tapada : mensaje);
		}
	}

	/** Casillas de más que suma el equipo: 1 si ya tiene su casilla secreta (vale doble). */
	int extra(ColorEquipo equipo) {
		Integer s = secretas.get(equipo);
		return s != null && carton().casillas.get(s).logros.containsKey(equipo) ? 1 : 0;
	}

	/** Lo que todavía podría sumar de más: 1 si su casilla secreta sigue sin dueño. */
	int extraPosible(ColorEquipo equipo) {
		Integer s = secretas.get(equipo);
		return s != null && carton().casillas.get(s).logros.isEmpty() ? 1 : 0;
	}

	// ---------------------------------------------------------------- draft

	/** Un jugador veta una casilla en el draft. Devuelve el error, o null si se vetó. */
	public Component vetar(ServerPlayer p, int indice) {
		if (draft == null) return Component.translatable("venatio.draft.error.no_draft");
		return draft.vetar(p, indice);
	}

	// ---------------------------------------------------------------- cartón oculto

	/** Tapa el cartón: cada equipo ve solo el centro (los cuatro del centro en un cartón par). */
	private void tapar() {
		int n = carton().tamano;
		BitSet centro = new BitSet();
		if (n % 2 == 1) centro.set(n / 2 * n + n / 2);
		else for (int f = n / 2 - 1; f <= n / 2; f++) for (int c = n / 2 - 1; c <= n / 2; c++) centro.set(f * n + c);
		for (ColorEquipo c : partida.equipos) destapadas.put(c, (BitSet) centro.clone());
		tapado = true;
	}

	/** Si quien recibe el estado no debe ver lo que pide la casilla. Sin equipo (no juega), solo lo que algún equipo destapó. */
	boolean ocultaPara(ColorEquipo equipo, int indice) {
		if (!tapado) return false;
		if (equipo != null && destapadas.containsKey(equipo)) return !destapadas.get(equipo).get(indice);
		for (BitSet b : destapadas.values()) if (b.get(indice)) return false;
		return true;
	}

	/** Una casilla tapada tal como viaja: sin item, logro ni cantidad; solo quién la tiene y cuándo. */
	static Paquetes.CasillaRed casillaTapada(List<Paquetes.Logro> logros) {
		ItemStack tapa = new ItemStack(Items.PAPER);
		tapa.set(DataComponents.ITEM_NAME, Component.translatable("venatio.oculto.tapada"));
		return new Paquetes.CasillaRed(tapa, logros, "", 1, List.of());
	}

	// ---------------------------------------------------------------- casilla secreta

	private void elegirSecretas() {
		List<Integer> indices = new ArrayList<>();
		for (int i = 0; i < carton().casillas.size(); i++) indices.add(i);
		Collections.shuffle(indices, new Random(partida.semilla * 0x2545F4914F6CDD1DL + 0x5EC2E7AL));
		int k = 0;
		for (ColorEquipo c : partida.equipos) secretas.put(c, indices.get(k++ % indices.size()));
	}

	// ---------------------------------------------------------------- relevo

	/** Fila r para el miembro r % miembros: con dos, filas 1, 3 y 5 al primero y 2 y 4 al segundo. */
	private void repartirFilas() {
		int n = carton().tamano;
		for (ColorEquipo c : partida.equipos) {
			List<UUID> miembros = miembros(c);
			if (miembros.isEmpty()) continue;
			UUID[] duenos = new UUID[n];
			for (int r = 0; r < n; r++) duenos[r] = miembros.get(r % miembros.size());
			filasOriginales.put(c, duenos);
			filas.put(c, duenos.clone());
		}
	}

	private List<UUID> miembros(ColorEquipo c) {
		List<UUID> lista = new ArrayList<>();
		partida.participantes.forEach((u, eq) -> {
			if (eq == c) lista.add(u);
		});
		return lista;
	}

	/**
	 * Cada fila es de su dueño original si está conectado; si no, de quien la tenga (si sigue conectado) o del miembro
	 * conectado con menos filas. Avisa al equipo de cada cambio.
	 */
	private void revisarFilas() {
		Set<UUID> conectados = new HashSet<>();
		for (ServerPlayer p : partida.jugadoresEnLinea()) conectados.add(p.getUUID());
		for (ColorEquipo c : partida.equipos) {
			UUID[] actual = filas.get(c), originales = filasOriginales.get(c);
			if (actual == null) continue;
			List<UUID> enLinea = new ArrayList<>();
			for (UUID u : miembros(c)) if (conectados.contains(u)) enLinea.add(u);
			if (enLinea.isEmpty()) continue;
			for (int r = 0; r < actual.length; r++) {
				UUID nuevo = conectados.contains(originales[r]) ? originales[r]
					: conectados.contains(actual[r]) ? actual[r] : elMenosCargado(enLinea, actual);
				if (nuevo.equals(actual[r])) continue;
				actual[r] = nuevo;
				Component aviso = Component.translatable("venatio.relevo.cambio", r + 1, Juego.INSTANCIA.nombre(nuevo)).withStyle(ChatFormatting.GOLD);
				for (ServerPlayer p : partida.jugadoresEnLinea()) if (partida.equipoDe(p) == c) p.sendSystemMessage(aviso);
				Juego.INSTANCIA.enviarEstadoATodos();
			}
		}
	}

	private static UUID elMenosCargado(List<UUID> candidatos, UUID[] actual) {
		UUID mejor = candidatos.get(0);
		int menos = Integer.MAX_VALUE;
		for (UUID u : candidatos) {
			int n = 0;
			for (UUID d : actual) if (u.equals(d)) n++;
			if (n < menos) {
				menos = n;
				mejor = u;
			}
		}
		return mejor;
	}

	private void avisarFilas(ServerPlayer p) {
		UUID[] duenos = filas.get(partida.equipoDe(p));
		if (duenos == null) return;
		List<String> mias = new ArrayList<>();
		for (int r = 0; r < duenos.length; r++) if (p.getUUID().equals(duenos[r])) mias.add(String.valueOf(r + 1));
		p.sendSystemMessage((mias.isEmpty() ? Component.translatable("venatio.relevo.sin_filas")
			: Component.translatable("venatio.relevo.aviso", String.join(", ", mias))).withStyle(ChatFormatting.GOLD));
	}

	// ---------------------------------------------------------------- red

	/** Lo que ve un jugador del equipo dado (null: no juega). Antes del final, cada equipo conoce solo su casilla secreta. */
	PaquetesVariantes.Variantes red(ColorEquipo miEquipo) {
		if (!oculto && draft == null && !secreta && !relevo) return PaquetesVariantes.Variantes.NINGUNA;
		List<PaquetesVariantes.Mascara> mascaras = new ArrayList<>();
		if (tapado) {
			destapadas.forEach((c, b) -> {
				long[] bits = b.toLongArray();
				mascaras.add(new PaquetesVariantes.Mascara(c.ordinal(), bits.length == 0 ? 0 : bits[0]));
			});
		}
		boolean alFinal = partida.fase() == Partida.Fase.FINAL;
		List<PaquetesVariantes.Secreta> secretasRed = new ArrayList<>();
		secretas.forEach((c, i) -> {
			if (alFinal || c == miEquipo) secretasRed.add(new PaquetesVariantes.Secreta(c.ordinal(), i));
		});
		List<PaquetesVariantes.Fila> filasRed = new ArrayList<>();
		filas.forEach((c, duenos) -> {
			for (int r = 0; r < duenos.length; r++) filasRed.add(new PaquetesVariantes.Fila(c.ordinal(), r, Juego.INSTANCIA.nombre(duenos[r])));
		});
		return new PaquetesVariantes.Variantes(tapado, mascaras, secretasRed, filasRed, draft == null ? null : draft.red());
	}

	// ---------------------------------------------------------------- consultas (pruebas)

	public int secretaDe(ColorEquipo equipo) {
		return secretas.getOrDefault(equipo, -1);
	}

	/** Para las pruebas: cambia la casilla secreta de un equipo. */
	public void ponerSecreta(ColorEquipo equipo, int indice) {
		secretas.put(equipo, indice);
	}

	public boolean destapada(ColorEquipo equipo, int indice) {
		return !tapada(indice, equipo);
	}

	/** Relevo: dueño actual de la fila (0 = la de arriba), o null sin relevo. */
	public UUID duenoDeFila(ColorEquipo equipo, int fila) {
		UUID[] d = filas.get(equipo);
		return d == null ? null : d[fila];
	}
}
