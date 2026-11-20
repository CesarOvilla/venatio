"""Extrae el pool de items de MCBingo (MIT, SirChi) a JSON.

Uso: python3 tools/extraer_pool_mcbingo.py <ruta a MCBingo/data/bingo/function/seed/chosen_items.mcfunction> <salida.json>

MCBingo elige 25 categorías distintas (de 50) y una dificultad 1-3 para cada una.
Las categorías 1-10 dan el mismo item en cualquier dificultad; las 11-50 tienen un item por dificultad.
"""
import json
import re
import sys

PATRON = re.compile(
    r"categories matches (\d+) if score .*? difficulties matches ([\d.]+) run function bingo:card_items/(\w+)"
)


# MCBingo detecta estas casillas con un tag de items ("cualquier letrero", "cualquier vela"…)
TAGS = {
    "boat_with_chest": "#minecraft:chest_boats",
    "hanging_sign": "#minecraft:hanging_signs",
    "harness": "#minecraft:harnesses",
    "sign": "#minecraft:signs",
    "candle": "#minecraft:candles",
    "egg": "#minecraft:eggs",
}


def rango(texto):
    if ".." in texto:
        a, b = texto.split("..")
        return list(range(int(a), int(b) + 1))
    return [int(texto)]


def main(origen, destino):
    categorias = {}
    with open(origen, encoding="utf-8") as f:
        for linea in f:
            m = PATRON.search(linea)
            if not m:
                continue
            cat, dif, item = int(m.group(1)), m.group(2), m.group(3)
            entrada = categorias.setdefault(cat, {"id": cat, "items": {}})
            for d in rango(dif):
                entrada["items"][str(d)] = TAGS.get(item, f"minecraft:{item}")
    salida = {
        "origen": "MCBingo 2.0 de SirChi (MIT) — categorías y dificultades originales",
        "reglas": "25 categorías distintas por cartón; cada una con dificultad 1 (fácil), 2 (media) o 3 (difícil). Un valor con # es un tag: vale cualquier item del tag",
        "categorias": [categorias[k] for k in sorted(categorias)],
    }
    with open(destino, "w", encoding="utf-8") as f:
        json.dump(salida, f, indent=2, ensure_ascii=False)
    items = {i for c in categorias.values() for i in c["items"].values()}
    print(f"{len(categorias)} categorías, {len(items)} items únicos -> {destino}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
