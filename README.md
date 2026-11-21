# Venatio

Bingo de Minecraft (y modo Rastreo tipo ItemFinder) como mod de Fabric 26.3 con parte de servidor y de cliente. Los jugadores lo instalan con el instalador de Venatio (Mac, Windows, Linux). Todo el texto va en español.

El diseño completo, las decisiones y las fases están en [`docs/DISEÑO.md`](docs/DISEÑO.md).

## Requisitos

- JDK 25 (instalado en `~/Library/Java/JavaVirtualMachines/jdk-25.0.5.1+2`). Gradle lo elige solo por `gradle/gradle-daemon-jvm.properties`; el Java por defecto del sistema (21, vía sdkman) no se tocó.

## Comandos

```bash
./gradlew build        # jar en build/libs/venatio-<versión>.jar (incluye Fantasy)
./gradlew runServer    # server de prueba en run/server
./gradlew runClient    # cliente de prueba con el mod; entrar a localhost
```

En el server de prueba, `/venatio` responde con la versión y el tamaño del pool.

## Estructura

```
docs/DISEÑO.md                          diseño y plan
src/main/java/dev/venatio/              servidor y código común
src/client/java/dev/venatio/cliente/    HUD, pantallas, teclas (B cartón, H ayudas, N marca, M minimapa)
src/main/resources/assets/venatio/lang/ textos (es_mx; el mismo español en en_us y es_es)
src/main/resources/data/venatio/pools/  pools de items (clasico.json = MCBingo)
tools/extraer_pool_mcbingo.py           regenera clasico.json desde el datapack de MCBingo
tools/arte/texturas.py                  dibuja los sprites de la interfaz y el icono del mod (necesita Pillow)
tools/arte/sonidos.py                   sintetiza los sonidos y escribe sounds.json (necesita ffmpeg)
```

## Créditos

- Pool de items y reglas de cartón basados en **MC Bingo 2.0** de SirChi (licencia MIT).
- Modo Rastreo inspirado en el mapa **ItemFinder**; la mecánica se reimplementa, no se copia su código.
