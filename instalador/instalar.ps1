# Instalador de Venatio para Windows.
# Uso (en PowerShell):  irm <url>/instalar.ps1 | iex
# El archivo es UTF-8 sin BOM: ejecútalo con irm | iex (descargado y abierto en PowerShell 5.1 se verían mal los acentos).
#
# Crea el perfil "Venatio" en el launcher oficial de Minecraft con su propia carpeta de juego
# (no toca tus otros mods ni tus mundos), instala Fabric y los mods del paquete, deja el juego
# en español y agrega el server de Venatio a la lista. Volver a ejecutarlo actualiza todo.
#
# Variables opcionales (para pruebas):
#   $env:VENATIO_PAQUETE    URL del archivo paquete.txt
#   $env:VENATIO_MINECRAFT  carpeta de Minecraft a usar

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ProgressPreference = 'SilentlyContinue'

function Decir([string]$texto) { Write-Host $texto }
function Fallar([string]$texto) { Write-Host ""; Write-Host "❌ $texto" -ForegroundColor Red; throw $texto }

$PaqueteUrl = if ($env:VENATIO_PAQUETE) { $env:VENATIO_PAQUETE } else { 'https://raw.githubusercontent.com/USUARIO/venatio/main/instalador/paquete.txt' }
$PerfilId = 'venatio'
$Utf8SinBom = New-Object System.Text.UTF8Encoding $false

Decir '== Instalador de Venatio =='

# --- 1. Carpeta de Minecraft -------------------------------------------------
$MC = if ($env:VENATIO_MINECRAFT) { $env:VENATIO_MINECRAFT } else { Join-Path $env:APPDATA '.minecraft' }
$ArchivosPerfiles = @('launcher_profiles.json', 'launcher_profiles_microsoft_store.json') |
	ForEach-Object { Join-Path $MC $_ } | Where-Object { Test-Path $_ }
if (-not $ArchivosPerfiles) {
	Fallar "No encontré el launcher de Minecraft en: $MC`nAbre el launcher oficial de Minecraft una vez, ciérralo y vuelve a ejecutar el instalador."
}
Decir "Carpeta de Minecraft: $MC"
if (Get-Process -Name 'MinecraftLauncher', 'Minecraft' -ErrorAction SilentlyContinue) {
	Decir '⚠️  el launcher de Minecraft está abierto. Ciérralo antes de continuar para que vea el perfil nuevo.'
}

$Tmp = Join-Path ([IO.Path]::GetTempPath()) ('venatio-' + [Guid]::NewGuid())
New-Item -ItemType Directory -Path $Tmp | Out-Null

function Descargar([string]$url, [string]$destino) {
	try { Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $destino }
	catch { Fallar "No se pudo descargar: $url" }
}
function Huella([string]$ruta) { (Get-FileHash -Algorithm SHA512 -Path $ruta).Hash.ToLowerInvariant() }

try {
	# --- 2. Paquete ----------------------------------------------------------
	$ArchivoPaquete = Join-Path $Tmp 'paquete.txt'
	Descargar $PaqueteUrl $ArchivoPaquete
	$Version = ''; $McVersion = ''; $Fabric = ''; $ServidorDir = ''; $ServidorNombre = 'Venatio'
	$Mods = @()
	foreach ($linea in Get-Content -Encoding UTF8 $ArchivoPaquete) {
		$linea = $linea.Trim()
		if (-not $linea -or $linea.StartsWith('#')) { continue }
		$partes = $linea -split '\s+'
		switch ($partes[0]) {
			'version' { $Version = $partes[1] }
			'minecraft' { $McVersion = $partes[1] }
			'fabric' { $Fabric = $partes[1] }
			'servidor' {
				$ServidorDir = $partes[1]
				if ($partes.Count -gt 2) { $ServidorNombre = ($partes[2..($partes.Count - 1)] -join ' ') }
			}
			'mod' { $Mods += [pscustomobject]@{ Archivo = $partes[1]; Hash = $partes[2].ToLowerInvariant(); Url = $partes[3] } }
		}
	}
	if (-not $McVersion -or -not $Fabric) { Fallar 'El paquete está incompleto.' }
	Decir "Paquete Venatio $Version - Minecraft $McVersion - Fabric $Fabric"

	# --- 3. Fabric -----------------------------------------------------------
	$VersionId = "fabric-loader-$Fabric-$McVersion"
	$CarpetaVersion = Join-Path $MC "versions\$VersionId"
	New-Item -ItemType Directory -Force -Path $CarpetaVersion | Out-Null
	Descargar "https://meta.fabricmc.net/v2/versions/loader/$McVersion/$Fabric/profile/json" (Join-Path $CarpetaVersion "$VersionId.json")
	Decir "✔ Fabric $Fabric para Minecraft $McVersion"

	# --- 4. Carpeta de juego y mods -------------------------------------------
	$Juego = Join-Path $MC 'venatio'
	$CarpetaMods = Join-Path $Juego 'mods'
	New-Item -ItemType Directory -Force -Path $CarpetaMods, (Join-Path $Juego 'config') | Out-Null
	foreach ($mod in $Mods) {
		$destino = Join-Path $CarpetaMods $mod.Archivo
		if ((Test-Path $destino) -and ((Huella $destino) -eq $mod.Hash)) { Decir "✔ $($mod.Archivo) (ya estaba)"; continue }
		$temporal = Join-Path $Tmp $mod.Archivo
		Descargar $mod.Url $temporal
		if ((Huella $temporal) -ne $mod.Hash) { Fallar "El archivo $($mod.Archivo) llegó dañado (no coincide su huella). Vuelve a intentar." }
		Move-Item -Force $temporal $destino
		Decir "✔ $($mod.Archivo)"
	}
	$Conservar = $Mods | ForEach-Object { $_.Archivo }
	Get-ChildItem -Path $CarpetaMods -Filter '*.jar' | Where-Object { $Conservar -notcontains $_.Name } | ForEach-Object {
		Remove-Item -Force $_.FullName
		Decir "  (quitado $($_.Name): ya no es parte del paquete)"
	}

	# --- 5. Español y server en la lista ---------------------------------------
	$Opciones = Join-Path $Juego 'options.txt'
	$lineas = @()
	if (Test-Path $Opciones) { $lineas = @(Get-Content -Encoding UTF8 $Opciones | Where-Object { -not $_.StartsWith('lang:') }) }
	$lineas += 'lang:es_mx'
	[IO.File]::WriteAllLines($Opciones, [string[]]$lineas, $Utf8SinBom)
	if ($ServidorDir) {
		[IO.File]::WriteAllText((Join-Path $Juego 'config\venatio-servidor.txt'), "nombre=$ServidorNombre`ndireccion=$ServidorDir`n", $Utf8SinBom)
	}
	Decir "✔ Juego en español y server $ServidorNombre en la lista"

	# --- 6. Perfil en el launcher (oficial y, si existe, el de Microsoft Store) --
	# Memoria para Minecraft según la RAM de la computadora (de 3 a 6 GB), con los flags de G1 del launcher oficial.
	$RamGb = 0
	try { $RamGb = [math]::Floor((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB) } catch {}
	$Xmx = if ($RamGb -ge 16) { 6 } elseif ($RamGb -ge 12) { 5 } elseif ($RamGb -ge 8) { 4 } else { 3 }
	$JavaArgs = "-Xmx${Xmx}G -Xms1G -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M"
	$Fecha = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.000Z')
	foreach ($archivo in $ArchivosPerfiles) {
		Copy-Item -Force $archivo "$archivo.respaldo-venatio"
		$datos = [IO.File]::ReadAllText($archivo, [Text.Encoding]::UTF8) | ConvertFrom-Json
		if (-not $datos.profiles) { $datos | Add-Member -NotePropertyName profiles -NotePropertyValue ([pscustomobject]@{}) }
		$previo = $datos.profiles.$PerfilId
		$perfil = [pscustomobject]@{
			name          = 'Venatio'
			type          = 'custom'
			icon          = 'Crafting_Table'
			created       = if ($previo -and $previo.created) { $previo.created } else { $Fecha }
			lastUsed      = $Fecha
			lastVersionId = $VersionId
			gameDir       = $Juego
			javaArgs      = if ($previo -and $previo.javaArgs) { $previo.javaArgs } else { $JavaArgs }
		}
		$datos.profiles | Add-Member -Force -NotePropertyName $PerfilId -NotePropertyValue $perfil
		[IO.File]::WriteAllText($archivo, ($datos | ConvertTo-Json -Depth 32), $Utf8SinBom)
	}
	Decir '✔ Perfil "Venatio" en el launcher'

	Decir ''
	Decir '✅ Listo. Abre el launcher de Minecraft, elige el perfil "Venatio" y dale a Jugar.'
	Decir "La primera vez descarga Minecraft $McVersion y tarda un poco más."
}
finally {
	Remove-Item -Recurse -Force $Tmp -ErrorAction SilentlyContinue
}
