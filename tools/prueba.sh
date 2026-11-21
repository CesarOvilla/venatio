#!/bin/sh
# Corre las pruebas con cliente real (./gradlew runClientGameTest) esperando turno: si otra copia del proyecto
# (otro worktree, otro agente) ya tiene un Minecraft de prueba abierto, espera a que termine. Dos clientes de
# prueba a la vez en esta Mac pueden colgarse con la ventana en negro.
#
# Uso:  tools/prueba.sh                 # la lista de src/gametest/resources/fabric.mod.json
#       tools/prueba.sh PruebaArte ...  # solo esas clases (deja la lista como estaba al terminar)
# Salida: el log completo en run/prueba-cliente.log; código de salida el de Gradle.

set -u
CANDADO="${VENATIO_CANDADO:-/private/tmp/claude-502/-Volumes-OvillaHome-ovilla/a488f318-070f-4d58-9ed0-a603be45a0bb/scratchpad/venatio-gametest.lock}"
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
LISTA="$RAIZ/src/gametest/resources/fabric.mod.json"
LOG="$RAIZ/run/prueba-cliente.log"
mkdir -p "$RAIZ/run" "$(dirname "$CANDADO")"

# ---- turno
esperado=0
while ! mkdir "$CANDADO" 2>/dev/null; do
	duenio=$(cat "$CANDADO/pid" 2>/dev/null || echo "")
	if [ -n "$duenio" ] && ! kill -0 "$duenio" 2>/dev/null; then
		echo "Turno abandonado (pid $duenio ya no existe): lo libero"
		rm -rf "$CANDADO"
		continue
	fi
	if [ $((esperado % 60)) -eq 0 ]; then echo "Esperando turno para la prueba (lo tiene: $(cat "$CANDADO/quien" 2>/dev/null))…"; fi
	sleep 5
	esperado=$((esperado + 5))
done
echo $$ > "$CANDADO/pid"
echo "$RAIZ" > "$CANDADO/quien"

respaldo=""
liberar() {
	if [ -n "$respaldo" ] && [ -f "$respaldo" ]; then cp "$respaldo" "$LISTA"; rm -f "$respaldo"; fi
	rm -rf "$CANDADO"
}
trap liberar EXIT INT TERM

# ---- lista temporal de pruebas
if [ $# -gt 0 ]; then
	respaldo="$(mktemp)"
	cp "$LISTA" "$respaldo"
	python3 -I - "$LISTA" "$@" <<'EOF'
import json, sys
ruta, clases = sys.argv[1], sys.argv[2:]
d = json.load(open(ruta))
d["entrypoints"]["fabric-client-gametest"] = [c if "." in c else "dev.venatio.prueba." + c for c in clases]
open(ruta, "w").write(json.dumps(d, indent="\t", ensure_ascii=False) + "\n")
EOF
fi

# ---- prueba
cd "$RAIZ"
rm -rf build/run/clientGameTest/screenshots
./gradlew runClientGameTest --console=plain > "$LOG" 2>&1
salida=$?
echo "salida: $salida" >> "$LOG"
echo "Pasos: $(grep -c 'PRUEBA.*✔' "$LOG") · salida $salida · log en $LOG"
grep -E "Falló|Timed out|AssertionError" "$LOG" | head -5
exit $salida
