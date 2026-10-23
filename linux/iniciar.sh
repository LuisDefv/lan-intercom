#!/usr/bin/env bash
# Intercom LAN — lanzador para Linux/macOS.
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v node >/dev/null 2>&1; then
    echo "Falta Node.js (>=18). Instalalo con el gestor de tu distro, p.ej.:" >&2
    echo "  Debian/Ubuntu:  sudo apt install nodejs" >&2
    echo "  Fedora:         sudo dnf install nodejs" >&2
    echo "  Arch:           sudo pacman -S nodejs" >&2
    exit 1
fi

# Variables opcionales (descomentá y ajustá):
# export INTERCOM_KEY="una-clave-compartida"     # misma clave en todas las PCs del grupo
# export INTERCOM_NAME="mi-linux"                # nombre visible en la red
# export INTERCOM_ALLOW_EXEC=1                   # permitir que otras PCs ejecuten comandos acá
# export INTERCOM_FILE_LIMIT=10                  # máx. archivos entrantes por PC
# export INTERCOM_FILE_WINDOW_MS=600000          # ventana del límite (10 min)

exec node server.js --abrir
