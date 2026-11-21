#!/usr/bin/env python3
"""Sintetiza los sonidos de Venatio (todo generado por código, sin muestras ajenas).

Uso:  python3 tools/arte/sonidos.py [--wav carpeta]
Requiere ffmpeg (encoder vorbis). Escribe los .ogg en src/main/resources/assets/venatio/sounds/
y el sounds.json. Con --wav deja además los .wav sin comprimir en esa carpeta, para escucharlos.

Timbres: marimba de madera (casillas), cuerno de caza (fanfarrias), campanillas (avisos) y un bloque
de madera (cuenta atrás). Todo queda a la misma sonoridad: mismo RMS de la parte audible y pico limitado;
las diferencias de volumen entre eventos se fijan en sounds.json.
"""
import json
import math
import random
import struct
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[2]
ASSETS = RAIZ / "src/main/resources/assets/venatio"
FS = 44100
RMS_OBJETIVO = 10 ** (-16 / 20)   # RMS de la parte audible
PICO_MAXIMO = 10 ** (-1.5 / 20)

NOTAS = {"C": 0, "C#": 1, "D": 2, "D#": 3, "E": 4, "F": 5, "F#": 6, "G": 7, "G#": 8, "A": 9, "A#": 10, "B": 11}


def hz(nota):
    """'A4' → 440.0"""
    nombre, octava = nota[:-1], int(nota[-1])
    return 440.0 * 2 ** ((NOTAS[nombre] - 9) / 12 + (octava - 4))


def silencio(segundos):
    return [0.0] * int(FS * segundos)


def mezclar(destino, senal, inicio=0.0, ganancia=1.0):
    k = int(inicio * FS)
    if len(destino) < k + len(senal):
        destino.extend([0.0] * (k + len(senal) - len(destino)))
    for i, v in enumerate(senal):
        destino[k + i] += v * ganancia
    return destino


def desvanecer(senal, entrada=0.002, salida=0.02):
    n = len(senal)
    a, b = int(entrada * FS), int(salida * FS)
    for i in range(min(a, n)):
        senal[i] *= i / a
    for i in range(min(b, n)):
        senal[n - 1 - i] *= i / b
    return senal


# ---------------------------------------------------------------- instrumentos

def marimba(f, dur=0.45, brillo=1.0):
    """Lámina de madera: fundamental que decae lento y parciales inarmónicos (×4 y ×10) que se apagan enseguida."""
    n = int(dur * FS)
    out = []
    for i in range(n):
        t = i / FS
        v = math.sin(2 * math.pi * f * t) * math.exp(-t * 9)
        v += 0.35 * brillo * math.sin(2 * math.pi * f * 3.98 * t) * math.exp(-t * 28)
        v += 0.12 * brillo * math.sin(2 * math.pi * f * 10.1 * t) * math.exp(-t * 60)
        out.append(v)
    return desvanecer(out, 0.001, 0.03)


def campanilla(f, dur=0.7, caida=6.0):
    """Campanilla suave: parciales ligeramente inarmónicos y un batido lento."""
    n = int(dur * FS)
    out = []
    for i in range(n):
        t = i / FS
        v = math.sin(2 * math.pi * f * t) * math.exp(-t * caida)
        v += 0.45 * math.sin(2 * math.pi * f * 2.005 * t) * math.exp(-t * caida * 1.6)
        v += 0.22 * math.sin(2 * math.pi * f * 3.01 * t) * math.exp(-t * caida * 2.4)
        v += 0.10 * math.sin(2 * math.pi * f * 5.43 * t) * math.exp(-t * caida * 4)
        out.append(v)
    return desvanecer(out, 0.003, 0.05)


def cuerno(f, dur, ataque=0.035, brillo=1.0, vibrato=0.004):
    """Cuerno de caza: serie armónica que se abre con el ataque (más brillo cuanto más fuerte) y un vibrato leve."""
    n = int(dur * FS)
    out = []
    fase = 0.0
    for i in range(n):
        t = i / FS
        env = min(1.0, t / ataque)
        suelta = min(1.0, (dur - t) / 0.07)
        env *= max(0.0, suelta)
        fi = f * (1 + vibrato * math.sin(2 * math.pi * 5.2 * t) * min(1.0, t / 0.25))
        fase += 2 * math.pi * fi / FS
        v = 0.0
        for h in range(1, 9):
            # los armónicos altos entran un poco después y con menos fuerza
            peso = (1.0 / h ** 1.25) * min(1.0, (t / (ataque * (0.6 + 0.35 * h)))) * (brillo if h > 2 else 1.0)
            v += peso * math.sin(h * fase)
        out.append(v * env)
    return out


def ruido(dur, grave=0.0, agudo=1.0, semilla=1):
    """Ruido blanco pasado por un pasa-bajos y un pasa-altos de un polo. grave/agudo entre 0 y 1."""
    rnd = random.Random(semilla)
    n = int(dur * FS)
    out = []
    lp = 0.0
    hp = 0.0
    previo = 0.0
    for _ in range(n):
        x = rnd.uniform(-1, 1)
        lp += agudo * (x - lp)
        hp = (1 - grave) * (hp + lp - previo)
        previo = lp
        out.append(hp if grave > 0 else lp)
    return out


def bloque(f=950, dur=0.07):
    """Bloque de madera: golpe seco con el tono cayendo un poco."""
    n = int(dur * FS)
    out = []
    fase = 0.0
    clic = ruido(dur, grave=0.5, agudo=0.9, semilla=7)
    for i in range(n):
        t = i / FS
        fi = f * (1 + 0.5 * math.exp(-t * 90))
        fase += 2 * math.pi * fi / FS
        v = math.sin(fase) * math.exp(-t * 55) + 0.5 * math.sin(2.7 * fase) * math.exp(-t * 110)
        v += 0.5 * clic[i] * math.exp(-t * 300)
        out.append(v)
    return desvanecer(out, 0.0005, 0.01)


def eco(senal, retardo=0.085, realimentacion=0.28, mezcla=0.22, cola=0.35):
    """Un poco de sala: una línea de retardo con realimentación, mezclada bajito."""
    k = int(retardo * FS)
    out = senal + [0.0] * int(cola * FS)
    linea = [0.0] * len(out)
    for i in range(len(out)):
        entrada = out[i] + (linea[i - k] * realimentacion if i >= k else 0.0)
        linea[i] = entrada
    return desvanecer([out[i] + mezcla * (linea[i - k] if i >= k else 0.0) for i in range(len(out))], 0.0, 0.08)


# ---------------------------------------------------------------- sonidos

def casilla(raiz):
    """Casilla tuya: dos láminas de marimba, cuarta ascendente, con un brillo de campanilla al final."""
    f = hz(raiz)
    s = []
    mezclar(s, marimba(f, 0.30), 0.0)
    mezclar(s, marimba(f * 4 / 3, 0.42), 0.075)
    mezclar(s, campanilla(f * 8 / 3, 0.35, 11), 0.08, 0.16)
    return s


def casilla_rival(raiz):
    """Casilla de un rival: una sola lámina grave y apagada."""
    f = hz(raiz)
    s = []
    mezclar(s, marimba(f, 0.22, brillo=0.35), 0.0)
    mezclar(s, marimba(f * 0.5, 0.2, brillo=0.1), 0.0, 0.5)
    return s


def bingo():
    """Línea o bingo: llamada corta de cuerno, arpegio ascendente y acorde."""
    s = []
    t = 0.0
    for nota in ("G4", "C5", "E5"):
        mezclar(s, cuerno(hz(nota), 0.13, brillo=0.9), t, 0.8)
        t += 0.11
    for nota, g in (("C5", 0.55), ("E5", 0.5), ("G5", 0.8)):
        mezclar(s, cuerno(hz(nota), 0.55, brillo=1.0), t, g)
    mezclar(s, campanilla(hz("C7"), 0.5, 8), t, 0.12)
    return eco(s)


def victoria():
    """Victoria: fanfarria de cuerno a dos voces que resuelve en un acorde mayor con campanas."""
    s = []
    frase = [("C5", 0.00, 0.14), ("C5", 0.16, 0.10), ("C5", 0.28, 0.10), ("E5", 0.40, 0.34),
             ("D5", 0.78, 0.14), ("E5", 0.94, 0.14), ("G5", 1.10, 0.95)]
    for nota, t, d in frase:
        mezclar(s, cuerno(hz(nota), d, brillo=1.0), t, 0.8)
    segunda = [("G4", 0.00, 0.14), ("G4", 0.16, 0.10), ("G4", 0.28, 0.10), ("C5", 0.40, 0.34),
               ("B4", 0.78, 0.14), ("C5", 0.94, 0.14), ("E5", 1.10, 0.95), ("C5", 1.10, 0.95)]
    for nota, t, d in segunda:
        mezclar(s, cuerno(hz(nota), d, brillo=0.7), t, 0.45)
    mezclar(s, cuerno(hz("C3"), 0.95, brillo=0.5), 1.10, 0.5)
    for i, nota in enumerate(("C6", "E6", "G6", "C7")):
        mezclar(s, campanilla(hz(nota), 0.9, 5), 1.12 + i * 0.07, 0.13)
    return eco(s, retardo=0.11, realimentacion=0.34, mezcla=0.26, cola=0.6)


def derrota():
    """Derrota: tres notas de cuerno que bajan, suaves y sin brillo, hacia un acorde menor."""
    s = []
    frase = [("E4", 0.00, 0.30), ("D4", 0.32, 0.30), ("C4", 0.64, 0.34)]
    for nota, t, d in frase:
        mezclar(s, cuerno(hz(nota), d, ataque=0.06, brillo=0.35), t, 0.8)
    for nota, g in (("A3", 0.8), ("C4", 0.5), ("E4", 0.4)):
        mezclar(s, cuerno(hz(nota), 0.75, ataque=0.09, brillo=0.3), 1.0, g)
    return eco(s, retardo=0.12, realimentacion=0.3, mezcla=0.24, cola=0.5)


def fin():
    """Partida terminada sin ganador (empate o cancelada): dos campanas en quinta, neutras."""
    s = []
    mezclar(s, campanilla(hz("G4"), 1.1, 3.5), 0.0, 0.8)
    mezclar(s, campanilla(hz("D5"), 1.0, 3.8), 0.09, 0.6)
    return eco(s, cola=0.3)


def cuenta_tic():
    """Tic de la cuenta atrás: bloque de madera."""
    return bloque(950, 0.07)


def cuenta_ya():
    """¡Ya!: golpe de cuerno en quinta abierta con una nota de adorno y campana."""
    s = []
    mezclar(s, cuerno(hz("G4"), 0.07, ataque=0.015), 0.0, 0.6)
    for nota, g in (("C5", 0.8), ("G5", 0.7), ("C4", 0.4)):
        mezclar(s, cuerno(hz(nota), 0.5, ataque=0.02, brillo=1.1), 0.06, g)
    mezclar(s, campanilla(hz("C6"), 0.6, 6), 0.06, 0.18)
    mezclar(s, bloque(700, 0.06), 0.055, 0.5)
    return eco(s, cola=0.3)


def compra():
    """Compra de ayuda: dos monedas, tintineo metálico agudo."""
    def moneda(f, dur=0.26):
        n = int(dur * FS)
        out = []
        for i in range(n):
            t = i / FS
            v = math.sin(2 * math.pi * f * t) * math.exp(-t * 16)
            v += 0.6 * math.sin(2 * math.pi * f * 1.48 * t) * math.exp(-t * 20)
            v += 0.3 * math.sin(2 * math.pi * f * 2.76 * t) * math.exp(-t * 34)
            out.append(v)
        return desvanecer(out, 0.0008, 0.03)
    s = []
    mezclar(s, moneda(2350), 0.0, 0.7)
    mezclar(s, moneda(3140), 0.085, 1.0)
    mezclar(s, bloque(1500, 0.03), 0.0, 0.25)
    return s


def marca():
    """Marca puesta: un 'pip' corto, como clavar un banderín."""
    n = int(0.16 * FS)
    out = []
    fase = 0.0
    for i in range(n):
        t = i / FS
        f = 1180 * (1 + 0.35 * math.exp(-t * 60))
        fase += 2 * math.pi * f / FS
        # onda triangular suave
        v = (2 / math.pi) * math.asin(math.sin(fase)) * math.exp(-t * 26)
        v += 0.3 * math.sin(2 * fase) * math.exp(-t * 50)
        out.append(v)
    s = desvanecer(out, 0.001, 0.02)
    mezclar(s, bloque(520, 0.05), 0.0, 0.5)
    return s


def guia_llegada():
    """Llegada a la zona de la guía: tres campanillas que suben."""
    s = []
    for i, nota in enumerate(("C6", "E6", "G6")):
        mezclar(s, campanilla(hz(nota), 0.55, 7.5), i * 0.11, 0.8 + 0.1 * i)
    return eco(s, cola=0.25)


def rastreo_encontrado(raiz):
    """Item encontrado en Rastreo: arpegio rápido de marimba hacia arriba con un destello."""
    f = hz(raiz)
    s = []
    for i, r in enumerate((1, 5 / 4, 3 / 2, 2)):
        mezclar(s, marimba(f * r, 0.3 if i < 3 else 0.45), i * 0.055, 0.8 + 0.07 * i)
    mezclar(s, campanilla(f * 4, 0.4, 9), 0.17, 0.2)
    return s


def carton_abrir():
    """Abrir el cartón: roce corto de papel y un golpecito de madera."""
    n = int(0.16 * FS)
    roce = ruido(0.16, grave=0.35, agudo=0.55, semilla=3)
    for i in range(n):
        t = i / FS
        roce[i] *= math.sin(math.pi * min(1.0, t / 0.16)) ** 2 * (0.5 + 0.5 * t / 0.16)
    s = []
    mezclar(s, roce, 0.0, 0.8)
    golpe = []
    for i in range(int(0.09 * FS)):
        t = i / FS
        golpe.append(math.sin(2 * math.pi * 190 * t * (1 + 0.3 * math.exp(-t * 80))) * math.exp(-t * 45))
    mezclar(s, desvanecer(golpe, 0.001, 0.02), 0.11, 0.9)
    mezclar(s, bloque(620, 0.04), 0.112, 0.25)
    return s


# nombre del evento → (variantes, volumen en sounds.json, subtítulo)
SONIDOS = {
    "casilla": ([lambda: casilla("E5"), lambda: casilla("G5"), lambda: casilla("D5")], 0.9, "Casilla conseguida"),
    "casilla_rival": ([lambda: casilla_rival("G4"), lambda: casilla_rival("A4")], 0.45, "Casilla de un rival"),
    "bingo": ([bingo], 0.9, "¡Bingo!"),
    "victoria": ([victoria], 1.0, "Fanfarria de victoria"),
    "derrota": ([derrota], 0.8, "Fin de la partida"),
    "fin": ([fin], 0.7, "Partida terminada"),
    "cuenta_tic": ([cuenta_tic], 0.55, "Cuenta atrás"),
    "cuenta_ya": ([cuenta_ya], 0.9, "¡A cazar!"),
    "compra": ([compra], 0.6, "Ayuda comprada"),
    "marca": ([marca], 0.6, "Marca de equipo"),
    "guia_llegada": ([guia_llegada], 0.7, "Llegaste a la zona"),
    "rastreo_encontrado": ([lambda: rastreo_encontrado("C5"), lambda: rastreo_encontrado("D5"),
                            lambda: rastreo_encontrado("A#4")], 0.9, "Item encontrado"),
    "carton_abrir": ([carton_abrir], 0.5, "Cartón abierto"),
}


# ---------------------------------------------------------------- salida

def normalizar(senal):
    """Mismo RMS de la parte audible (por encima del 2 % del pico) para todos, con el pico limitado."""
    pico = max(abs(v) for v in senal) or 1.0
    audibles = [v for v in senal if abs(v) > 0.02 * pico]
    rms = math.sqrt(sum(v * v for v in audibles) / len(audibles))
    g = RMS_OBJETIVO / rms
    if pico * g > PICO_MAXIMO:
        g = PICO_MAXIMO / pico
    return [v * g for v in senal]


def medir(senal):
    pico = max(abs(v) for v in senal)
    audibles = [v for v in senal if abs(v) > 0.02 * pico]
    rms = math.sqrt(sum(v * v for v in audibles) / len(audibles))
    return 20 * math.log10(pico), 20 * math.log10(rms)


def escribir_wav(ruta, senal):
    with wave.open(str(ruta), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(FS)
        w.writeframes(b"".join(struct.pack("<h", max(-32767, min(32767, round(v * 32767)))) for v in senal))


def a_ogg(wav, ogg):
    # El encoder vorbis propio de ffmpeg solo admite estéreo y es "experimental" (-strict -2).
    # pan en vez de -ac 2: duplica el canal sin bajarle 3 dB.
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(wav), "-af", "pan=stereo|c0=c0|c1=c0",
                    "-c:a", "vorbis", "-strict", "-2",
                    "-q:a", "5", "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact", str(ogg)], check=True)


def main():
    carpeta_wav = Path(sys.argv[sys.argv.index("--wav") + 1]) if "--wav" in sys.argv else None
    if carpeta_wav:
        carpeta_wav.mkdir(parents=True, exist_ok=True)
    destino = ASSETS / "sounds"
    destino.mkdir(parents=True, exist_ok=True)
    for viejo in destino.glob("*.ogg"):
        viejo.unlink()
    indice = {}
    with tempfile.TemporaryDirectory() as tmp:
        for nombre, (variantes, volumen, subtitulo) in SONIDOS.items():
            entradas = []
            for k, crear in enumerate(variantes):
                archivo = nombre if len(variantes) == 1 else f"{nombre}_{k + 1}"
                senal = normalizar(desvanecer(crear(), 0.0, 0.01))
                pico, rms = medir(senal)
                wav = (carpeta_wav or Path(tmp)) / f"{archivo}.wav"
                escribir_wav(wav, senal)
                a_ogg(wav, destino / f"{archivo}.ogg")
                print(f"{archivo:24s} {len(senal) / FS:5.2f} s   pico {pico:6.1f} dBFS   RMS {rms:6.1f} dBFS")
                entrada = {"name": f"venatio:{archivo}"}
                if volumen != 1.0:
                    entrada["volume"] = volumen
                entradas.append(entrada)
            indice[nombre] = {"subtitle": f"subtitles.venatio.{nombre}", "sounds": entradas}
    (ASSETS / "sounds.json").write_text(json.dumps(indice, indent=2, ensure_ascii=False) + "\n")
    print(f"{len(indice)} eventos en sounds.json")
    print("Subtítulos para lang/es_mx.json:")
    for nombre, (_, _, subtitulo) in SONIDOS.items():
        print(f'  "subtitles.venatio.{nombre}": "{subtitulo}",')


if __name__ == "__main__":
    main()
