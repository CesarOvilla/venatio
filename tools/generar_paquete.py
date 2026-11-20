"""Genera instalador/paquete.txt: qué instala el instalador de Venatio.

Uso:
  python3 tools/generar_paquete.py --venatio-url <url del jar publicado> --servidor <direccion> [--salida ruta]
  python3 tools/generar_paquete.py --local ...   (todas las URLs como file://, para probar el instalador sin internet propio)

Toma Fabric API y Sodium para la versión de Minecraft de gradle.properties desde Modrinth, y el jar de
Venatio de build/libs (hay que correr ./gradlew build antes).
"""
import argparse
import hashlib
import json
import pathlib
import urllib.request

RAIZ = pathlib.Path(__file__).resolve().parent.parent
MODS_MODRINTH = ["fabric-api", "sodium"]


def propiedades():
    datos = {}
    for linea in (RAIZ / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if "=" in linea and not linea.startswith("#"):
            k, v = linea.split("=", 1)
            datos[k.strip()] = v.strip()
    return datos


def modrinth(proyecto, mc):
    url = f"https://api.modrinth.com/v2/project/{proyecto}/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%22{mc}%22%5D"
    req = urllib.request.Request(url, headers={"User-Agent": "venatio-instalador"})
    with urllib.request.urlopen(req) as r:
        version = json.load(r)[0]
    archivo = next(f for f in version["files"] if f["primary"])
    return archivo["filename"], archivo["hashes"]["sha512"], archivo["url"]


def sha512(ruta):
    return hashlib.sha512(ruta.read_bytes()).hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--venatio-url", default="")
    ap.add_argument("--servidor", default="")
    ap.add_argument("--nombre-servidor", default="Venatio")
    ap.add_argument("--salida", default=str(RAIZ / "instalador" / "paquete.txt"))
    ap.add_argument("--local", action="store_true", help="descarga los mods a build/paquete y usa URLs file://")
    a = ap.parse_args()

    p = propiedades()
    mc, fabric, version = p["minecraft_version"], p["loader_version"], p["version"]
    jar = RAIZ / "build" / "libs" / f"venatio-{version}.jar"
    if not jar.exists():
        raise SystemExit(f"No existe {jar}; corre ./gradlew build")

    lineas = [
        "# Paquete de Venatio: lo lee el instalador (instalar.sh / instalar.ps1). Generado con tools/generar_paquete.py",
        f"version {version}",
        f"minecraft {mc}",
        f"fabric {fabric}",
    ]
    if a.servidor:
        lineas.append(f"servidor {a.servidor} {a.nombre_servidor}")

    local = RAIZ / "build" / "paquete"
    if a.local:
        local.mkdir(parents=True, exist_ok=True)
    for proyecto in MODS_MODRINTH:
        nombre, huella, url = modrinth(proyecto, mc)
        if a.local:
            destino = local / nombre
            if not destino.exists():
                urllib.request.urlretrieve(url, destino)
            url = destino.as_uri()
        lineas.append(f"mod {nombre} {huella} {url}")
    url_venatio = jar.as_uri() if a.local else a.venatio_url
    if not url_venatio:
        raise SystemExit("Falta --venatio-url (o usa --local)")
    lineas.append(f"mod {jar.name} {sha512(jar)} {url_venatio}")

    pathlib.Path(a.salida).write_text("\n".join(lineas) + "\n", encoding="utf-8")
    print(f"Paquete escrito en {a.salida}")


if __name__ == "__main__":
    main()
