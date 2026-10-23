# Intercom LAN

Intercomunicador entre PCs (y el celular) de la **misma red local**: mandar
**mensajes** y **archivos** al instante, con interfaz web, sin servicios en la
nube y sin dependencias externas. Las PCs se descubren solas por broadcast UDP.

> Proyecto personal / hobby. Hecho para pasarnos cosas entre máquinas de la
> oficina o de casa sin subir nada a internet.

## Qué hay en el repo

| Carpeta | Qué es |
|---|---|
| raíz (`server.js`, `mcp.js`, `public/`) | Versión **Windows** (Node.js). `iniciar.bat` para arrancar. |
| `linux/` | **Fork Linux/macOS**: mismo código + `iniciar.sh` y servicio `systemd`. |
| `android/` | **Cliente Android (Kotlin)** para recibir archivos/APKs en el celular y guardarlos en la carpeta que elijas. |

## Arranque (Windows)

```bat
node server.js --abrir
```

Abre <http://localhost:7777>. Las otras PCs con el Intercom abierto aparecen
solas; si no, agregás una por IP.

## Funciones

- **Mensajes y archivos** entre PCs y el celular, en vivo (SSE).
- **Descubrimiento automático** por UDP + alta manual por IP.
- **Recepción con confirmación**: los archivos entrantes quedan *en espera* y se
  aceptan o rechazan desde la interfaz.
- **Rate limiter**: máximo 10 archivos por PC cada 10 minutos (configurable).
- **Alias por PC**: nombre propio para cada equipo.
- **Ejecución remota opcional y con consentimiento**: apagada por defecto; se
  activa desde la interfaz (o `INTERCOM_ALLOW_EXEC=1`), exige la clave compartida
  (`INTERCOM_KEY`) y registra todo. Se permite global o por PC.
- **Servidor MCP** (`mcp.js`) para manejar todo desde un agente.

## Clave de grupo

Si definís `INTERCOM_KEY` (la misma en todas las máquinas), solo se ven y hablan
entre sí las que comparten esa clave. Sin clave, grupo por defecto.

## Variables de entorno

| Variable | Default | Para qué |
|---|---|---|
| `INTERCOM_KEY` | (vacía) | Clave compartida del grupo. |
| `INTERCOM_NAME` | hostname | Nombre visible en la red. |
| `INTERCOM_PORT` | 7777 | Puerto HTTP. |
| `INTERCOM_DISCOVERY_PORT` | 7778 | Puerto UDP de descubrimiento. |
| `INTERCOM_DIR` | `~/Intercom` | Carpeta de datos y recibidos. |
| `INTERCOM_ALLOW_EXEC` | apagado | Permitir ejecución remota al arrancar. |
| `INTERCOM_FILE_LIMIT` | 10 | Máx. archivos entrantes por PC en la ventana. |
| `INTERCOM_FILE_WINDOW_MS` | 600000 | Ventana del límite (10 min). |

## Seguridad

Pensado para una **red local de confianza**. La ejecución remota viene **apagada**
y, aun activada, exige la clave compartida. No lo expongas a internet.
