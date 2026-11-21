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

Teclas por defecto (se pueden cambiar en Controles, categoría "Venatio"): **B** cartón, **H** ayudas, **N** marca de equipo, **M** minimapa.

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
- Variante "niebla": solo ves las casillas vecinas a las que ya tienes.

**Rastreo** (ItemFinder)
- Blitz y Contrarreloj, individual o por equipos; saltar item con costo.

**Más adelante:** casillas que no son items (visitar un bioma, entrar a una estructura, matar un mob, conseguir un logro, domar o criar un animal). El motor de casillas admite estos tipos desde el inicio.

## 7. Lobby, equipos y ranking

- Lobby construido (estructura NBT) en el mundo principal: zona de equipos, panel del anfitrión, ranking en hologramas, historial de partidas.
- Equipos: elegidos, aleatorios o balanceados por ranking; hasta 8 colores.
- El anfitrión configura la partida en una pantalla del mod; los demás ven la config en el lobby.
- **Estadísticas persistentes** (JSON en `config/venatio/` del server): partidas, victorias por modo, casillas totales, bingo más rápido, casilla más rápida, ayudas compradas.
- **Ranking por temporada** con puntos por puesto.
- **Pantalla final** con la línea de tiempo de la partida: quién consiguió qué y a qué minuto.
- Quien entra tarde o muere sin respawn entra como espectador.

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
| 6 | Modo Rastreo (ItemFinder): normal y carrera, compartido o independiente | Hecho. Falta: casillas que no son items |

Cada módulo tiene su prueba con cliente real en `src/gametest` (`./gradlew runClientGameTest`).

## 12. Despliegue del server

- Desarrollo: `./gradlew runServer` + `./gradlew runClient` en local.
- Producción: server Fabric 26.3 en exaroton con Venatio, Fabric API, Lithium, FerriteCore, Krypton, ScalableLux y C2ME (alpha; probar). Carpet ya no hace falta. VeinMiner tampoco: el minado en veta es parte de Venatio. Los datapacks Craftable y ALL Recycling Recipes que había en el server de MCBingo se pueden copiar a `world/datapacks` si se quieren (las recetas son globales y aplican a los mundos de partida).
- El server actual de MCBingo (1.21.11) se deja intacto hasta que Venatio esté listo.

## 13. Pendiente de decidir

- Moneda de las ayudas: items (implementado) o fichas por casilla.
- Nombre definitivo.
- Si se queda el comando de prueba `/venatiodev minimapa`.
