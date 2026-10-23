# Intercom LAN — fork para Linux / macOS

Mismo Intercom que la versión Windows (mensajes, archivos, descubrimiento por
broadcast UDP, interfaz web y servidor MCP), con lanzador y servicio para Linux.
El código Node es idéntico y multiplataforma: lo único propio de cada SO es cómo
se arranca y qué shell usa la ejecución remota (`/bin/sh` o `bash` en Linux,
`cmd`/`powershell` en Windows).

## Requisitos

- Node.js >= 18 (`node -v`).

## Arranque rápido

```bash
chmod +x iniciar.sh
./iniciar.sh
```

Abre la interfaz en <http://localhost:7777>. Las otras PCs de la misma red que
tengan el Intercom abierto aparecen solas.

## Como servicio (systemd, arranca con la sesión)

```bash
mkdir -p ~/lan-intercom-linux && cp -r ./* ~/lan-intercom-linux/
mkdir -p ~/.config/systemd/user
cp lan-intercom.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now lan-intercom
systemctl --user status lan-intercom
```

## Variables de entorno

| Variable | Para qué | Default |
|---|---|---|
| `INTERCOM_KEY` | Clave compartida del grupo (misma en todas las PCs). Separa redes. | (vacía) |
| `INTERCOM_NAME` | Nombre visible en la red. | hostname |
| `INTERCOM_PORT` | Puerto HTTP. | 7777 |
| `INTERCOM_DISCOVERY_PORT` | Puerto UDP de descubrimiento. | 7778 |
| `INTERCOM_DIR` | Carpeta de datos (recibidos, historial). | `~/Intercom` |
| `INTERCOM_ALLOW_EXEC` | Permitir que otras PCs ejecuten comandos acá (`1`). Igual se prende/apaga desde la UI y **siempre** exige `INTERCOM_KEY`. | apagado |
| `INTERCOM_FILE_LIMIT` | Máximo de archivos entrantes por PC en la ventana. | 10 |
| `INTERCOM_FILE_WINDOW_MS` | Ventana del límite, en ms. | 600000 (10 min) |

## Novedades de esta versión

- **Ejecución remota opcional y con consentimiento**: apagada por defecto; se
  activa desde la interfaz (o `INTERCOM_ALLOW_EXEC=1`), exige `INTERCOM_KEY`, y
  todo lo ejecutado queda en el historial. Se puede permitir globalmente o por PC.
- **Archivos entrantes con confirmación**: no se guardan directo; quedan *en
  espera* y los aceptás o rechazás desde la interfaz.
- **Rate limit**: máximo 10 archivos por PC cada 10 minutos (configurable).
- **Alias por PC**: nombre propio para cada equipo.

## MCP

`node mcp.js` expone las herramientas al agente: `listar_pcs`, `enviar_mensaje`,
`enviar_archivo`, `leer_mensajes`, `listar_archivos_recibidos`,
`leer_archivo_recibido`, `agregar_pc`, `ejecutar`, `permitir_ejecucion`,
`ver_pendientes`, `resolver_pendiente`, `poner_alias`.
