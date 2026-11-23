#!/usr/bin/env python3
"""Genera las texturas de Venatio (pixel art dibujado por código, nada copiado).

Uso:
    python3 -m venv /tmp/arte-venv && /tmp/arte-venv/bin/pip install Pillow
    /tmp/arte-venv/bin/python tools/arte/texturas.py [--hoja vista.png]

Escribe los sprites de GUI en src/main/resources/assets/venatio/textures/gui/sprites/
(con su .png.mcmeta cuando son nine-slice) y el icono del mod en assets/venatio/icon.png.
--hoja guarda además una hoja de contacto ampliada para revisar el resultado de un vistazo.

Identidad: pabellón de caza. Madera oscura, cobre y dorado; los colores de equipo, vivos.
Los sprites "teñibles" van en grises: el juego los multiplica por el color del equipo.
"""
import json
import sys
from pathlib import Path

from PIL import Image

RAIZ = Path(__file__).resolve().parents[2]
ASSETS = RAIZ / "src/main/resources/assets/venatio"
SPRITES = ASSETS / "textures/gui/sprites"

# ---------------------------------------------------------------- paleta

T = (0, 0, 0, 0)
N = (0x14, 0x0D, 0x08, 255)   # contorno
M1 = (0x2A, 0x1A, 0x10, 255)  # madera: sombra
M2 = (0x3D, 0x27, 0x17, 255)  # madera: base
M3 = (0x52, 0x35, 0x1F, 255)  # madera: luz
M4 = (0x6B, 0x47, 0x2A, 255)  # madera: brillo
C1 = (0x7A, 0x3F, 0x24, 255)  # cobre: sombra
C2 = (0xB8, 0x63, 0x38, 255)  # cobre: base
C3 = (0xE0, 0x8F, 0x55, 255)  # cobre: luz
C4 = (0xF7, 0xC3, 0x8F, 255)  # cobre: brillo
O1 = (0x8A, 0x5F, 0x12, 255)  # oro: sombra
O2 = (0xD9, 0x9E, 0x24, 255)  # oro: base
O3 = (0xF2, 0xB8, 0x4B, 255)  # oro: luz
O4 = (0xFF, 0xE6, 0x9A, 255)  # oro: brillo
F1 = (0x10, 0x18, 0x12, 255)  # fieltro: sombra
F2 = (0x18, 0x24, 0x1B, 255)  # fieltro: base
F3 = (0x20, 0x30, 0x24, 255)  # fieltro: luz
H1 = (0x24, 0x1B, 0x14, 255)  # hueco de casilla: sombra
H2 = (0x37, 0x2C, 0x23, 255)  # hueco de casilla: base
H3 = (0x4B, 0x3D, 0x31, 255)  # hueco de casilla: luz
PERGAMINO = (0xE9, 0xDC, 0xB6, 255)
PERGAMINO_S = (0xBB, 0xA5, 0x78, 255)

# Mismos colores que dev.venatio.juego.ColorEquipo
EQUIPOS = {
    "rojo": 0xFF5555, "azul": 0x5577FF, "verde": 0x55FF55, "amarillo": 0xFFFF55,
    "morado": 0xAA44DD, "naranja": 0xFFAA00, "cian": 0x55FFFF, "rosa": 0xFF77DD,
}


def gris(v, a=255):
    return (v, v, v, a)


def alfa(c, a):
    return (c[0], c[1], c[2], a)


def mezcla(c, d, t):
    return tuple(round(c[i] + (d[i] - c[i]) * t) for i in range(3)) + (255,)


def rgb(n):
    return ((n >> 16) & 255, (n >> 8) & 255, n & 255, 255)


def lienzo(w, h, fondo=T):
    return Image.new("RGBA", (w, h), fondo)


def rect(img, x0, y0, x1, y1, c):
    """Rectángulo relleno, con x1 e y1 inclusive."""
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            img.putpixel((x, y), c)


def marco(img, x0, y0, x1, y1, c):
    for x in range(x0, x1 + 1):
        img.putpixel((x, y0), c)
        img.putpixel((x, y1), c)
    for y in range(y0, y1 + 1):
        img.putpixel((x0, y), c)
        img.putpixel((x1, y), c)


def mapa(filas, paleta, ancho=16):
    """Dibujo a partir de filas de texto: cada carácter es un color de la paleta ('.' = transparente)."""
    img = lienzo(ancho, len(filas))
    for y, fila in enumerate(filas):
        if len(fila) != ancho:
            raise ValueError(f"fila {y} mide {len(fila)}, no {ancho}: {fila!r}")
        for x, ch in enumerate(fila):
            if ch != ".":
                img.putpixel((x, y), paleta[ch])
    return img


SALIDA = {}  # nombre → (imagen, mcmeta o None)


def sprite(nombre, img, borde=None, estirar=False):
    meta = None
    if borde is not None:
        escala = {"type": "nine_slice", "width": img.width, "height": img.height, "border": borde}
        if estirar:
            escala["stretch_inner"] = True
        meta = {"gui": {"scaling": escala}}
    SALIDA[nombre] = (img, meta)


# ---------------------------------------------------------------- marcos

def esquina_cobre(img, x, y, tam, vx, vy):
    """Cantonera de cobre con remache. (x, y) es la esquina exterior; vx/vy = +1 o -1 hacia dentro."""
    for j in range(tam):
        for i in range(tam):
            if i + j >= tam + 1:
                continue
            c = C2
            if i == 0 or j == 0:
                c = N
            elif i == 1 or j == 1:
                c = C3 if (vx > 0 and vy > 0) else C2 if (vx > 0 or vy > 0) else C1
            elif i + j == tam:
                c = C1
            img.putpixel((x + i * vx, y + j * vy), c)
    # contorno diagonal
    for i in range(1, tam + 1):
        j = tam + 1 - i
        if 0 <= i < tam + 1 and 0 <= j < tam + 1:
            px, py = x + i * vx, y + j * vy
            if 0 <= px < img.width and 0 <= py < img.height:
                img.putpixel((px, py), N)
    img.putpixel((x + 2 * vx, y + 2 * vy), C4)


def marco_madera(lado, grosor, interior, veta=True):
    """Marco de madera oscura con bisel: luz arriba e izquierda, sombra abajo y derecha."""
    img = lienzo(lado, lado, interior)
    u = lado - 1
    for k in range(grosor):
        if k == 0:
            arriba = abajo = N
        elif k == 1:
            arriba, abajo = M4, M2
        elif k == grosor - 1:
            arriba = abajo = N
        elif k == grosor - 2:
            arriba, abajo = M2, M1
        else:
            arriba, abajo = M3, M2
        for i in range(k, lado - k):
            img.putpixel((i, k), arriba)
            img.putpixel((k, i), arriba)
        for i in range(k, lado - k):
            img.putpixel((i, u - k), abajo)
            img.putpixel((u - k, i), abajo)
    if veta and grosor >= 5:
        # vetas: unos pocos píxeles más oscuros a lo largo de cada larguero
        for i in range(grosor + 1, lado - grosor - 1, 5):
            img.putpixel((i, 2), M2)
            img.putpixel((i + 1, 2), M2)
            img.putpixel((2, i), M2)
            img.putpixel((2, i + 1), M2)
            img.putpixel((i + 2, u - 2), M1)
            img.putpixel((u - 2, i + 2), M1)
    return img


def marco_carton():
    """Marco y fondo del cartón: madera con cantoneras de cobre y fieltro verde oscuro."""
    lado, g = 26, 5
    img = marco_madera(lado, g, alfa(F2, 236))
    u = lado - 1
    # sombra interior del fieltro, pegada al marco
    for i in range(g, lado - g):
        img.putpixel((i, g), alfa(F1, 244))
        img.putpixel((g, i), alfa(F1, 244))
    for vx, vy, x, y in ((1, 1, 0, 0), (-1, 1, u, 0), (1, -1, 0, u), (-1, -1, u, u)):
        esquina_cobre(img, x, y, 6, vx, vy)
    sprite("carton/marco", img, borde=7)


def marco_pantalla():
    """Marco de las pantallas propias: madera gruesa, remaches de cobre y fondo oscuro casi opaco."""
    lado, g = 34, 6
    fondo = (0x17, 0x10, 0x0B, 240)
    img = marco_madera(lado, g, fondo)
    u = lado - 1
    for i in range(g, lado - g):
        img.putpixel((i, g), (0x0C, 0x08, 0x05, 248))
        img.putpixel((g, i), (0x0C, 0x08, 0x05, 248))
    for vx, vy, x, y in ((1, 1, 0, 0), (-1, 1, u, 0), (1, -1, 0, u), (-1, -1, u, u)):
        esquina_cobre(img, x, y, 8, vx, vy)
    sprite("pantalla/marco", img, borde=9)


def placa():
    """Placa de título: latón con filo claro arriba."""
    w, h = 20, 14
    img = lienzo(w, h, M1)
    marco(img, 0, 0, w - 1, h - 1, N)
    marco(img, 1, 1, w - 2, h - 2, O2)
    for x in range(1, w - 1):
        img.putpixel((x, 1), O4)
        img.putpixel((x, h - 2), O1)
    for y in range(2, h - 2):
        img.putpixel((1, y), O3)
        img.putpixel((w - 2, y), O1)
    marco(img, 2, 2, w - 3, h - 3, N)
    rect(img, 3, 3, w - 4, h - 4, (0x22, 0x15, 0x0D, 255))
    for x in range(3, w - 3):
        img.putpixel((x, 3), (0x17, 0x0E, 0x08, 255))
    # esquinas achaflanadas
    for x, y in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
        img.putpixel((x, y), T)
    sprite("pantalla/placa", img, borde=4)


def fila():
    """Fila de lista: hueco oscuro con filo de luz abajo."""
    img = lienzo(8, 8, (0x0E, 0x09, 0x06, 205))
    for x in range(8):
        img.putpixel((x, 0), (0x05, 0x03, 0x02, 230))
        img.putpixel((x, 7), alfa(M3, 150))
    for y in range(1, 7):
        img.putpixel((0, y), (0x05, 0x03, 0x02, 230))
        img.putpixel((7, y), alfa(M2, 150))
    sprite("pantalla/fila", img, borde=2)
    # la misma, resaltada (ratón encima o seleccionada): filo de cobre
    img = lienzo(8, 8, (0x2B, 0x1B, 0x10, 225))
    marco(img, 0, 0, 7, 7, C2)
    for x in range(8):
        img.putpixel((x, 0), C3)
        img.putpixel((x, 7), C1)
    sprite("pantalla/fila_activa", img, borde=2)


# ---------------------------------------------------------------- casillas

def casilla_vacia():
    img = lienzo(16, 16, H2)
    marco(img, 0, 0, 15, 15, H1)
    for i in range(16):
        img.putpixel((i, 0), N)
        img.putpixel((0, i), N)
    for i in range(1, 16):
        img.putpixel((i, 15), H3)
        img.putpixel((15, i), H3)
    for i in range(1, 15):
        img.putpixel((i, 1), H1)
        img.putpixel((1, i), H1)
    sprite("carton/casilla_vacia", img, borde=2)


def casilla_mia():
    """Teñible con el color del equipo: borde claro, fondo algo más oscuro y un brillo arriba."""
    img = lienzo(16, 16, gris(168))
    marco(img, 0, 0, 15, 15, gris(255))
    marco(img, 1, 1, 14, 14, gris(120))
    for i in range(1, 15):
        img.putpixel((i, 1), gris(215))
        img.putpixel((1, i), gris(215))
    sprite("carton/casilla_mia", img, borde=2)


def casilla_rival():
    """Alguien más ya la tiene (sigue disponible): hueco con la esquina doblada, donde van los puntos de equipo."""
    img = lienzo(16, 16, H2)
    for i in range(16):
        img.putpixel((i, 0), N)
        img.putpixel((0, i), N)
    for i in range(1, 16):
        img.putpixel((i, 15), H3)
        img.putpixel((15, i), H3)
    for i in range(1, 15):
        img.putpixel((i, 1), H1)
        img.putpixel((1, i), H1)
    # esquina inferior derecha: triángulo más claro con filo de cobre
    for y in range(9, 15):
        for x in range(9, 15):
            if x + y >= 23:
                img.putpixel((x, y), H3)
            elif x + y == 22:
                img.putpixel((x, y), C1)
    sprite("carton/casilla_rival", img, borde={"left": 2, "top": 2, "right": 8, "bottom": 8})


def casilla_bloqueada():
    """Lockout, tomada por otro equipo. Teñible: rayas diagonales apagadas con el color de quien la tomó."""
    img = lienzo(16, 16, gris(96))
    for y in range(2, 14):
        for x in range(2, 14):
            img.putpixel((x, y), gris(118) if (x + y) % 4 < 2 else gris(84))
    marco(img, 0, 0, 15, 15, gris(190))
    marco(img, 1, 1, 14, 14, gris(60))
    sprite("carton/casilla_bloqueada", img, borde=2)


def candado():
    """Candadito de latón que se pone encima de una casilla bloqueada."""
    filas = [
        "..####..",
        ".#gGGg#.",
        ".#G##G#.",
        ".#G#.G#.",
        "#######.",
        "#oOOOOo#",
        "#oO##Oo#",
        "#oO##Oo#",
        "#ooooOo#",
        ".######.",
    ]
    pal = {"#": N, "g": gris(0x8C), "G": gris(0xCE), "o": O1, "O": O3}
    sprite("carton/candado", mapa(filas, pal, 8))


def palomita():
    """Marca dorada de casilla conseguida."""
    filas = [
        ".......##",
        "......#Y#",
        ".....#YO#",
        "##..#YO#.",
        "#Y##YO#..",
        "#OYYO#...",
        ".#OO#....",
        "..##.....",
    ]
    pal = {"#": N, "Y": O4, "O": O3}
    sprite("carton/palomita", mapa(filas, pal, 9))


# ---------------------------------------------------------------- variantes del cartón

ROJO_VETO = ((0x7A, 0x16, 0x14, 255), (0xB8, 0x26, 0x22, 255), (0xE0, 0x4A, 0x3C, 255), (0xFF, 0x8A, 0x70, 255))


def casilla_tapada():
    """Cartón oculto: tapa de tablas oscuras con filo de cobre. El centro se repite, así que las vetas van cada 6 px."""
    img = lienzo(16, 16, M2)
    for y in range(2, 14):
        for x in range(2, 14):
            img.putpixel((x, y), M3 if (x + y) % 6 < 2 else M1 if (x + y) % 6 == 5 else M2)
    for i in range(16):
        img.putpixel((i, 0), N)
        img.putpixel((0, i), N)
        img.putpixel((i, 15), C1)
        img.putpixel((15, i), C1)
    for i in range(1, 15):
        img.putpixel((i, 1), C3)
        img.putpixel((1, i), C3)
        img.putpixel((i, 14), C2)
        img.putpixel((14, i), C2)
    sprite("carton/casilla_tapada", img, borde=2)


def interrogacion():
    """El signo de interrogación dorado que va encima de una casilla tapada: trazo de un píxel con contorno oscuro."""
    trazo = [
        ".OYO.",
        "O...O",
        "....O",
        "...O.",
        "..O..",
        "..O..",
        ".....",
        "..O..",
    ]
    img = lienzo(7, 10)
    puntos = {(x + 1, y + 1) for y, f in enumerate(trazo) for x, ch in enumerate(f) if ch != "."}
    for x, y in puntos:
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                if (x + dx, y + dy) not in puntos:
                    img.putpixel((x + dx, y + dy), N)
    for y, f in enumerate(trazo):
        for x, ch in enumerate(f):
            if ch != ".":
                img.putpixel((x + 1, y + 1), O4 if ch == "Y" or y == 0 else O3)
    sprite("carton/interrogacion", img)


def estrella_secreta():
    """Casilla secreta: estrellita dorada en la esquina."""
    filas = [
        "...#...",
        "..#Y#..",
        "##YYY##",
        "#OYYYO#",
        ".#OYO#.",
        "#OO#OO#",
        "##...##",
    ]
    pal = {"#": N, "Y": O4, "O": O2}
    sprite("carton/secreta", mapa(filas, pal, 7))


def sello_veto():
    """Draft: la X roja que marca una casilla vetada."""
    filas = [
        "##.....##",
        "#R#...#R#",
        ".#R#.#R#.",
        "..#RLR#..",
        "...#L#...",
        "..#RLR#..",
        ".#R#.#R#.",
        "#R#...#R#",
        "##.....##",
    ]
    pal = {"#": N, "R": ROJO_VETO[2], "L": ROJO_VETO[3]}
    sprite("carton/veto", mapa(filas, pal, 9))


def icono_veto():
    """Sello de lacre rojo con una X: el draft."""
    img = lienzo(16, 16)
    r1, r2, r3, _ = ROJO_VETO
    for y in range(16):
        for x in range(16):
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            if d > 7.6:
                continue
            if d > 6.6:
                c = N
            elif x + y < 12 and d > 5.2:
                c = r3
            elif x + y > 18 and d > 5.2:
                c = r1
            else:
                c = r2
            img.putpixel((x, y), c)
    for i in range(5, 11):
        img.putpixel((i, i + 1), r1)
        img.putpixel((15 - i, i + 1), r1)
    for i in range(5, 11):
        img.putpixel((i, i), PERGAMINO)
        img.putpixel((15 - i, i), PERGAMINO)
    sprite("icono/veto", img)


# ---------------------------------------------------------------- barra de progreso y banner

def barra():
    img = lienzo(8, 8, (0x0C, 0x08, 0x05, 235))
    marco(img, 0, 0, 7, 7, N)
    for x in range(1, 7):
        img.putpixel((x, 6), alfa(M3, 255))
        img.putpixel((x, 1), (0x05, 0x03, 0x02, 255))
    sprite("rastreo/barra_fondo", img, borde=2)
    # relleno teñible: más claro arriba, sombra abajo
    img = lienzo(4, 6)
    for x in range(4):
        for y, v in enumerate((255, 235, 200, 185, 160, 125)):
            img.putpixel((x, y), gris(v))
    sprite("rastreo/barra_relleno", img, borde={"left": 1, "top": 2, "right": 1, "bottom": 2}, estirar=True)


def banner():
    """Banner de victoria: cuerpo teñible (color del equipo) y, encima, el ribete dorado."""
    w, h = 24, 24
    cuerpo = lienzo(w, h, gris(150))
    for y in range(h):
        v = 175 - abs(y - h // 2 + 3) * 5
        for x in range(w):
            cuerpo.putpixel((x, y), gris(max(95, v)))
    sprite("banner/cuerpo", cuerpo, borde=4, estirar=True)

    borde = lienzo(w, h)
    marco(borde, 0, 0, w - 1, h - 1, N)
    marco(borde, 1, 1, w - 2, h - 2, O3)
    marco(borde, 2, 2, w - 3, h - 3, O1)
    marco(borde, 3, 3, w - 4, h - 4, N)
    for x in range(1, w - 1):
        borde.putpixel((x, 1), O4)
    for x in range(2, w - 2):
        borde.putpixel((x, h - 3), O2)
    # remaches en las esquinas
    for x, y in ((1, 1), (w - 4, 1), (1, h - 4), (w - 4, h - 4)):
        rect(borde, x, y, x + 2, y + 2, C2)
        borde.putpixel((x, y), C4)
        borde.putpixel((x + 2, y + 2), C1)
    sprite("banner/borde", borde, borde=5)


# ---------------------------------------------------------------- iconos 16×16

def icono_bingo():
    img = lienzo(16, 16, N)
    for fila_ in range(3):
        for col in range(3):
            x, y = 1 + col * 5, 1 + fila_ * 5
            if fila_ == col:
                rect(img, x, y, x + 3, y + 3, O3)
                img.putpixel((x, y), O4)
                img.putpixel((x + 1, y), O4)
                img.putpixel((x, y + 1), O4)
                for i in range(4):
                    img.putpixel((x + i, y + 3), O2)
                    img.putpixel((x + 3, y + i), O2)
                img.putpixel((x + 3, y + 3), O1)
            else:
                rect(img, x, y, x + 3, y + 3, H2)
                for i in range(4):
                    img.putpixel((x + i, y), H1)
                    img.putpixel((x, y + i), H1)
                img.putpixel((x + 3, y + 3), H3)
    # marco de madera alrededor
    marco(img, 0, 0, 15, 15, M2)
    for i in range(16):
        img.putpixel((i, 0), M4)
        img.putpixel((0, i), M3)
    for x, y in ((0, 0), (15, 0), (0, 15), (15, 15)):
        img.putpixel((x, y), C3)
    sprite("icono/bingo", img)


def icono_tienda():
    filas = [
        "................",
        ".....##..##.....",
        "....#bB##Bb#....",
        ".....#bBBb#.....",
        "....#cCCCCc#....",
        "...#bBBLLBBb#...",
        "..#bBBLBBBBBb#..",
        ".#bBLBB##BBBBb#.",
        ".#bBBB#OO#BBBb#.",
        "#bBLB#OYOO#BBBb#",
        "#bBBB#OOOo#BBBb#",
        "#bBBBB#Oo#BBBBb#",
        "#bBBBBB##BBBBBb#",
        ".#bBBBBBBBBBBb#.",
        "..#bbbbbbbbbb#..",
        "...##########...",
    ]
    pal = {"#": N, "b": (0x6B, 0x45, 0x25, 255), "B": (0x9C, 0x6B, 0x3A, 255), "L": (0xC2, 0x8E, 0x55, 255),
           "c": C1, "C": C3, "o": O1, "O": O3, "Y": O4}
    sprite("icono/tienda", mapa(filas, pal))


def icono_guia():
    filas = [
        ".....######.....",
        "...##cCCCCc##...",
        "..#cCppppppCc#..",
        ".#cCpPPPPPrpCc#.",
        ".#CpPPPPPrRPpc#.",
        "#cCpPPPPrRPPpCc#",
        "#CpPPPPrRPPPPpc#",
        "#CpPPPP##PPPPpc#",
        "#CpPPPP##PPPPpc#",
        "#CpPPPgGPPPPPpc#",
        "#cCpPgGPPPPPpCc#",
        ".#CpgGPPPPPPpc#.",
        ".#cCgPPPPPPpCc#.",
        "..#cCppppppCc#..",
        "...##cccccc##...",
        ".....######.....",
    ]
    pal = {"#": N, "c": C1, "C": C3, "p": PERGAMINO_S, "P": PERGAMINO,
           "r": (0x9E, 0x24, 0x1E, 255), "R": (0xE0, 0x45, 0x38, 255),
           "g": (0x4E, 0x4A, 0x46, 255), "G": (0x86, 0x80, 0x7A, 255)}
    sprite("icono/guia", mapa(filas, pal))


def icono_marca():
    """Banderín. Va en grises para teñirlo con el color del equipo."""
    filas = [
        "................",
        "....##..........",
        "...#pP#####.....",
        "...#pPWWWWW###..",
        "...#pPWWWWWWWW#.",
        "...#pPWWWWWWWW#.",
        "...#pPwwwww###..",
        "...#pP#####.....",
        "...#pP#.........",
        "...#pP#.........",
        "...#pP#.........",
        "...#pP#.........",
        "...#pP#.........",
        "..##pP##........",
        ".#pppPPP#.......",
        ".########.......",
    ]
    pal = {"#": gris(30), "p": gris(120), "P": gris(175), "W": gris(255), "w": gris(200)}
    sprite("icono/marca", mapa(filas, pal))


def icono_minimapa():
    filas = [
        "................",
        "................",
        ".####.....####..",
        "#pPPP#####PPPp#.",
        "#pPvPpPPPPpPPPp#",
        "#pvvPpPPaPpPPPp#",
        "#pPvPpPaaPpPrPp#",
        "#pPPPpPPaPprPPp#",
        "#pPPPpPPPPrPPPp#",
        "#pPrPpPPPrpPvPp#",
        "#pPPrprrrPpvvPp#",
        "#pPPPpPPPPpPvPp#",
        "#pPPP#####PPPp#.",
        ".####.....####..",
        "................",
        "................",
    ]
    pal = {"#": N, "p": PERGAMINO_S, "P": PERGAMINO, "v": (0x4F, 0x9A, 0x4A, 255),
           "a": (0x4A, 0x86, 0xC8, 255), "r": (0xC8, 0x3C, 0x30, 255)}
    sprite("icono/minimapa", mapa(filas, pal))


def icono_reloj():
    """Reloj de arena con tapas de cobre."""
    filas = [
        "................",
        "..############..",
        "..#cCCCCCCCCc#..",
        "..############..",
        "...#wggggggw#...",
        "...#wgSSSSgw#...",
        "....#wSSSSw#....",
        ".....#wSsw#.....",
        "......#ws#......",
        ".....#wSgw#.....",
        "....#wgSggw#....",
        "...#wgsSSsgw#...",
        "...#wSSSSSSw#...",
        "..############..",
        "..#cCCCCCCCCc#..",
        "..############..",
    ]
    pal = {"#": N, "c": C1, "C": C3, "w": (0xD6, 0xE8, 0xEA, 255), "g": (0x6F, 0x8C, 0x93, 255),
           "S": O3, "s": O2}
    sprite("icono/reloj", mapa(filas, pal))


def icono_trofeo():
    filas = [
        "................",
        "...##########...",
        ".###oOOOOOOo###.",
        "#o.#oOYOOOOo#.o#",
        "#o.#oOYOOOOo#.o#",
        "#o.#oOYOOOOo#.o#",
        ".#o#oOYOOOOo#o#.",
        "..###oOOOOo###..",
        "....#oOOOOo#....",
        ".....#oOOo#.....",
        "......#oO#......",
        "......#oO#......",
        ".....#oOOo#.....",
        "...##cCCCCc##...",
        "...#cCCCCCCc#...",
        "...##########...",
    ]
    pal = {"#": N, "o": O1, "O": O3, "Y": O4, "c": C1, "C": C3}
    sprite("icono/trofeo", mapa(filas, pal))


def icono_rastreo():
    """Huella: rastro de la presa."""
    filas = [
        "................",
        "....##....##....",
        "...#cC#..#cC#...",
        "...#CC#..#CC#...",
        ".##.##....##.##.",
        "#cC#........#cC#",
        "#CC#..####..#CC#",
        ".##..#cCCc#..##.",
        "....#cCLLCc#....",
        "...#cCLCCCCc#...",
        "..#cCCCCCCCCc#..",
        "..#cCCCCCCCCc#..",
        "..#cCCC##CCCc#..",
        "..#ccC#..#Ccc#..",
        "...###....###...",
        "................",
    ]
    pal = {"#": N, "c": C1, "C": C2, "L": C3}
    sprite("icono/rastreo", mapa(filas, pal))


# ---------------------------------------------------------------- emblemas de equipo

ESCUDO = [
    "................",
    ".##############.",
    ".#LLLLLLLLLLLL#.",
    ".#LSSSSSSSSSSD#.",
    ".#LSSSSSSSSSSD#.",
    ".#LSSSSSSSSSSD#.",
    ".#LSSSSSSSSSSD#.",
    ".#LSSSSSSSSSSD#.",
    ".#LSSSSSSSSSSD#.",
    ".#LSSSSSSSSSSD#.",
    "..#LSSSSSSSSD#..",
    "..#LSSSSSSSSD#..",
    "...#LSSSSSSD#...",
    "....#DSSSSD#....",
    ".....##DD##.....",
    ".......##.......",
]

# Figura de cada equipo (8×8): además del color, la forma distingue a los equipos.
FIGURAS = {
    "rojo": [  # llama
        "...X....",
        "..XX....",
        "..XXX.X.",
        ".XXXXXX.",
        ".XXXXXXX",
        "XXX.XXXX",
        "XXX..XXX",
        ".XXXXXX.",
    ],
    "azul": [  # gota
        "...XX...",
        "...XX...",
        "..XXXX..",
        "..XXXX..",
        ".XXXXXX.",
        ".XXXXXX.",
        ".XXXXXX.",
        "..XXXX..",
    ],
    "verde": [  # hoja
        ".....XXX",
        "...XXXXX",
        "..XXXXXX",
        ".XXXX.XX",
        ".XXX.XXX",
        ".XX.XXX.",
        "X.XXXX..",
        "X.......",
    ],
    "amarillo": [  # sol
        "...XX...",
        ".X....X.",
        "..XXXX..",
        "X.XXXX.X",
        "X.XXXX.X",
        "..XXXX..",
        ".X....X.",
        "...XX...",
    ],
    "morado": [  # luna
        "..XXXX..",
        ".XXX....",
        "XXX.....",
        "XXX.....",
        "XXX.....",
        "XXXX...X",
        ".XXXXXXX",
        "..XXXXX.",
    ],
    "naranja": [  # flecha
        "...XXXXX",
        ".....XXX",
        "....XXXX",
        "...XXX.X",
        "..XXX..X",
        ".XXX....",
        "XXX.....",
        "XX......",
    ],
    "cian": [  # cristal
        "...XX...",
        "..XXXX..",
        ".XX.XXX.",
        "XX.XXXXX",
        "XXXXXXXX",
        ".XXXXXX.",
        "..XXXX..",
        "...XX...",
    ],
    "rosa": [  # flor
        "..XXXX..",
        "..XXXX..",
        "XX.XX.XX",
        "XXX..XXX",
        "XXX..XXX",
        "XX.XX.XX",
        "..XXXX..",
        "..XXXX..",
    ],
}


def emblemas():
    for nombre, n in EQUIPOS.items():
        base = rgb(n)
        pal = {"#": N, "S": base, "L": mezcla(base, (255, 255, 255), 0.45), "D": mezcla(base, (0, 0, 0), 0.40)}
        img = mapa(ESCUDO, pal)
        figura = FIGURAS[nombre]
        # Figura clara sobre los colores oscuros y oscura sobre los claros (amarillo, cian…): que siempre contraste.
        luz = (0.299 * base[0] + 0.587 * base[1] + 0.114 * base[2]) / 255
        if luz < 0.55:
            tinta, relieve = (0xFF, 0xFB, 0xEE, 255), mezcla(base, (0, 0, 0), 0.55)
        else:
            tinta, relieve = mezcla(base, (0x14, 0x0D, 0x08), 0.78), mezcla(base, (255, 255, 255), 0.6)
        ox, oy = 4, 3
        # relieve de la figura, un píxel abajo a la derecha
        for y, f in enumerate(figura):
            for x, ch in enumerate(f):
                if ch == "X" and img.getpixel((ox + x + 1, oy + y + 1)) == base:
                    img.putpixel((ox + x + 1, oy + y + 1), relieve)
        for y, f in enumerate(figura):
            for x, ch in enumerate(f):
                if ch == "X":
                    img.putpixel((ox + x, oy + y), tinta)
        sprite(f"equipo/{nombre}", img)


# ---------------------------------------------------------------- icono del mod

def icono_mod():
    """128×128: cartón de 5×5 con marco de madera y cobre; las casillas doradas forman la V de Venatio."""
    lado = 32
    img = marco_madera(lado, 4, F2, veta=False)
    u = lado - 1
    for vx, vy, x, y in ((1, 1, 0, 0), (-1, 1, u, 0), (1, -1, 0, u), (-1, -1, u, u)):
        esquina_cobre(img, x, y, 6, vx, vy)
    v = {(0, 0), (0, 4), (1, 0), (1, 4), (2, 1), (2, 3), (3, 1), (3, 3), (4, 2)}
    for f in range(5):
        for c in range(5):
            x, y = 4 + c * 5, 4 + f * 5
            if (f, c) in v:
                rect(img, x, y, x + 3, y + 3, O3)
                img.putpixel((x, y), O4)
                img.putpixel((x + 1, y), O4)
                img.putpixel((x, y + 1), O4)
                for i in range(4):
                    img.putpixel((x + i, y + 3), O2)
                    img.putpixel((x + 3, y + i), O2)
                img.putpixel((x + 3, y + 3), O1)
            else:
                rect(img, x, y, x + 3, y + 3, H2)
                for i in range(4):
                    img.putpixel((x + i, y), H1)
                    img.putpixel((x, y + i), H1)
                img.putpixel((x + 3, y + 3), H3)
    # las líneas entre casillas, en fieltro sombra
    for k in range(1, 5):
        for i in range(4, 28):
            img.putpixel((3 + k * 5, i), F1)
            img.putpixel((i, 3 + k * 5), F1)
    return img.resize((128, 128), Image.NEAREST)


# ---------------------------------------------------------------- salida

def hoja_de_contacto(ruta, icono):
    """Todos los sprites ampliados ×6 sobre dos fondos (oscuro y claro) para revisar contraste."""
    z = 6
    celdas = [(n, i) for n, (i, _) in sorted(SALIDA.items())]
    ancho = 40 * z + 8
    cols = 6
    filas = (len(celdas) + cols - 1) // cols
    hoja = Image.new("RGBA", (cols * ancho, filas * ancho + 140), (0x30, 0x30, 0x34, 255))
    for k, (nombre, img) in enumerate(celdas):
        x, y = (k % cols) * ancho + 4, (k // cols) * ancho + 4
        fondo = (0x70, 0x90, 0xB0, 255) if (k // cols) % 2 else (0x38, 0x48, 0x38, 255)
        hoja.paste(Image.new("RGBA", (ancho - 8, ancho - 8), fondo), (x, y))
        grande = img.resize((img.width * z, img.height * z), Image.NEAREST)
        hoja.alpha_composite(grande, (x + 4, y + 4))
    hoja.alpha_composite(icono, (4, filas * ancho + 6))
    hoja.save(ruta)


def bloque_suerte():
    """Bloque de la suerte: oro vivo para verlo de lejos, marco de cobre y un trébol de madera oscura. Va al atlas de bloques."""
    img = lienzo(16, 16, O3)
    for y in range(16):
        for x in range(16):
            if (x * 3 + y * 7) % 11 == 0:
                img.putpixel((x, y), O4)
            elif (x * 5 + y * 2) % 13 == 0:
                img.putpixel((x, y), O2)
    marco(img, 0, 0, 15, 15, N)
    marco(img, 1, 1, 14, 14, C2)
    for x, y in ((1, 1), (14, 1), (1, 14), (14, 14)):
        img.putpixel((x, y), C4)
    for x, y in ((2, 1), (1, 2), (13, 1), (14, 2), (1, 13), (2, 14), (14, 13), (13, 14)):
        img.putpixel((x, y), C3)
    trebol = mapa([
        "...oo..oo...",
        "..oOOooOOo..",
        "..oOBOOBOo..",
        "...oOOOOo...",
        "..oOOooOOo..",
        "..oOBooBOo..",
        "...oo.so....",
        "......ss....",
        ".......ss...",
    ], {".": T, "o": N, "O": M2, "B": M4, "s": M1}, 12)
    img.alpha_composite(trebol, (2, 3))
    return img


def main():
    marco_carton()
    marco_pantalla()
    placa()
    fila()
    casilla_vacia()
    casilla_mia()
    casilla_rival()
    casilla_bloqueada()
    candado()
    palomita()
    barra()
    banner()
    icono_bingo()
    icono_tienda()
    icono_guia()
    icono_marca()
    icono_minimapa()
    icono_reloj()
    icono_trofeo()
    icono_rastreo()
    emblemas()
    casilla_tapada()
    interrogacion()
    estrella_secreta()
    sello_veto()
    icono_veto()

    for nombre, (img, meta) in SALIDA.items():
        ruta = SPRITES / f"{nombre}.png"
        ruta.parent.mkdir(parents=True, exist_ok=True)
        img.save(ruta)
        ruta_meta = ruta.with_name(ruta.name + ".mcmeta")
        if meta is not None:
            ruta_meta.write_text(json.dumps(meta, indent=2) + "\n")
        elif ruta_meta.exists():
            ruta_meta.unlink()
    ruta_bloque = ASSETS / "textures/block/bloque_suerte.png"
    ruta_bloque.parent.mkdir(parents=True, exist_ok=True)
    bloque_suerte().save(ruta_bloque)
    icono = icono_mod()
    icono.save(ASSETS / "icon.png")
    print(f"{len(SALIDA)} sprites en {SPRITES.relative_to(RAIZ)} e icon.png")

    if "--hoja" in sys.argv:
        ruta = sys.argv[sys.argv.index("--hoja") + 1]
        hoja_de_contacto(ruta, icono)
        print(f"hoja de contacto: {ruta}")


if __name__ == "__main__":
    main()
