# Intercom LAN — cliente Android (Kotlin)

App nativa en Kotlin que habla el **mismo protocolo** que el Intercom de PC
(carpeta raíz del repo). Sirve para que una PC te **mande archivos/APKs al
celular por la red local**, y el celular los guarda en **la carpeta que vos
elijas**.

## Cómo funciona

- El celular corre un servicio en primer plano con un servidor HTTP en el
  puerto **7777** (mismo que la versión PC) y se **anuncia por broadcast UDP**
  (puerto 7778), así aparece solo en la lista de PCs del Intercom de escritorio.
- Cuando una PC te manda un archivo (`POST /inbox/file`), el celular lo escribe
  en la carpeta elegida vía **Storage Access Framework** (no necesita permiso de
  almacenamiento total).
- Si ponés una **clave de grupo**, tiene que ser la misma que en las PCs
  (`INTERCOM_KEY`); separa tu red de otras.

## Uso

1. Abrí la app, tocá **"Elegir carpeta donde guardar"** y elegí la carpeta
   (ej. `Download/Intercom`).
2. Poné un **nombre** para el celu y, si usás clave, la **clave del grupo**.
3. Tocá **Iniciar**. Queda activo en segundo plano (notificación fija).
4. En la PC, abrí el Intercom: el celular aparece como una PC más. Seleccionalo
   y mandale el APK/archivo. Llega a la carpeta elegida.
5. Para instalar un APK recibido, abrilo desde un explorador de archivos (el
   sistema pide confirmación de "instalar apps desconocidas").

## Compilar

- Abrí la carpeta `android/` en **Android Studio** (deja el Gradle wrapper listo)
  y dale **Run**, o generá el APK con *Build > Build APK(s)*.
- Por consola, con el wrapper generado: `./gradlew assembleDebug`
  (el APK queda en `app/build/outputs/apk/debug/`).

Requisitos: Android Studio reciente, `minSdk 24`, `compileSdk 35`.

## Permisos

- Red (INTERNET, estado de WiFi, multicast para el descubrimiento).
- Servicio en primer plano + notificación (Android 13+ pide permiso de notif).
- `REQUEST_INSTALL_PACKAGES` solo para poder abrir/instalar un APK recibido.
