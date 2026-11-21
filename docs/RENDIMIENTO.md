# Rendimiento del servidor

Qué mods de servidor, qué `server.properties` y qué cambios en Venatio hacen que el server de exaroton (6 GB, 3 núcleos
compartidos, 4–6 jugadores) vaya fluido con una distancia de visión alta. Medido el 20–21 de noviembre de 2026.

## Resumen

- **El cuello de botella es generar chunks, no el tick.** Con 5 jugadores explorando, el hilo principal usa 10–28 % de
  un núcleo y la MSPT media queda en 6–16 ms con cualquier conjunto de mods. Lo que se nota es el terreno que tarda en
  aparecer: volando con élitro, con Minecraft solo, **falta ~60 % de la vista** de los jugadores.
- **Moonrise con 2 hilos de trabajo** es lo que de verdad cambia eso: genera 124–157 chunks/s contra 45–68 de vanilla o
  Lithium+ScalableLux, y la vista sin cargar baja a 8–29 %. Por defecto, con 3 núcleos, Moonrise usa **1** hilo; hay
  que subirlo a 2.
- **C2ME no sirve con Fantasy**: los mundos que Fantasy borra al terminar cada partida no se liberan (fuga de ~500 MB
  por partida; con 6 GB el server se queda sin memoria a las pocas partidas) y da tirones de hasta 2.9 s. Seguramente
  por eso se quitó en el server viejo.
- **Los tirones (ticks de más de 50 ms) son casi todos pausas del recolector de basura.** Con 6 GB y G1 por defecto las
  pausas llegan a 0.3–1 s; con `-XX:MaxGCPauseMillis=50` los ticks lentos bajan a una cuarta o sexta parte.
- **El código de Venatio no pesa**: 0.01–0.04 ms por tick en promedio (detección, localizador, marcas, estado). No hubo
  nada que optimizar ahí sin cambiar el comportamiento.
- **Cambios en Venatio**: los mundos de la partida ya no entran al autoguardado de cada 5 min (tirón de 30–50 ms que no
  protegía nada) y el mundo de la siguiente partida se **precalienta en el lobby** (la partida empieza en 0.1 s con la
  vista completa).
- **Recomendación**: Moonrise + Lithium + FerriteCore + Krypton, Moonrise con `worker-threads: 2`, `view-distance=12`,
  `simulation-distance=8` y, si exaroton deja, `-XX:MaxGCPauseMillis=50`. Detalle en
  [Recomendación para exaroton](#recomendación-para-exaroton).

## Cómo se mide

`PruebaRendimiento` (`src/gametest/java/dev/venatio/prueba/`) levanta un **servidor dedicado** dentro de la prueba con
cliente (`TestWorldBuilder.createServer`), conecta a Player0 y agrega 5 bots de Carpet. Arma 3 equipos de 2 (los equipos
aparecen a 400 bloques entre sí) e inicia una partida de Línea con logros y cantidades en el cartón (para que la detección
haga todo su trabajo), siempre con la **misma semilla**. Al empezar el juego:

- Player0 (cliente real) se queda en su punto de aparición con vista corta: el cliente comparte la JVM y avanza tick a
  tick a la par del servidor, así que un cliente cargado frenaría al servidor y ensuciaría la medición.
- Cada bot sale de su punto de aparición en una dirección distinta a velocidad fija (6 b/s a pie, 25 b/s con élitro, a
  30 bloques del suelo). Como el cliente de verdad, no avanza hacia un chunk que todavía no existe. Los bots piden la
  distancia de visión completa y confirman los lotes de chunks como un cliente real (16 por tick), así el servidor arma
  y les manda los chunks.
- Cada 10 s se registra MSPT media, p95 y máximo, chunks cargados, **chunks faltantes** (los que están dentro de la
  distancia de visión de algún jugador y todavía no existen: lo que el jugador ve como hueco), chunks generados, memoria
  y CPU (del hilo del servidor y del proceso). Al final, una línea de resumen y una fila en
  `build/run/clientGameTest/rendimiento/resultados.csv`.
- A la mitad se llama al mismo guardado que el autoguardado de cada 5 min y se mide cuánto tarda.

MSPT es lo que dura `tickServer` completo (del primer oyente de `START_SERVER_TICK` al último de `END_SERVER_TICK`):
mundos, red, autoguardado y los oyentes de Venatio. "Venatio" es lo que duran los oyentes de Venatio en ese tick.

Para correrla (todo es opcional; sin nada es una corrida corta de 30 s que forma parte de la lista de pruebas):

```sh
ORG_GRADLE_PROJECT_mods=b ORG_GRADLE_PROJECT_jvmExtra=-DMoonrise.WorkerThreadCount=2 \
ORG_GRADLE_PROJECT_nucleos=3 ORG_GRADLE_PROJECT_memoria=6G \
ORG_GRADLE_PROJECT_rendVistas=10,12,16 ORG_GRADLE_PROJECT_rendVelocidades=6,25 \
ORG_GRADLE_PROJECT_rendSegundos=90 ORG_GRADLE_PROJECT_rendCalentar=30 ORG_GRADLE_PROJECT_rendPrecalentar=false \
tools/prueba.sh PruebaRendimiento
```

| Propiedad de Gradle | Qué hace |
|---|---|
| `mods` | Conjunto de mods de servidor (solo en el runtime de las pruebas): `base`, `a`, `a_sin_c2me`, `b`, o mods sueltos sumados con `+` (`a+spark`, `b+vmp`). Ver `build.gradle`. |
| `nucleos` | `-XX:ActiveProcessorCount`: cuántos procesadores ve la JVM (tamaño de los hilos de generación, GC, etc.). |
| `memoria` | `-Xms`/`-Xmx`. |
| `jfr`, `jvmExtra` | Grabar con Java Flight Recorder; banderas de JVM extra separadas por espacios. |
| `configMods` | Carpeta que se copia a `config/` del juego de prueba antes de arrancar (la carpeta de la corrida se borra cada vez). |
| `rendVistas`, `rendSimulacion` | Listas de view-distance y simulation-distance (`vista` = igual a la vista). |
| `rendVelocidades` | Bloques por segundo de los bots. |
| `rendSegundos`, `rendCalentar` | Duración de cada escenario; partida previa sin registrar para calentar la JVM. |
| `rendBots`, `rendEquipos`, `rendSemilla` | 5, 3 y 20261120 por defecto. |
| `rendPregen` | Lista de radios de pregeneración a comparar (`-Dvenatio.pregen`). |
| `rendLobby`, `rendPrecalentar` | Segundos en el lobby antes de cada partida (medidos aparte) y si Precalentado trabaja. |
| `rendSync`, `rendCompresion`, `rendRegion` | `sync-chunk-writes`, `network-compression-threshold` y `region-file-compression` del servidor de prueba. |

### Qué tan parecido es a exaroton

- La Mac es un M4 (4 núcleos de rendimiento y 6 de eficiencia). `-XX:ActiveProcessorCount=3` hace que la JVM y los mods
  dimensionen sus hilos como en una máquina de 3 núcleos (vanilla: 2 hilos de generación; Moonrise: 1 por defecto), pero
  **no limita el CPU de verdad**: el proceso puede usar más de 3 núcleos y cada núcleo del M4 es más rápido que uno
  compartido de exaroton. Por eso se reporta el CPU del proceso: con Moonrise y 2 hilos usa ~2.1 núcleos, dentro de lo
  que hay en exaroton, pero allá cada núcleo rinde menos. Los números absolutos de chunks/s van a ser más bajos en
  exaroton; las diferencias entre conjuntos deberían mantenerse.
- macOS no deja fijar procesos a núcleos, así que no se pudo simular "3 núcleos compartidos" más de cerca.
- El cliente de la prueba comparte la JVM (≈0.3 GB de la memoria reportada) y otros programas de la Mac corrían a la vez
  (a partir de las 23:30 del 20 de nov., un Minecraft abierto aparte): la MSPT varía hasta ±6 ms entre corridas iguales. Las
  conclusiones se apoyan en diferencias grandes (chunks/s, vista sin cargar, memoria), no en 2–3 ms de MSPT.
- C2ME no encuentra su biblioteca nativa de matemáticas en macOS ARM; en Linux x86 (exaroton) generaría algo más rápido.
  No cambia la conclusión: la fuga de memoria es independiente de eso.

## Resultados

Salvo que se diga otra cosa: 5 bots + Player0 en 3 equipos, `simulation-distance=6`, JVM con 3 procesadores visibles
y 6 GB (G1 por defecto), 90 s por escenario después de 30 s de calentamiento, semilla 20261120, sin precalentado.

### Conjuntos de mods

| Conjunto | Mods (versiones de Modrinth para 26.3) |
|---|---|
| base | Fabric API 0.166.2+26.3 (más Carpet para los bots y Fantasy, que van en todas) |
| A | Lithium mc26.3-0.26.4-fabric, FerriteCore 9.0.0-fabric, Krypton 0.3.2, ScalableLux 0.3.0-alpha.0.6+26.3, C2ME 0.4.2-alpha.0.121+26.3 |
| A sin C2ME | A sin C2ME |
| B | Moonrise 1.2.2+35bacce, Lithium, FerriteCore, Krypton (Moonrise declara incompatibles C2ME, Starlight y **ScalableLux**: trae su propio motor de luz) |

Todos arrancan y juegan partidas completas con Fantasy (crear, jugar, borrar y volver a crear mundos), pero A no
libera los mundos borrados (ver C2ME abajo). Moonrise reemplaza el gestor de entidades de vanilla y con eso el evento
`ALLOW_LOAD` de Fabric deja de llamarse: Venatio lo usaba para que los tableros del lobby recuperaran su texto al
recargarse, y con Moonrise "Última partida" volvía con el texto viejo (lo detectó `PruebaStats`). Se corrigió en
`TablerosLobby` escuchando también `ENTITY_LOAD`, que Moonrise sí dispara. En el log de arranque con B aparecen dos
avisos esperables: Moonrise apaga dos opciones de FerriteCore y una sobrescritura de `CompoundTag.copy` queda en la de
Lithium.

#### Explorando con élitro (25 b/s)

Lo que separa a los conjuntos. Corridas del 21 de nov. (misma prueba, mismo rato, Mac con otra carga encima):

| Conjunto | Vista | Chunks generados/s | Vista sin cargar (media) | MSPT media | p95 | máx | Ticks >50 ms | CPU del proceso |
|---|---|---|---|---|---|---|---|---|
| base | 10 | 46 | ≈59 % | 11.7 | 21.7 | 223 | 15 de 1772 | 1.6 núcleos |
| base | 12 | 45 | ≈62 % | 13.6 | 27.2 | 430 | 32 de 1760 | 1.6 |
| base | 16 | 46 | ≈63 % | 12.5 | 19.8 | 313 | 26 de 1772 | 1.5 |
| A sin C2ME | 10 | 68 | 49 % | 12.9 | 23.6 | 209 | 14 de 1585 | 1.8 |
| A sin C2ME | 12 | 60 | 55 % | 13.2 | 23.0 | 288 | 20 de 1778 | 1.6 |
| A sin C2ME | 16 | 59 | 66 % | 13.6 | 26.4 | 347 | 25 de 1776 | 1.6 |
| B (Moonrise, 1 hilo) | 10 | 62 | 47 % | 7.4 | 18.0 | 103 | 6 de 1771 | 1.3 |
| B (Moonrise, 1 hilo) | 12 | 67 | 51 % | 7.2 | 15.5 | 129 | 5 de 1767 | 1.3 |
| B (Moonrise, 1 hilo) | 16 | 69 | 58 % | 7.3 | 16.4 | 339 | 6 de 1769 | 1.3 |
| **B (Moonrise, 2 hilos)** | 10 | **124** | ≈23 % | 15.2 | 26.9 | 509 | 18 de 1556 | 2.1 |
| **B (Moonrise, 2 hilos)** | 12 | **133** | ≈26 % | 15.4 | 30.1 | 545 | 20 de 1550 | 2.0 |
| **B (Moonrise, 2 hilos)** | 16 | **156** | ≈29 % | 14.2 | 26.1 | 266 | 17 de 1548 | 2.1 |

"Vista sin cargar": de los chunks que caen dentro de la distancia de visión de algún jugador, cuántos todavía no
existen, promediado cada segundo (≈ = calculado sobre 6 × (2·vista+1)², sin solapes; las corridas que lo midieron
directo quedan 2–6 puntos por encima de esa estimación). Los bots casi siempre pudieron seguir a 24–25 b/s porque el
chunk de enfrente se genera primero; lo que no alcanza a aparecer es el resto de la vista. Con Moonrise de 1 hilo los
bots sí se frenaron (19–21 b/s de los 25 pedidos).

- Vanilla no usa los 3 núcleos: con 2 hilos de generación el proceso se queda en ~1.5 núcleos y unos 45–60 chunks/s.
  Lithium y ScalableLux (A sin C2ME) suben a ~60–70 chunks/s.
- Moonrise con su configuración automática usa **un solo** hilo de trabajo con 3 procesadores; con 2 hilos duplica la
  generación (124–156 chunks/s) usando ~2.1 núcleos del proceso. El costo: la MSPT media sube de ~7 a ~12–15 ms (el
  hilo principal recibe el doble de chunks terminados y hay más basura que recoger; con la bandera de GC de abajo queda
  en ~9–12 ms). En exaroton hay que vigilarlo (ver recomendación).
- La MSPT casi no depende de la distancia de visión: lo que crece con la vista es la cantidad de chunks por generar.

#### A pie (6 b/s)

Con cualquier conjunto y cualquier vista la generación alcanza: la vista queda completa salvo los primeros segundos
(lo que falta al empezar la partida). Corridas del 20 de nov., Mac sin otra carga (en estas los bots todavía no
esperaban al chunk de enfrente; a pie la generación alcanza, así que no cambia):

| Conjunto | Vista | MSPT media | p95 | máx | Ticks >50 ms | Faltan al empezar | Chunks/s |
|---|---|---|---|---|---|---|---|
| base | 10 | 9.2 | 14.5 | 51 | 1 de 1805 | 515 | 31 |
| base | 12 | 8.2 | 12.8 | 47 | 0 de 1606 | 845 | 39 |
| A sin C2ME | 10 | 8.7 | 13.3 | 117 | 2 de 1602 | 178 | 34 |
| A sin C2ME | 12 | 9.1 | 14.5 | 134 | 4 de 1790 | 0 | 30 |
| A sin C2ME | 16 | 8.9 | 14.7 | 138 | 3 de 1797 | 1258 | 51 |
| B (1 hilo) | 10 | 6.7 | 11.3 | 42 | 0 de 1799 | 100 | 29 |
| B (1 hilo) | 12 | 7.4 | 12.6 | 167 | 2 de 1789 | 450 | 36 |
| B (1 hilo) | 16 | 7.6 | 13.7 | 207 | 2 de 1798 | 1128 | 49 |

Con Moonrise de 2 hilos (21 de nov.): MSPT media 11–13 ms y **0 faltantes al empezar** con vista 10 y 12 (635 con 16).
Base con vista 16 a pie ya no alcanza del todo: falta en promedio el 8 % de la vista.

#### C2ME y Fantasy: fuga de memoria

Con C2ME, los mundos que Fantasy borra al terminar cada partida se quedan en memoria. Memoria usada después de forzar
un GC con los mundos ya borrados, al terminar cada una de las 6 partidas de la corrida:

| Conjunto | Partida 1 | 2 | 3 | 4 | 5 | 6 |
|---|---|---|---|---|---|---|
| A (con C2ME) | 862 MB | 1315 | 1771 | 2201 | 2740 | 3258 |
| A sin C2ME | 292 | 293 | 285 | 285 | 332 | 286 |
| base | 311 | 315 | 328 | 316 | 289 | 289 |

Un histograma del heap (`jcmd GC.class_histogram`) a la 7.ª partida con C2ME tenía **22 `RuntimeLevel` vivos** (el
lobby más 3 por cada partida jugada) con 24 mil `LevelChunk`; sin C2ME y con Moonrise había 4 (el lobby y los 3 de la
partida en curso). Fantasy, al borrar un mundo, lo saca del servidor pero no llama a `ServerLevel.close()`; sin C2ME
eso basta para que se libere, con C2ME algo sigue apuntando a esos mundos (no se identificó qué). Son ~450–500 MB por
partida: con 6 GB el server se cae a las pocas partidas.
Además, C2ME dio tirones de 740 ms, 885 ms y 2.9 s volando con élitro, y por defecto (notickvd: 3 cargas simultáneas)
no generó más rápido que vanilla. Probablemente es lo que pasó en el server viejo.

#### Memoria

Memoria usada después de forzar un GC al final de cada escenario, con los mundos de la partida todavía cargados (incluye
≈0.3 GB del cliente de la prueba):

| Conjunto | Vista 10 | Vista 12 | Vista 16 |
|---|---|---|---|
| base | 0.6–0.65 GB | 0.66–0.68 | 0.67–0.74 |
| A sin C2ME | 0.63 | 0.66 | 0.71 |
| B, 1 hilo | 0.83 | 0.89 | 1.05 |
| B, 2 hilos | 0.83–0.92 | 0.88–1.03 | 1.0–1.17 |

Con 6 GB sobra memoria para cualquier vista medida (sin C2ME). Moonrise usa unos 200–400 MB más que vanilla.

#### Recolector de basura

Con G1 por defecto (sin banderas, 6 GB fijos, 3 procesadores) las pausas fueron largas: en las corridas de
pregeneración y precalentado, **mediana de 84–90 ms, 10 % por encima de 300 ms y una de 1 s** (el registro de GC incluye
al cliente de la prueba, que comparte la JVM). La mayoría de los ticks de más de 50 ms de las tablas son pausas de GC:
con otro recolector casi desaparecen (abajo).

Comparación de banderas (Moonrise con 2 hilos, vista 12, 60 s a pie y 60 s con élitro, 21 de nov., mismo rato):

| JVM | A pie: MSPT media / p95 / máx | Ticks >50 ms | Élitro: MSPT media / p95 / máx | Ticks >50 ms | Chunks/s élitro | Vista sin cargar | Pausas de GC |
|---|---|---|---|---|---|---|---|
| G1 por defecto | 14.9 / 40.0 / 475 | 38 de 1151 | 14.7 / 27.3 / 388 | 15 de 1179 | 117 | 19 % | mediana 60 ms, 10 % >281 ms, máx 658 |
| G1 con `-XX:MaxGCPauseMillis=50` | 8.9 / 14.9 / 101 | 6 de 1004 | 11.9 / 19.8 / 77 | 4 de 1198 | 148 | 10 % | mediana 29 ms, 10 % >66 ms, máx 569 |
| ZGC (`-XX:+UseZGC`) | 9.1 / 16.9 / 69 | 3 de 1204 | 12.6 / 22.0 / 49 | **0** de 1003 | 157 | 8 % | concurrentes (sin pausas largas) |
| Banderas de Aikar (G1)¹ | 8.4 / 15.3 / 162 | 2 de 1001 | 12.1 / 20.4 / 129 | 4 de 1190 | 153 | 9 % | mediana 42 ms, 10 % >142 ms, máx 249 |

¹ Las de [docs.papermc.io/paper/aikars-flags](https://docs.papermc.io/paper/aikars-flags) para menos de 12 GB:
`-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions
-XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40
-XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4
-XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5
-XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1`. Con `DisableExplicitGC` la "memoria con
mundos" de esa corrida no es comparable (el GC forzado de la prueba no hace nada).

Bajar la meta de pausa de G1 a 50 ms cambia mucho: pausas más cortas y frecuentes en vez de pocas largas, y los ticks
lentos quedan entre una cuarta y una sexta parte. También sube la generación (menos tiempo con todo detenido). Las
banderas de Aikar logran casi lo mismo por otro camino (generación joven acotada al 30–40 % del heap): pausas un poco más
largas en la mediana pero sin las de medio segundo. ZGC casi no pausa, pero hace su trabajo en paralelo y en la Mac sobraban núcleos para eso;
con 3 núcleos compartidos ese trabajo compite con la generación, así que en exaroton la opción segura es G1 con 50 ms
y ZGC queda como prueba si siguen los tirones (ver recomendación). Con ZGC la memoria usada es mayor (1.7 GB con los
mundos contra 1.0–1.1 GB con G1), sin problema con 6 GB.

### Distancia de simulación

Moonrise con 2 hilos, vista 12, a pie, 60 s por escenario (21 de nov.):

| simulation-distance | MSPT media | p95 | máx | Ticks >50 ms |
|---|---|---|---|---|
| 4 | 6.9 | 12.8 | 47 | 0 de 1002 |
| 6 | 8.2 | 15.1 | 70 | 2 de 1200 |
| 8 | 8.8 | 14.7 | 101 | 2 de 1003 |
| 12 (= vista) | 10.1 | 16.4 | 149 | 1 de 1002 |

Cuesta poco: de 6 a 8 son 0.6 ms y de 6 a 12 son 2 ms (con un mundo recién generado; con más mobs acumulados la
diferencia crece algo). Ojo con lo que cambia en el juego: los mobs solo aparecen en chunks simulados y a menos de
128 bloques (8 chunks) de un jugador. Con **8** la aparición de mobs es igual que en vanilla; con 6 el área donde
aparecen y se mueven se achica a ~96 bloques (la densidad cerca del jugador es la misma, pero hay menos mobs en total).
Por eso la recomendación es **8**: lejos para ver (12), cerca para simular (8), sin tocar la aparición de mobs.

### Pregeneración y precalentado

Hoy Partida genera un radio de 2 chunks alrededor de cada punto de aparición antes de llevar a los jugadores
(PREPARANDO) y después hay 10 s de cuenta atrás con los jugadores congelados en su lugar.

Radio de pregeneración, con Moonrise de 2 hilos, vista 12, 3 equipos:

| Radio | Preparación | Faltan al empezar el juego (de ~1900) |
|---|---|---|
| 2 (actual) | 1.7 s | 61 |
| 6 | 3.3 s | 0 |
| 12 | 8.1 s | 0 |

Sin Moonrise el radio 2 deja 500–900 chunks sin generar al empezar con vista 10–12 (tabla de arriba). Con Moonrise la
cuenta atrás ya alcanza para casi toda la vista, así que **el radio se queda en 2**: subirlo solo alarga la espera.

**Precalentado** (nuevo, `dev.venatio.rendimiento.Precalentado`): mientras todos están en el lobby genera, sin simular,
la vista completa alrededor de los puntos de aparición que tendrían los equipos armados. Con 60 s de lobby antes de cada
partida (Moonrise 2 hilos, vista 12):

| | Preparación | Faltan al empezar | MSPT del lobby mientras genera |
|---|---|---|---|
| Sin precalentado (radio 2) | 1.7 s | 61 | — |
| Con precalentado | **0.1 s** | **0** | 4.5 ms media (crear los 3 mundos cuesta 15–25 ms una vez) |

En la Mac con Moonrise la ganancia es chica (1.6 s y unos cuantos chunks), porque Moonrise ya genera rápido. Vale más
en exaroton, donde cada núcleo rinde menos, y sin Moonrise (500–900 chunks pendientes al empezar). No cambia el juego:
la semilla sigue siendo al azar (se elige antes), el anfitrión puede fijar otra y se regenera, y si cambian los equipos,
la distancia o el PvP solo se mueve lo que se genera. Chunky no hizo falta: Precalentado usa tickets de carga de
vanilla, que Moonrise atiende igual.

### El código de Venatio

Tiempo de los oyentes de Venatio en cada tick (detección de casillas cada 10 ticks con logros y cantidades, inventarios
llenos de 30 stacks, `SeguimientoRetos`, localizador cada 10 ticks, marcas, eventos, estado a todos cada segundo y en
cada cambio): **0.010–0.038 ms de media** en todas las corridas, contra 6–16 ms de MSPT. El máximo por tick suele ser
0.3–5 ms (el primer tick de la partida); hubo un pico aislado de 126 ms en los primeros 10 s de una partida, en una
ventana con tirones generales (probablemente una pausa de GC; no se pudo confirmar). Ninguno de los
sospechosos (detección, paquetes del localizador, marcas, eventos, reaparición, `enviarEstadoATodos`) aparece: no se
cambió ese código.

Lo que sí se cambió:

- **Autoguardado** (`dev.venatio.rendimiento.mixin.ServerLevelMixin`): el autoguardado de cada 5 minutos serializa en
  el hilo principal todos los chunks modificados de golpe. En un mundo recién generado son casi todos: medido con 3–8
  mil chunks cargados, **30–50 ms** (vanilla). Los mundos de la partida ya no entran: queda en **7–13 ms** (el lobby y el
  mundo principal). Los chunks se siguen guardando al descargarse, así que volver a una zona la encuentra como se dejó.
  El guardado al apagar ya lo omitía Fantasy para mundos temporales. Las dos cifras son de la misma Mac sin otra carga
  y con el mismo conjunto (base); en las corridas posteriores, con la Mac más ocupada, el guardado quedó en 15–45 ms.
- **Precalentado** y **radio de pregeneración configurable** (`-Dvenatio.pregen`), descritos arriba.
- **Tableros del lobby con Moonrise** (`TablerosLobby`): además de `ALLOW_LOAD`, escuchan `ENTITY_LOAD` (ver
  conjuntos de mods).

Los mundos temporales sí escriben a disco: al descargar cada chunk (necesario para que el terreno modificado no se
regenere si el jugador vuelve; Fantasy no tiene opción para evitarlo y `noSave` haría que nunca se descarguen) y en el
autoguardado (ya no).

## Recomendación para exaroton

### Mods del servidor

| Mod | Versión (Modrinth) | Para qué |
|---|---|---|
| Fabric API | 0.166.2+26.3 | (ya estaba) |
| Venatio | la del instalador (trae Fantasy adentro) | |
| **Moonrise** | 1.2.2+35bacce | Sistema de chunks de Paper: el doble de generación con 2 hilos |
| Lithium | mc26.3-0.26.4-fabric | Optimiza física, IA y ticks sin cambiar el juego |
| FerriteCore | 9.0.0-fabric | Menos memoria (Moonrise apaga dos de sus opciones solo, es normal) |
| Krypton | 0.3.2 | Red más barata (compresión y armado de paquetes) |
| spark (opcional) | 1.10.187-fabric | Para medir en exaroton: `/spark tps`, `/spark health`, `/spark profiler start --timeout 120` |

Van solo en el servidor; los clientes no los necesitan. **No poner**: C2ME (fuga de memoria con Fantasy y tirones),
ScalableLux (Moonrise lo declara incompatible; trae su propio motor de luz), Carpet (solo pruebas).

La lista completa de pruebas con cliente (15 clases, 166 pasos) pasa con este conjunto y Moonrise de 2 hilos
(`ORG_GRADLE_PROJECT_mods=b ORG_GRADLE_PROJECT_jvmExtra=-DMoonrise.WorkerThreadCount=2 tools/prueba.sh`, 21 de nov.).
Para llegar ahí hubo que corregir los tableros del lobby (ver arriba) y endurecer `PruebaArte`, cuyo bot se ahogaba
según dónde cayera su equipo (no tenía que ver con Moonrise).

Configuración de Moonrise, `config/moonrise.yml` (se crea al primer arranque; cambiar solo esto):

```yaml
worker-pool:
  worker-threads: 2
```

Con 3 núcleos Moonrise se configura solo con 1 hilo y genera la mitad. Al arrancar, el log dice
`Moonrise is using N worker threads`: tiene que decir 2. Si con spark la MSPT p95 queda arriba de 40 ms de forma
sostenida en exaroton (núcleos más lentos y compartidos), volver a 1. (También se puede fijar con la bandera
`-DMoonrise.WorkerThreadCount=2`.)

### server.properties

```properties
view-distance=12
simulation-distance=8
sync-chunk-writes=false
network-compression-threshold=256
entity-broadcast-range-percentage=100
max-tick-time=60000
```

- `view-distance=12`: con Moonrise de 2 hilos la MSPT es la misma con 10, 12 y 16; lo que crece es lo que hay que
  generar. 12 muestra un 42 % más de área que 10 y, volando, deja ~26 % de la vista por llenar contra ~29 % con 16 en
  la Mac. En exaroton cada núcleo rinde menos, así que 16 dejaría bastantes más huecos al volar; 12 es el punto medio.
  Si con spark sobra (MSPT p95 < 25 ms volando), se puede probar 14–16: solo hay que reiniciar.
- `simulation-distance=8`: ver arriba; 0.6 ms más que 6 y la aparición de mobs queda igual que en vanilla.
- `sync-chunk-writes=false`: con Moonrise da igual (lo ignora y nunca escribe en síncrono), pero si un día se quita
  Moonrise, en `true` (el valor por defecto de vanilla) cada escritura de región espera al disco.
- `network-compression-threshold=256` y `entity-broadcast-range-percentage=100`: los valores por defecto. Krypton
  abarata la compresión; bajar el porcentaje de entidades ahorra red pero hace que los mobs se vean más cerca (cambia
  el juego). Ninguno de los dos se midió con jugadores reales por internet.
- `max-tick-time=60000`: el valor por defecto (el watchdog solo actúa con un tick de 60 s; no pasó nunca).

### Banderas de JVM (si exaroton deja ponerlas)

exaroton permite agregar argumentos de inicio en las opciones del server (no en la prueba gratis). Lo medido:

```
-XX:MaxGCPauseMillis=50 -DMoonrise.WorkerThreadCount=2
```

Con los 6 GB que asigna exaroton, G1 por defecto apunta a pausas de 200 ms y en la medición hizo pausas de 60 ms de
mediana y de hasta 0.5–1 s; con `MaxGCPauseMillis=50` la mediana bajó a 29 ms y los ticks de más de 50 ms a una cuarta
o sexta parte. No se sabe qué banderas pone exaroton por defecto (su ayuda solo dice que son "optimizadas"): si ya son
las de Aikar, que midieron parecido, no hace falta cambiarlas y basta con `-DMoonrise.WorkerThreadCount=2` (o el
`moonrise.yml`). Para revisarlo: `/spark gc` muestra las pausas. Si aun así hay tirones, probar `-XX:+UseZGC` en lugar
de las banderas de G1 (en la Mac quitó todos los ticks de más de 50 ms volando, pero con 3 núcleos compartidos su
trabajo en paralelo compite con la generación: hay que verificarlo con spark allá). No poner `-Xmx` a mano: exaroton lo
fija con la RAM del plan.

### En Venatio (ya incluido)

- Precalentado activo por defecto (`-Dvenatio.precalentar=false` lo apaga).
- Radio de pregeneración 2 (`-Dvenatio.pregen=<radio>` para cambiarlo sin recompilar).
- Los mundos de la partida no entran al autoguardado.

## Pendiente para próximas sesiones

Lo que faltó probar o confirmar, y cómo medirlo.

- **exaroton real con spark.** No hubo acceso; macOS no deja limitar un proceso a 3 núcleos y `ActiveProcessorCount=3`
  solo reproduce cómo se dimensionan los hilos, no la potencia. Cómo: subir spark 1.10.187-fabric al server, jugar una
  partida normal con 4–6 personas (con al menos un rato de élitro) y correr `/spark profiler start --timeout 300`,
  `/spark tps`, `/spark health` y `/spark gc` durante el juego. Confirmar en el log `Moonrise is using 2 worker
  threads`. Decide: si MSPT p95 < 40 ms con 2 hilos de Moonrise (si no, volver a 1) y si la vista 12 se queda o sube.
- **Banderas de JVM por defecto de exaroton.** No se sabe cuáles pone. Cómo: en el panel, opciones del server, ver los
  argumentos de inicio; o en la información del sistema del informe de `/spark profiler`. Si no son las
  de Aikar, agregar `-XX:MaxGCPauseMillis=50` y comparar `/spark gc` antes y después.
- **ZGC con 3 núcleos compartidos.** En la Mac quitó los ticks de más de 50 ms, pero su trabajo concurrente sobraba de
  núcleos. Cómo: en exaroton, una partida con `-XX:+UseZGC` y otra con G1 (`-XX:MaxGCPauseMillis=50` o Aikar), mismo
  grupo y semilla fija (`/venatio semilla <n>`), comparando `/spark tps` (p95 de MSPT), `/spark gc` y si los jugadores
  notan huecos al volar.
- **Distant Horizons 3.3.4-26.3 con un cliente aparte.** Su generación lejana solo arranca cuando un cliente con DH pide
  LODs; en esta prueba el cliente comparte la JVM con el servidor y lo frenaría. Cómo: `./gradlew runServer` con
  `-Pmods`/el jar de DH en `run/server/mods`, conectar desde otra computadora (o una instancia de Minecraft aparte, no
  la de la prueba) con DH instalado, volar 5 min y medir con spark en el servidor (`/spark tps`, `/spark profiler`); en
  paralelo correr `PruebaRendimiento` para tener los bots. Comparar chunks/s y MSPT con y sin DH.
- **VMP 0.2.0+beta.7.238+26.3 y ServerCore 1.5.21+26.3.** Ya están en `build.gradle`. Cómo:
  `ORG_GRADLE_PROJECT_mods=b+vmp ORG_GRADLE_PROJECT_jvmExtra=-DMoonrise.WorkerThreadCount=2 ORG_GRADLE_PROJECT_nucleos=3
  ORG_GRADLE_PROJECT_memoria=6G ORG_GRADLE_PROJECT_rendVistas=12 ORG_GRADLE_PROJECT_rendVelocidades=6,25
  ORG_GRADLE_PROJECT_rendSegundos=90 ORG_GRADLE_PROJECT_rendCalentar=30 ORG_GRADLE_PROJECT_rendPrecalentar=false
  tools/prueba.sh PruebaRendimiento`, y lo mismo con `b+servercore`; comparar contra `b` en la misma sesión. Con
  ServerCore revisar antes que su `config/servercore/` siga con activación por rango, límites dinámicos y límite de
  crías apagados (así vienen por defecto), y correr la lista completa de pruebas con él.
- **Red real con jugadores por internet.** Los bots arman los paquetes de chunks pero no se comprimen ni se envían.
  Cómo: en exaroton con 4–6 jugadores, `/spark profiler start --thread *` (incluye los hilos de Netty) y ver cuánto
  CPU se va en compresión; probar `network-compression-threshold=256` contra `512`. Del lado de los jugadores, F3 muestra
  el ping y los chunks pendientes.
- **Partidas largas y élitro de horas.** Los escenarios duran 90 s y los bots no construyen, no pelean ni van al Nether o
  al End. Cómo: `PruebaRendimiento` con `ORG_GRADLE_PROJECT_rendSegundos=1800` (una vista, élitro) para ver si la
  memoria y la MSPT se estabilizan; y en exaroton, `/spark profiler` en una partida real de 30–60 min con Nether y End.
- **Varias partidas seguidas sin reiniciar.** Se probaron hasta 7 por JVM (sin fuga sin C2ME). Cómo: en exaroton, jugar
  5–10 partidas seguidas y mirar `/spark health` (memoria usada tras GC) al final de cada una; no debe crecer.
- **Precalentado en exaroton.** En la Mac con Moonrise la ganancia fue de 1.6 s. Cómo: en exaroton, iniciar una partida
  justo al entrar al lobby y otra después de 1–2 min en el lobby; comparar el tiempo en "Preparando…" y los huecos al
  aparecer. Si estorbara, `-Dvenatio.precalentar=false` lo apaga.
- **Radio de pregeneración sin Moonrise.** Solo se midió con Moonrise. Cómo:
  `ORG_GRADLE_PROJECT_mods=a_sin_c2me ORG_GRADLE_PROJECT_rendPregen=2,6,12 ORG_GRADLE_PROJECT_rendVistas=12
  ORG_GRADLE_PROJECT_rendVelocidades=6 ORG_GRADLE_PROJECT_rendSegundos=40 ORG_GRADLE_PROJECT_rendPrecalentar=false
  ORG_GRADLE_PROJECT_nucleos=3 ORG_GRADLE_PROJECT_memoria=6G tools/prueba.sh PruebaRendimiento`.
- **`region-file-compression=lz4` y `-XX:+UseCompactObjectHeaders`.** La prueba los acepta y no se corrieron. Cómo:
  `ORG_GRADLE_PROJECT_rendRegion=lz4` y `ORG_GRADLE_PROJECT_jvmExtra='-DMoonrise.WorkerThreadCount=2
  -XX:+UseCompactObjectHeaders'` en la misma línea de arriba, comparando chunks/s y "memoria con mundos".
- **Repetir las corridas con la Mac sin otra carga.** Las del 21 de nov. coincidieron con un Minecraft abierto aparte.
  Cómo: las mismas líneas de arriba con la Mac libre, y si se puede dos veces cada una para ver la variación.
- **Qué retiene los mundos con C2ME.** Se confirmó la fuga, no la causa. Cómo: con `-Pmods=a`, tras 3 partidas
  `jcmd <pid> GC.heap_dump /tmp/c2me.hprof` y abrirlo en Eclipse MAT o VisualVM ("Path to GC roots" de un
  `xyz.nucleoid.fantasy.RuntimeLevel` de una partida ya borrada). Sirve para reportarlo a C2ME o a Fantasy.
