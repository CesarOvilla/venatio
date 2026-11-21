# Venatio — diseño

Bingo de Minecraft propio, más interactivo que MCBingo, con ItemFinder integrado como modo de juego. Nombre de trabajo; se puede cambiar.

**Todo el texto del juego va en español**: HUD, menús, lobby, mensajes, avisos, instalador y errores (sección 9).

## 1. Decisión de plataforma

**Un mod de Fabric con parte de servidor y parte de cliente, Minecraft 26.3 (Java 25).** Los amigos instalan el paquete una vez con el instalador de Venatio (Mac, Windows o Linux) y entran desde el launcher oficial de Minecraft o desde el launcher que usen.

- **El servidor manda:** genera cartones, detecta items, cobra ayudas, lleva tiempos y puntos. El cliente no decide nada que afecte el resultado.
- **El cliente presenta:** HUD propio, pantallas, teclas, minimapa, animaciones, sonidos y texturas.
- Un jugador sin el mod (o con una versión vieja) no entra: el server lo rechaza con un mensaje en español que dice cómo instalarlo.

| Opción | Veredicto |
|---|---|
| Datapack (lo actual) | Llegó a su límite: no puede leer recetas ni loot tables, ni crear mundos, ni dibujar HUD propio. |
| Spigot / Paper | Solo servidor; no permite HUD, teclas ni minimapa propios. |
| Forge / NeoForge | Viables, pero más pesados. El server actual ya es Fabric y los mods de rendimiento que usan (Sodium, Lithium, C2ME) son de Fabric. |
| **Fabric (cliente + servidor)** | Elegido. Desde 26.1 Minecraft ya no está ofuscado, así que se programa con los nombres reales del juego. |

Por qué 26.3 y no 1.21.11: es la versión actual (15-sep-2026) y todo lo necesario ya tiene versión para 26.3: Fabric API, Fantasy, Sodium, Mod Menu, AutoModpack, Lithium, C2ME, Krypton, FerriteCore y ScalableLux. Noisium no tiene versión 26.3; se queda fuera.

Librerías del mod:

- **Fabric API** — eventos, comandos, red cliente↔servidor, teclas.
- **Fantasy** (NucleoidMC) — crea y borra dimensiones en caliente: cada partida tiene su propio mundo con semilla nueva.

Polymer y sgui, que hacían falta para un cliente vanilla, se quitan: con mod de cliente las pantallas y el HUD son propios.

## 2. Qué se conserva y qué se trae

**De MCBingo (MIT, SirChi)** — lo que ya les gusta:
- Pool de 116 items en 50 categorías × 3 dificultades. Extraído a `src/main/resources/data/venatio/pools/clasico.json` con `tools/extraer_pool_mcbingo.py`.
- Cartón por "semilla" reproducible: el mismo número da el mismo cartón y los mismos spawns.
- Modos 5 en línea, Blackout, Contrarreloj (25 min), Lockout, PvP (mismo spawn).
- Configs de partida: visión nocturna, velocidad, botas con Depth Strider, élitro, todas las recetas.

**De ItemFinder** — como modo "Rastreo" (se reimplementa la mecánica, no se copia el código):
- Un item objetivo a la vez, el mismo para todos, sesgado por los biomas cercanos.
- Blitz (más items en X minutos) y Contrarreloj (primero en llegar a N).
- Su lista de ~600 items sirve de base para el pool del Rastreo.

**Se elimina:** el mapa como cartón, la dimensión `bingo:bingo_card`, el bot BingoCam de Carpet y los teletransportes a ±5,000,000.

## 3. Interfaz (mod de cliente)

| Lo que pediste | Cómo se hace |
|---|---|
| El tablero en la interfaz, no en un mapa | **Cartón en el HUD**, en una esquina, con los items dibujados como en el inventario. Cada casilla conseguida se marca con el color del equipo y una animación. En Lockout, las casillas de otros equipos se tachan con su color. Tamaño y posición configurables. |
| Ver el tablero completo | Tecla **B**: pantalla grande del cartón con estado de cada casilla, quién la tiene y a qué minuto. |
| Saber cómo craftear lo que pide el tablero | Clic en una casilla → **Ficha**: todas las recetas que producen ese item (mesa, horno, alto horno, ahumador, fogata, cortapiedras, herrería, destilación) dibujadas como la mesa real. Los ingredientes con varias opciones van rotando; clic en un ingrediente abre su ficha. Solo cubre lo que pide el cartón y sus ingredientes. |
| Items que tira un mob: cuál y dónde está | La ficha incluye **"De dónde sale"**: el server lo calcula al arrancar recorriendo loot tables (mobs, cofres de estructuras, bloques, pesca, trueques, arqueología) y las listas de spawn por bioma, y se completa con pistas escritas a mano ("brisa → cámaras de prueba"). |
| Brújula que guíe a un bioma | **Guía** de pago: flecha en el HUD y un haz de partículas hacia una **zona aproximada** (radio de ~64 bloques alrededor del objetivo, no el punto exacto) con distancia en rangos ("cerca / media / lejos"). Se apaga al llegar o tras X minutos. |
| Estructuras (cámaras de prueba de cobre, aldeas…) | La misma guía, con la búsqueda que usa `/locate structure`. |
| Mob concreto | Guía al ejemplar más cercano en chunks cargados; si no hay, al bioma donde aparece. |
| Minimapa con limitaciones | **Minimapa propio** (tecla M) que solo funciona cuando el server lo permite: comprado, por tiempo, con radio corto, sin cuevas ni radar de mobs, y solo con lo que tú exploraste en esta partida. |
| Tiempo, modo, puntos | Barra superior propia con tiempo, modo y marcador de equipos. |
| Compañeros | Marcadores de compañeros en el HUD y tecla **N** para poner una marca que ve tu equipo. |

Teclas por defecto (se pueden cambiar en Controles, categoría "Venatio"): **B** cartón, **H** ayudas, **N** marca de equipo, **M** minimapa, **R** ruta. Como espectador: **← →** jugador, **↑ ↓** equipo y **Espacio** vista libre.

**Arte y sonido propios (hecho):** identidad de pabellón de caza, como el lobby: madera oscura, cobre y dorado, con los colores de equipo vivos. Marco del cartón, casillas (vacía, tuya, de rival, bloqueada), emblema de cada equipo (color y figura), iconos, marco de las pantallas e icono del mod; sonidos sintetizados de casilla, bingo, victoria, derrota, cuenta atrás, compra, marca, guía y Rastreo; animaciones de entrada del cartón, cuenta atrás, casilla conseguida, bingo, victoria (banner, confeti y fuegos artificiales) y pantalla final. Los sprites y los sonidos se generan con `tools/arte/texturas.py` y `tools/arte/sonidos.py`; el dibujo vive en `cliente/Arte.java`, los tiempos en `cliente/Animaciones.java` y los sonidos en `cliente/Sonidos.java`. Todo viaja dentro del jar del mod; no hace falta resource pack aparte.

## 4. Ayudas con costo

Recetas y "de dónde sale" son **gratis**: esa información ya existe en el libro de recetas y en la wiki. Lo que acorta exploración **cuesta**.

| Ayuda | Efecto | Costo inicial (configurable) |
|---|---|---|
| Ficha y recetas | Ver cómo se consigue el item | Gratis |
| Guía a bioma | Flecha y haz hacia la zona hasta que entres al bioma | 8 lingotes de hierro |
| Guía a estructura | Igual, a la estructura más cercana | 3 diamantes |
| Guía a mob | Al ejemplar más cercano o a su bioma | 4 lingotes de oro |
| Minimapa | 3 minutos de minimapa limitado | 1 diamante |
| Marca de equipo | Un punto visible para tu equipo | Gratis, cada 30 s |

Reglas: se cobra del inventario al confirmar; cada ayuda tiene enfriamiento; en Lockout no se puede comprar guía hacia una casilla ya tomada; el anfitrión puede apagar las ayudas o cambiar su precio. Los precios son un punto de partida y se ajustan jugando.

**Descuento al último lugar (hecho, opción `descuento`, activa por defecto):** con 2 equipos o más, el equipo que va último por al menos 2 casillas o puntos paga las ayudas un 20 % menos (hacia abajo, mínimo 1). Si varios empatan en el último lugar, todos lo tienen. La tienda muestra el precio tachado y el nuevo. Vale en todos los modos (`ayudas/Descuento.java`).

Pendiente: pagar con items (lo que propusiste, va por defecto) o con fichas ganadas por casilla, que permiten dar más al equipo que va perdiendo.

## 5. Mundo nuevo en cada partida

- Cada partida crea con Fantasy su propio overworld, Nether y End con semilla aleatoria (o la del cartón si se juega por semilla). Al terminar se borran.
- Los portales del Nether y del End se redirigen a las dimensiones de la partida (requiere un mixin; es el punto técnico más delicado).
- Cada partida tiene un id de dimensión único (`venatio:partida_0042`): el minimapa, los mapas vanilla y cualquier caché de cliente empiezan en blanco.
- Durante la cuenta atrás se pregeneran los chunks alrededor de cada spawn.
- Opcional: preset de "biomas pequeños" como el de MCBingo.

## 6. Modos

**Bingo**
- 5 en línea, N líneas, Blackout, Lockout, Contrarreloj.
- Cartón de 3×3, 4×4 o 5×5.
- Spawns separados o compartido (PvP).
- Variante "niebla": hecha como *Cartón oculto* (abajo).

**Rastreo** (ItemFinder)
- Blitz y Contrarreloj, individual o por equipos; saltar item con costo.

**Cartón ampliado (hecho, opciones del anfitrión; valen en todos los modos, también en Rastreo):**
- *Casillas de logros* (`/venatio opcion logros si`, apagada por defecto): ~25–30 % de las casillas piden hacer algo en vez de tener un item: entrar a un bioma (también del Nether), matar un mob, comer algo, dormir, criar, domesticar, pescar, encantar, comerciar, montar, tocar una campana, minar un mineral, subir a la altura 200 o bajar a −50, entrar al Nether. Nada del End. Se detectan por la posición o por las estadísticas del jugador (comparando contra el valor del principio); domesticar y comer, con los disparadores de logros de vanilla. Catálogo en `carton/Retos.java`, textos en `assets/venatio_carton/lang`. Cada uno tiene icono, nombre y ficha con "Guíame" a sus biomas o mobs.
- *Casillas con cantidad* (`/venatio opcion cantidades si`, apagada por defecto): ~30 % de las casillas de item piden varias unidades ("16 × Tronco de roble"), según el nivel de la casilla y el tamaño de pila; nunca en items que no se apilan. Cuenta la suma de lo que llevan en el inventario los miembros del equipo; los regalos de la partida no cuentan. El progreso se ve en la casilla (HUD y pantalla B).
- *Dificultad del cartón* (`/venatio dificultad facil|normal|dificil|mixta`): pesos de los niveles 1, 2 y 3 al elegir cada categoría (fácil 60/30/10, difícil 10/30/60, mixta en tercios exactos). Normal es el reparto de MCBingo y da el mismo cartón que antes con la misma semilla.
- *Quitar items del pool* (`/venatio pool quitar|poner <item>`, `/venatio pool lista`, o clic derecho de un operador en "Items posibles"): se guarda en `config/venatio/pool.json`; una categoría que se queda sin items no se usa.

**Variantes del cartón (hecho, pestaña "Variantes" del panel; apagadas por defecto, combinables entre sí y con 5 en línea, Apagón, Lockout y Contrarreloj; en Rastreo y Switcheroo no aplican):**
- *Cartón oculto* (`/venatio opcion oculto si`): cada equipo empieza viendo solo la casilla central (las cuatro del centro en un 4×4); las demás se ven tapadas, con un signo de interrogación. Cada casilla que consigue destapa sus cuatro vecinas para ese equipo, con animación. Una tapada no se marca aunque se tenga el item. El servidor no manda lo que piden las tapadas (llegan como "Casilla tapada"); al que la tiene tapada, el aviso del chat no dice qué era; en la pantalla B, la vista de otro equipo tampoco muestra lo que ese equipo no destapó. En Lockout, la que toma otro equipo se destapa como tomada. Al final se destapa todo.
- *Draft* (`/venatio opcion draft si`, `/venatio vetos 1|2`): mientras se genera el mundo, con el cartón completo a la vista (aunque sea oculto), todos los equipos vetan a la vez durante 45 s en la pantalla del draft (se abre sola; B la vuelve a abrir): clic para elegir la casilla, otro clic o "Vetar" para vetarla. La vetada se cambia por otra del pool del mismo tipo y nivel, de una categoría que no esté en el cartón. Cualquier miembro decide; la lista dice quién vetó qué y el chat lo anuncia. Termina antes si todos vetaron; quien no vetó a tiempo se queda sin vetar.
- *Casilla secreta* (`/venatio opcion secreta si`): al empezar la cuenta atrás cada equipo recibe una casilla al azar (distinta por equipo si se puede) que solo él ve resaltada (marco dorado y estrella). Vale doble en todo lo que cuenta casillas: Contrarreloj, mayoría de Lockout, marcador final, puntos del ranking y desempates; en 5 en línea y Apagón no cambia cómo se gana. Durante la partida solo el marcador del propio equipo suma el doble. Se revela al final en el chat y en la pantalla final ("Casilla secreta de Rojo: Diamante (+1)").
- *Relevo* (`/venatio opcion relevo si`): al empezar la cuenta atrás las filas se reparten entre los miembros de cada equipo (fila r para el miembro r mod n: con dos, 1, 3 y 5 al primero y 2 y 4 al segundo). Una casilla solo cuenta si la consigue el dueño de su fila (en las de cantidad, con lo que lleva él). El HUD y la pantalla B ponen la cara del dueño junto a cada fila (las tuyas con marco dorado) y la lista de miembros dice sus filas. Si el dueño se desconecta, la fila pasa al miembro conectado con menos filas y vuelve cuando regresa.

El código está en `juego/Variantes.java`, `juego/Draft.java`, `carton/Reemplazos.java`, `red/PaquetesVariantes.java` (viaja dentro del estado), `cliente/VariantesCliente.java` y `cliente/PantallaDraft.java`.

**Más adelante:** casillas de estructura ("entra a una aldea") y de logros de vanilla.

**Switcheroo** (hecho): Rastreo compartido con tiempo por item. El primer equipo que consigue el item gana 1 punto y todos pasan al siguiente; si nadie lo consigue en `/venatio switcheroo 1|2|3|5` minutos (3 por defecto), cambia solo sin puntos. Gana quien llegue a la meta o tenga más puntos a los 20 min. El HUD lleva la cuenta regresiva del item, roja y con tic los últimos 10 s; los items que cambiaron sin dueño quedan en la línea de tiempo final.

**Eventos sorpresa** (hecho, opción `eventos`, apagada por defecto): cada `/venatio eventos_minutos 5|8|10` minutos (8 por defecto, ±25 %), uno al azar para todos, anunciado 10 s antes con banner, sonido e indicador en el HUD. Lista en `juego/TipoEvento.java`: noche eterna, velocidad, teletransporte a 150–300 bloques (solo a superficie segura), intercambio entre rivales, horda (sin creepers el primer minuto, nunca en el Nether), lluvia de items (nunca algo que pida la partida), gravedad lunar, niebla, tormenta (los rayos no lastiman), mercader nómada por equipo, todos a la vista y lluvia de experiencia. El anfitrión lanza uno a mano con `/venatio evento <nombre>`. Cada evento queda en la línea de tiempo final.


## 7. Lobby, equipos y ranking

- Lobby construido (estructura NBT) en el mundo principal: zona de equipos, panel del anfitrión, ranking en hologramas, historial de partidas.
- Equipos: elegidos, aleatorios o balanceados por ranking; hasta 8 colores.
- El anfitrión configura la partida en una pantalla del mod; los demás ven la config en el lobby.
- **Estadísticas persistentes** (JSON en `config/venatio/` del server): partidas, victorias por modo, casillas totales, bingo más rápido, casilla más rápida, ayudas compradas.
- **Ranking por temporada** con puntos por puesto.
- **Pantalla final** con la línea de tiempo de la partida: quién consiguió qué y a qué minuto.
- **Espectador (hecho):** juega quien tiene equipo al iniciar. Quien no tiene equipo (por ejemplo, el anfitrión que no juega), quien entra a media partida sin equipo y quien usa `/venatio mirar` desde el lobby mira la partida en modo espectador dentro de sus mundos; no cuenta para casillas, lockout, localizador, estadísticas ni victoria. Sigue a un jugador en primera persona con la cámara de espectador vanilla; ← → cambia de jugador, ↑ ↓ de equipo, Espacio (o Shift) deja la vista libre, B muestra el cartón del equipo que mira y R la ruta en vivo. `/venatio mirar salir` lo regresa al lobby. Un jugador de equipo que se desconecta y vuelve sigue jugando. Si nadie tiene equipo, la partida no empieza.
- **Ruta al final (hecho, sustituye a BingoCam):** durante la partida el servidor graba cada 2 s la posición de cada participante, los eventos con su lugar (casilla, muerte, portal, ayuda comprada) y una muestra del terreno explorado (colores de mapa, 1 px cada 4 bloques, solo de chunks cargados, unos pocos chunks por tick). Al terminar la comprime fuera del hilo del servidor y la manda en trozos de 256 KB. Si alguien se va muy lejos: el mapa se abre encuadrando todo el recorrido (el zoom llega hasta 0.002 px por bloque), el terreno se dibuja en mosaicos de 512 bloques (solo donde hay algo, sin una textura enorme y vacía entre zonas) y con zoom o clic en el jugador de la leyenda se acerca a cualquier zona; si el terreno explorado pasa de 40 000 chunks se manda a 2×2 colores por chunk, y de 120 000 a 1 (tope: 100 000 chunks por dimensión). La pantalla final tiene el botón **Ruta**: terreno, línea de cada jugador con el color de su equipo y su cabeza al final, iconos de casillas, muertes, portales, ayudas y salidas de equipo, zoom con la rueda, arrastre y selector Overworld/Nether. Se vuelve a ver desde el lobby con R o `/venatio ruta` hasta que empieza otra partida. Código en `ruta/` (servidor) y `cliente/ruta/`.

## 8. Instalación para los amigos (Mac, Windows, Linux)

Objetivo: que cada amigo ejecute una sola cosa y luego entre desde su launcher, sin copiar archivos a mano.

**Instalador Venatio** — un comando para pegar en la terminal:
- Mac y Linux: `curl -fsSL <url>/instalar.sh | sh`
- Windows: `irm <url>/instalar.ps1 | iex` en PowerShell

Se usa un comando pegado y no un ejecutable descargado porque así macOS (Gatekeeper) y Windows (SmartScreen) no bloquean un programa sin firma. Más adelante se puede añadir un instalador con doble clic.

Qué hace, sin tocar sus otros mods ni mundos:
1. Encuentra la carpeta de Minecraft: Mac `~/Library/Application Support/minecraft`, Windows `%APPDATA%\.minecraft`, Linux `~/.minecraft` (o la de Flatpak).
2. Instala Fabric 26.3 sin necesitar Java: descarga el perfil de `meta.fabricmc.net` y lo deja en `versions/`.
3. Crea el perfil **"Venatio"** en el launcher oficial (también en el de Microsoft Store si existe) con su propia carpeta de juego `.minecraft/venatio`, ícono y memoria ajustada.
4. Pone en `venatio/mods/` lo que diga `instalador/paquete.txt` (hoy Fabric API, Sodium y Venatio), verificando cada archivo por SHA-512, y quita los jars que ya no están en el paquete.
5. Deja el juego en español (`lang:es_mx`) y el server de Venatio en la lista de servidores (el mod lo agrega al abrir el juego desde `config/venatio-servidor.txt`).

**Actualizaciones (hecho):** volver a ejecutar el mismo comando. Si alguien entra con otra versión del mod, el server lo rechaza con un mensaje en español que dice que vuelva a ejecutar el instalador. `tools/generar_paquete.py` arma `paquete.txt` desde Modrinth y el jar compilado.

**Actualizaciones automáticas (pendiente):** AutoModpack descargaría del propio server lo nuevo al entrar. Hay que probar que funcione detrás del proxy de exaroton.

**Otros launchers:** el mismo paquete se exporta como `.mrpack` (Modrinth App, Prism, ATLauncher) y como zip de CurseForge para quien use esos.

**Dónde se alojan el instalador y los archivos:** repo público de GitHub (decidido). No se publica hasta que el juego esté completo.

## 9. Todo en español

- Todos los textos del mod viven en archivos de idioma; el contenido en español se publica también como `en_us.json`, así se ve en español aunque alguien tenga el juego en inglés.
- El instalador pone el juego en español de México, así que los nombres de items, bloques y biomas también salen en español.
- Los mensajes que manda el server son claves de traducción que resuelve el cliente: no hay texto en inglés escondido en el código.
- El instalador, sus errores y el mensaje de "te falta el mod" también van en español.

## 10. Estructura del código

```
src/main/java/dev/venatio        (servidor y común)
├── Venatio            arranque, comandos, config
├── red/               paquetes cliente↔servidor, verificación de versión del mod
├── juego/             Partida (LOBBY → PREPARANDO → CUENTA_ATRAS → JUGANDO → FINAL), Equipo, Modo (Bingo, Rastreo)
├── carton/            Carton, Casilla, Objetivo (item, bioma, estructura, mob…), GeneradorCarton
├── mundo/             MundosPartida (Fantasy), Portales (mixin), Pregeneracion
├── info/              IndiceRecetas, IndiceFuentes (loot tables y spawns), Pistas (JSON curado)
├── ayudas/            Ayuda, Precios, Guia, PermisoMinimapa
├── lobby/             Lobby, Hologramas, SeleccionEquipos
└── stats/             Estadisticas, Ranking

src/client/java/dev/venatio/cliente
├── VenatioCliente     arranque, teclas
├── hud/               HudCarton, BarraPartida, FlechaGuia, Marcas, Animaciones
├── pantallas/         PantallaCarton, PantallaFicha, PantallaAyudas, PantallaConfig, PantallaFinal
└── minimapa/          Minimapa (render limitado, permiso del server)

instalador/            instalar.sh, instalar.ps1, plantilla del perfil
```

Datos (pools, pistas, precios) en `data/venatio/…` con formato de datapack para cambiarlos sin recompilar.

## 11. Fases

| Fase | Contenido | Resultado |
|---|---|---|
| Fase | Contenido | Estado |
|---|---|---|
| 0 | Esqueleto Fabric 26.3 cliente + servidor, pool extraído | Hecho |
| 1 | Partida: lobby, equipos, mundo nuevo por partida, cartón, detección, 5 en línea / Apagón / Lockout / Contrarreloj, HUD, pantalla B (con vista por equipo), verificación del mod | Hecho |
| 1b | Paridad con MCBingo: visión nocturna, velocidad, Depth Strider, élitro, todas las recetas, fin automático opcional; minado en veta (minerales y troncos) | Hecho |
| 2 | Instalador Mac/Linux/Windows | Hecho en local (Windows sin probar). Falta: publicar en GitHub, server en exaroton, prueba con amigos, AutoModpack |
| 3 | Fichas: recetas, "de dónde sale", pistas en español, items posibles | Hecho |
| 4 | Ayudas con costo: tienda, guías, minimapa limitado, marcas de equipo | Hecho |
| 5 | Lobby construido, ranking, estadísticas, pantalla final, panel del anfitrión; arte, sonidos y animaciones propios | Hecho |
| 6 | Modo Rastreo (ItemFinder): normal y carrera, compartido o independiente | Hecho |
| 7 | Cartón ampliado: casillas de logros, casillas con cantidad, dificultad del cartón, quitar items del pool | Hecho. Falta: integrarlos al panel del anfitrión |
| 8 | Variantes del cartón: cartón oculto, draft, casilla secreta y relevo | Hecho |
| 9 | Ruta al final de la partida y modo espectador | Hecho |

Cada módulo tiene su prueba con cliente real en `src/gametest` (`./gradlew runClientGameTest`).

## 12. Despliegue del server

- Desarrollo: `./gradlew runServer` + `./gradlew runClient` en local.
- Producción: server Fabric 26.3 en exaroton con Venatio, Fabric API, Moonrise (con 2 hilos de trabajo), Lithium, FerriteCore y Krypton; `view-distance=12`, `simulation-distance=8`. C2ME no: con Fantasy no libera los mundos borrados (fuga de memoria por partida). Mediciones y configuración completa en [`RENDIMIENTO.md`](RENDIMIENTO.md). Carpet ya no hace falta. VeinMiner tampoco: el minado en veta es parte de Venatio. Los datapacks Craftable y ALL Recycling Recipes que había en el server de MCBingo se pueden copiar a `world/datapacks` si se quieren (las recetas son globales y aplican a los mundos de partida).
- El server actual de MCBingo (1.21.11) se deja intacto hasta que Venatio esté listo.

## 13. Propuestas para después (2026-11-20)

**Elegidas por el usuario (2026-11-20):** modos 1–5 (cartón oculto, Switcheroo, draft, casilla secreta, relevo) y las mejoras de casillas de logros, casillas con cantidad, ruta al final, espectador, dificultad y pool editable, descuento al último lugar (20 %) y eventos sorpresa. El kit inicial queda descartado. Todo lo que cambia las reglas va como **variante activable** (pestaña "Variantes" del panel), para poder jugar con o sin ella.

En curso: tres agentes en paralelo (cartón ampliado: logros, cantidades, dificultad, pool · ruta y espectador · Switcheroo, eventos y descuento). Después, otro con cartón oculto, draft, casilla secreta y relevo, que tocan el mismo código del cartón.

★ = recomendadas.

**Modos nuevos**

| Modo | Cómo funciona |
|---|---|
| ★ Cartón oculto | Solo ves las casillas vecinas a las que ya conseguiste; el cartón se descubre conforme avanzas. Premia planear la ruta. |
| ★ Switcheroo / Rey del item | Todos buscan el mismo item a la vez; cada X minutos cambia. Punto para quien lo consiga primero. Es la idea original de ItemFinder, en grupo. **Hecho** (sección 6). |
| Draft | Antes de empezar, cada equipo veta 1 o 2 casillas del cartón. |
| Casilla secreta | Cada equipo tiene una casilla que solo él conoce y vale doble; se revela al final. |
| Relevo | Cada miembro del equipo tiene asignada su propia fila. |

**Mejoras**

| Mejora | Detalle |
|---|---|
| ★ Casillas que no son items | Hecho (opción "Casillas de logros", sección 6). |
| ★ Casillas con cantidad | Hecho (opción "Casillas con cantidad", sección 6). |
| ★ Ruta al final | Hecho (sección 7): en la pantalla final, un mapa con el recorrido de cada equipo. Sustituye a BingoCam. |
| Espectador | Hecho (sección 7): quien no tiene equipo al iniciar, o usa /venatio mirar, sigue a cualquier jugador con la cámara. |
| Dificultad del cartón | Hecho: fácil / normal / difícil / mixta, y quitar items del pool (sección 6). |
| Kit inicial | Configurable: herramientas de piedra, comida, cama. |
| Ayuda al que va perdiendo | Descuento en la tienda para el último lugar. **Hecho** (sección 4). |
| Eventos sorpresa | Opcionales cada 10 min: noche eterna, velocidad para todos 1 min, lluvia de items… **Hecho** (sección 6). |

**Reglas fijas acordadas**
- Nada de items ni casillas que obliguen a ir al End (es difícil llegar y estar ahí). El portal del End sigue funcionando.
- Conservar inventario al morir, activo por defecto.
- Toda la interfaz se dibuja en coordenadas de GUI: escala con la "Escala de interfaz" del juego. Verificarlo con capturas a varias escalas.

## 14. Pendiente de decidir

- Moneda de las ayudas: items (implementado) o fichas por casilla.
- Nombre definitivo.
- Si se queda el comando de prueba `/venatiodev minimapa`.
- **Saltar por votación en Rastreo** (propuesto 2026-11-21, sin hacer). Hoy cualquiera salta solo con `/venatio saltar` (60 s
  entre saltos). Propuesta: botón "Votar saltar" en el cartón (B) y en el chat con clic, contador "Saltar: 1/3" en el HUD,
  la votación caduca a los 30 s, se mantienen los 60 s entre saltos; en Rastreo por equipo votan solo los compañeros.
  Falta decidir quién vota: un voto por equipo (recomendado), todos los jugadores, o que el anfitrión elija en el panel
  entre libre / por equipo / todos.
