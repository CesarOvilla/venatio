# Instalar Venatio

Necesitas el launcher oficial de Minecraft (Java Edition) y haberlo abierto al menos una vez. Cierra el launcher antes de instalar.

**Mac o Linux**: abre la Terminal y pega:

```sh
curl -fsSL https://raw.githubusercontent.com/CesarOvilla/venatio/main/instalador/instalar.sh | sh
```

**Windows**: abre PowerShell y pega:

```powershell
irm https://raw.githubusercontent.com/CesarOvilla/venatio/main/instalador/instalar.ps1 | iex
```

Después abre el launcher, elige el perfil **Venatio** y dale a **Jugar**. El server ya aparece en la lista de Multijugador.

Para actualizar, vuelve a pegar el mismo comando. El instalador no toca tus otros perfiles, mods ni mundos: Venatio usa su propia carpeta (`.minecraft/venatio`). Deja un respaldo de la configuración del launcher en `launcher_profiles.json.respaldo-venatio`.
