#!/bin/sh
# Instalador de Venatio para Mac y Linux.
# Uso:  curl -fsSL <url>/instalar.sh | sh
#
# Crea el perfil "Venatio" en el launcher oficial de Minecraft con su propia carpeta de juego
# (no toca tus otros mods ni tus mundos), instala Fabric y los mods del paquete, deja el juego
# en español y agrega el server de Venatio a la lista. Volver a ejecutarlo actualiza todo.
#
# Variables opcionales (para pruebas):
#   VENATIO_PAQUETE    URL (o file://) del archivo paquete.txt
#   VENATIO_MINECRAFT  carpeta de Minecraft a usar
set -eu

PAQUETE_URL="${VENATIO_PAQUETE:-https://raw.githubusercontent.com/CesarOvilla/venatio/main/instalador/paquete.txt}"
PERFIL_ID="venatio"

decir() { printf '%s\n' "$*"; }
fallar() { printf '\n❌ %s\n' "$*" >&2; exit 1; }

decir "== Instalador de Venatio =="

# --- 1. Carpeta de Minecraft -------------------------------------------------
SISTEMA="$(uname -s)"
if [ -n "${VENATIO_MINECRAFT:-}" ]; then
	MC="$VENATIO_MINECRAFT"
elif [ "$SISTEMA" = "Darwin" ]; then
	MC="$HOME/Library/Application Support/minecraft"
elif [ "$SISTEMA" = "Linux" ]; then
	if [ -d "$HOME/.minecraft" ]; then
		MC="$HOME/.minecraft"
	elif [ -d "$HOME/.var/app/com.mojang.Minecraft/.minecraft" ]; then
		MC="$HOME/.var/app/com.mojang.Minecraft/.minecraft"
	else
		MC="$HOME/.minecraft"
	fi
else
	fallar "Este instalador es para Mac y Linux. En Windows usa instalar.ps1."
fi
[ -f "$MC/launcher_profiles.json" ] || fallar "No encontré el launcher de Minecraft en: $MC
Abre el launcher oficial de Minecraft una vez, ciérralo y vuelve a ejecutar el instalador."
decir "Carpeta de Minecraft: $MC"

if pgrep -f "Minecraft Launcher|minecraft-launcher" >/dev/null 2>&1; then
	decir "⚠️  El launcher de Minecraft está abierto. Ciérralo antes de continuar para que vea el perfil nuevo."
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

descargar() { # url destino
	curl -fsSL --retry 3 -o "$2" "$1" || fallar "No se pudo descargar: $1"
}

sha512() {
	if command -v sha512sum >/dev/null 2>&1; then sha512sum "$1" | cut -d' ' -f1
	else shasum -a 512 "$1" | cut -d' ' -f1
	fi
}

# --- 2. Paquete --------------------------------------------------------------
descargar "$PAQUETE_URL" "$TMP/paquete.txt"
VERSION=""; MC_VERSION=""; FABRIC=""; SERVIDOR_DIR=""; SERVIDOR_NOMBRE="Venatio"
: > "$TMP/mods.txt"
set -f # sin comodines al partir las líneas
while IFS= read -r linea || [ -n "$linea" ]; do
	case "$linea" in
		""|\#*) continue ;;
	esac
	set -- $linea
	case "$1" in
		version) VERSION="$2" ;;
		minecraft) MC_VERSION="$2" ;;
		fabric) FABRIC="$2" ;;
		servidor) SERVIDOR_DIR="$2"; shift 2; [ $# -gt 0 ] && SERVIDOR_NOMBRE="$*" ;;
		mod) printf '%s %s %s\n' "$2" "$3" "$4" >> "$TMP/mods.txt" ;;
	esac
done < "$TMP/paquete.txt"
set +f
[ -n "$MC_VERSION" ] && [ -n "$FABRIC" ] || fallar "El paquete está incompleto."
decir "Paquete Venatio $VERSION · Minecraft $MC_VERSION · Fabric $FABRIC"

# --- 3. Fabric (sin Java: el perfil viene de meta.fabricmc.net) --------------
VERSION_ID="fabric-loader-$FABRIC-$MC_VERSION"
mkdir -p "$MC/versions/$VERSION_ID"
descargar "https://meta.fabricmc.net/v2/versions/loader/$MC_VERSION/$FABRIC/profile/json" "$TMP/perfil-fabric.json"
mv "$TMP/perfil-fabric.json" "$MC/versions/$VERSION_ID/$VERSION_ID.json"
decir "✔ Fabric $FABRIC para Minecraft $MC_VERSION"

# --- 4. Carpeta de juego y mods ------------------------------------------------
JUEGO="$MC/venatio"
mkdir -p "$JUEGO/mods" "$JUEGO/config"
: > "$TMP/conservar.txt"
while read -r archivo hash url; do
	printf '%s\n' "$archivo" >> "$TMP/conservar.txt"
	destino="$JUEGO/mods/$archivo"
	if [ -f "$destino" ] && [ "$(sha512 "$destino")" = "$hash" ]; then
		decir "✔ $archivo (ya estaba)"
		continue
	fi
	descargar "$url" "$TMP/$archivo"
	[ "$(sha512 "$TMP/$archivo")" = "$hash" ] || fallar "El archivo $archivo llegó dañado (no coincide su huella). Vuelve a intentar."
	mv "$TMP/$archivo" "$destino"
	decir "✔ $archivo"
done < "$TMP/mods.txt"

# Quita mods viejos del perfil de Venatio que ya no están en el paquete.
for jar in "$JUEGO/mods/"*.jar; do
	[ -e "$jar" ] || continue
	nombre="$(basename "$jar")"
	if ! grep -qxF "$nombre" "$TMP/conservar.txt"; then
		rm -f "$jar"
		decir "  (quitado $nombre: ya no es parte del paquete)"
	fi
done

# --- 5. Español y server en la lista ------------------------------------------
OPCIONES="$JUEGO/options.txt"
if [ -f "$OPCIONES" ]; then
	grep -v '^lang:' "$OPCIONES" > "$TMP/options.txt" || true
	printf 'lang:es_mx\n' >> "$TMP/options.txt"
	mv "$TMP/options.txt" "$OPCIONES"
else
	printf 'lang:es_mx\n' > "$OPCIONES"
fi
if [ -n "$SERVIDOR_DIR" ]; then
	printf 'nombre=%s\ndireccion=%s\n' "$SERVIDOR_NOMBRE" "$SERVIDOR_DIR" > "$JUEGO/config/venatio-servidor.txt"
fi
decir "✔ Juego en español y server $SERVIDOR_NOMBRE en la lista"

# --- 6. Perfil en el launcher ---------------------------------------------------
# Memoria para Minecraft según la RAM de la computadora (de 3 a 6 GB), con los flags de G1 que usa el launcher oficial.
RAM_GB=0
if [ "$SISTEMA" = "Darwin" ]; then
	RAM_GB=$(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1073741824 ))
elif [ -r /proc/meminfo ]; then
	RAM_GB=$(( $(awk '/^MemTotal:/ {print $2}' /proc/meminfo) / 1048576 ))
fi
if [ "$RAM_GB" -ge 16 ]; then XMX=6; elif [ "$RAM_GB" -ge 12 ]; then XMX=5; elif [ "$RAM_GB" -ge 8 ]; then XMX=4; else XMX=3; fi
export VENATIO_JAVA_ARGS="-Xmx${XMX}G -Xms1G -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M"
PERFILES="$MC/launcher_profiles.json"
cp "$PERFILES" "$PERFILES.respaldo-venatio"
export VENATIO_PERFILES="$PERFILES" VENATIO_PERFIL_ID="$PERFIL_ID" VENATIO_VERSION_ID="$VERSION_ID" VENATIO_JUEGO="$JUEGO"
export VENATIO_FECHA="$(date -u +%Y-%m-%dT%H:%M:%S.000Z)"

if [ "$SISTEMA" = "Darwin" ]; then
	# macOS: JavaScript de sistema (siempre disponible), sin depender de Python.
	osascript -l JavaScript >/dev/null <<'JXA' || fallar "No se pudo actualizar el perfil del launcher (se dejó un respaldo en launcher_profiles.json.respaldo-venatio)."
ObjC.import('Foundation');
var env = $.NSProcessInfo.processInfo.environment;
function v(k) { return env.objectForKey(k).js; }
var ruta = v('VENATIO_PERFILES');
var texto = $.NSString.stringWithContentsOfFileEncodingError(ruta, $.NSUTF8StringEncoding, null).js;
var datos = JSON.parse(texto);
if (!datos.profiles) datos.profiles = {};
var previo = datos.profiles[v('VENATIO_PERFIL_ID')] || {};
datos.profiles[v('VENATIO_PERFIL_ID')] = {
	name: 'Venatio',
	type: 'custom',
	icon: 'Crafting_Table',
	created: previo.created || v('VENATIO_FECHA'),
	lastUsed: v('VENATIO_FECHA'),
	lastVersionId: v('VENATIO_VERSION_ID'),
	gameDir: v('VENATIO_JUEGO'),
	javaArgs: previo.javaArgs || v('VENATIO_JAVA_ARGS')
};
$(JSON.stringify(datos, null, 2)).writeToFileAtomicallyEncodingError(ruta, true, $.NSUTF8StringEncoding, null);
JXA
else
	command -v python3 >/dev/null 2>&1 || fallar "Hace falta python3 para editar el perfil del launcher. Instálalo (p. ej. sudo apt install python3) y vuelve a intentar."
	python3 -I - <<'PY' || fallar "No se pudo actualizar el perfil del launcher (se dejó un respaldo en launcher_profiles.json.respaldo-venatio)."
import json, os
ruta = os.environ["VENATIO_PERFILES"]
with open(ruta, encoding="utf-8") as f:
    datos = json.load(f)
perfiles = datos.setdefault("profiles", {})
previo = perfiles.get(os.environ["VENATIO_PERFIL_ID"], {})
perfiles[os.environ["VENATIO_PERFIL_ID"]] = {
    "name": "Venatio",
    "type": "custom",
    "icon": "Crafting_Table",
    "created": previo.get("created", os.environ["VENATIO_FECHA"]),
    "lastUsed": os.environ["VENATIO_FECHA"],
    "lastVersionId": os.environ["VENATIO_VERSION_ID"],
    "gameDir": os.environ["VENATIO_JUEGO"],
    "javaArgs": previo.get("javaArgs", os.environ["VENATIO_JAVA_ARGS"]),
}
tmp = ruta + ".tmp"
with open(tmp, "w", encoding="utf-8") as f:
    json.dump(datos, f, indent=2, ensure_ascii=False)
os.replace(tmp, ruta)
PY
fi
decir "✔ Perfil \"Venatio\" en el launcher"

decir ""
decir "✅ Listo. Abre el launcher de Minecraft, elige el perfil \"Venatio\" y dale a Jugar."
decir "   La primera vez descarga Minecraft $MC_VERSION y tarda un poco más."
