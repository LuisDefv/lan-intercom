package py.lan.intercom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Servicio en primer plano. Habla el mismo protocolo que el Intercom de PC:
 * - responde /ping, recibe /inbox/message y /inbox/file,
 * - se anuncia por broadcast UDP y escucha anuncios de otras máquinas,
 * - manda texto y archivos a los peers.
 * Pensado para que convivan muchas PCs y celulares: los peers se deduplican por
 * su `id` y el historial no se repite.
 */
class IntercomService : Service() {

    private val pool = Executors.newCachedThreadPool()
    private lateinit var prefs: Prefs
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var udpSocket: DatagramSocket? = null
    @Volatile private var alive = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (alive) return START_STICKY

        startForegroundNotif()
        alive = true
        running = true
        ip = localIp()
        acquireMulticast()
        pool.execute { runHttpServer() }
        pool.execute { runDiscovery() }
        scheduleAnnounce()
        schedulePeerSweep()
        notifyUi()
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        running = false
        main.removeCallbacksAndMessages(null)
        try { serverSocket?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        try { multicastLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        pool.shutdownNow()
        peersMap.clear()
        peers = emptyList()
        instance = null
        notifyUi()
        super.onDestroy()
    }

    // ------------------------------------------------------------- notificación

    private fun startForegroundNotif() {
        val chanId = "intercom"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(chanId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(chanId, "Intercom LAN", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = NotificationCompat.Builder(this, chanId)
            .setContentTitle("Intercom LAN activo")
            .setContentText("Conectado a la red local")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notif)
        }
    }

    // ----------------------------------------------------------- transferencias

    private var transferSeq = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Registra una transferencia y mantiene CPU y WiFi despiertos mientras dure (pantalla apagada incluida). */
    private fun transferStart(up: Boolean, name: String, peer: String, total: Long): Long {
        val id: Long
        synchronized(transfers) {
            id = ++transferSeq
            transfers[id] = Transfer(up, name, peer, total)
            if (transfers.size == 1) acquireTransferLocks()
        }
        notifyProgress(true)
        return id
    }

    private fun transferBytes(id: Long, n: Int) {
        val t = transfers[id] ?: return
        t.done += n
        val now = System.currentTimeMillis()
        val dt = now - t.lastT
        if (dt >= 500) {
            val inst = (t.done - t.lastB) * 1000.0 / dt
            t.speed = if (t.speed == 0.0) inst else t.speed * 0.6 + inst * 0.4
            t.lastT = now
            t.lastB = t.done
            notifyProgress()
            updateTransferNotif()
        }
    }

    private fun transferEnd(id: Long) {
        synchronized(transfers) {
            transfers.remove(id)
            if (transfers.isEmpty()) releaseTransferLocks()
        }
        notifyProgress(true)
        updateTransferNotif()
    }

    private fun acquireTransferLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "intercom:transfer").apply {
                setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {}
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "intercom:transfer").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseTransferLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null; wifiLock = null
    }

    /** La notificación fija muestra el progreso; vuelve a "activo" cuando no hay transferencias. */
    private fun updateTransferNotif() {
        try {
            val list = transfers.values.toList()
            val b = NotificationCompat.Builder(this, "intercom")
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
            if (list.isEmpty()) {
                b.setContentTitle("Intercom LAN activo").setContentText("Conectado a la red local")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
            } else {
                val t = list.first()
                val flecha = if (t.up) "⬆ Enviando" else "⬇ Recibiendo"
                b.setSmallIcon(if (t.up) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
                    .setContentTitle("$flecha ${t.name}" + if (list.size > 1) " (+${list.size - 1})" else "")
                    .setContentText("${formatSize(t.done.toDouble())} de ${formatSize(t.total.toDouble())} · ${formatSize(t.speed)}/s")
                    .setProgress(100, t.percent.coerceAtLeast(0), t.percent < 0)
            }
            NotificationManagerCompat.from(this).notify(1, b.build())
        } catch (_: SecurityException) {}
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun acquireMulticast() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("intercom").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }

    // --------------------------------------------------------------- HTTP server

    private fun grupo(): String {
        val h = MessageDigest.getInstance("SHA-256").digest(prefs.key.toByteArray())
        return h.joinToString("") { "%02x".format(it) }.substring(0, 12)
    }

    private fun runHttpServer() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(PORT))
            serverSocket = ss
            while (alive) {
                val client = ss.accept()
                pool.execute { handle(client) }
            }
        } catch (_: Exception) {
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            try {
                val input = socket.getInputStream()
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val rawPath = parts[1]
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val out = socket.getOutputStream()
                val path = rawPath.substringBefore('?')
                val query = parseQuery(rawPath.substringAfter('?', ""))

                if (method == "GET" && path == "/ping") {
                    val body = JSONObject()
                        .put("app", APP).put("id", prefs.id).put("name", prefs.name)
                        .put("port", PORT).put("grupo", grupo())
                    return respondJson(out, 200, body.toString())
                }

                if (path.startsWith("/inbox/")) {
                    if (method != "POST") return respondJson(out, 405, errJson("método no permitido"))
                    if (!keyOk(headers)) return respondJson(out, 403, errJson("clave incorrecta"))
                    val len = headers["content-length"]?.toLongOrNull() ?: -1L
                    val host = socket.inetAddress?.hostAddress ?: "?"

                    if (path == "/inbox/message") {
                        val raw = readN(input, len)
                        val msg = try { JSONObject(String(raw, Charsets.UTF_8)) } catch (_: Exception) { JSONObject() }
                        val fromId = msg.optString("fromId", host)
                        val fromName = msg.optString("fromName", host)
                        upsertPeer(fromId, fromName, host, msg.optInt("fromPort", PORT))
                        addMessage(Msg("in", "text", fromId, fromName, text = msg.optString("text", "")))
                        return respondJson(out, 200, "{\"ok\":true}")
                    }

                    if (path == "/inbox/file") {
                        val fromId = query["fromId"] ?: host
                        val fromName = query["fromName"] ?: host
                        upsertPeer(fromId, fromName, host, query["fromPort"]?.toIntOrNull() ?: PORT)
                        val name = safeName(query["name"] ?: "archivo")
                        val doc = saveIncoming(name, input, len, fromName)
                            ?: return respondJson(out, 500, errJson("no hay carpeta elegida o no se pudo guardar"))
                        val saved = doc.name ?: name
                        addMessage(Msg("in", "file", fromId, fromName, fileName = saved, uri = doc.uri.toString()))
                        return respondJson(out, 200, JSONObject().put("ok", true).put("fileName", saved).toString())
                    }
                    return respondJson(out, 404, errJson("no encontrado"))
                }

                respondJson(out, 404, errJson("no encontrado"))
            } catch (_: Exception) {
            }
        }
    }

    private fun keyOk(headers: Map<String, String>): Boolean {
        if (prefs.key.isEmpty()) return true
        return headers["x-intercom-key"] == prefs.key
    }

    private fun saveIncoming(name: String, input: InputStream, len: Long, fromName: String): DocumentFile? {
        val uriStr = prefs.folderUri ?: return null
        val tree = DocumentFile.fromTreeUri(this, Uri.parse(uriStr)) ?: return null
        if (!tree.canWrite()) return null
        val mime = if (name.lowercase().endsWith(".apk")) {
            "application/vnd.android.package-archive"
        } else {
            "application/octet-stream"
        }
        val doc = tree.createFile(mime, name) ?: return null
        val out: OutputStream = contentResolver.openOutputStream(doc.uri) ?: return null
        val tid = transferStart(false, name, fromName, len)
        try {
            BufferedOutputStream(out, 256 * 1024).use { bo ->
                val buf = ByteArray(256 * 1024)
                var remaining = len
                while (len < 0 || remaining > 0) {
                    val toRead = if (len < 0 || remaining > buf.size) buf.size else remaining.toInt()
                    val r = input.read(buf, 0, toRead)
                    if (r < 0) break
                    bo.write(buf, 0, r)
                    transferBytes(tid, r)
                    if (len >= 0) remaining -= r
                }
                bo.flush()
            }
            // Llegó incompleto (se cortó la conexión): no dejar un archivo roto en la carpeta.
            if (len >= 0 && transfers[tid]?.done != len) {
                try { doc.delete() } catch (_: Exception) {}
                return null
            }
        } finally {
            transferEnd(tid)
        }
        return doc
    }

    // ---------------------------------------------------------------- enviar

    /** Manda texto a un peer (por id) o a "todos". Corre en segundo plano. */
    fun sendText(toId: String, text: String) {
        pool.execute {
            val targets = resolveTargets(toId)
            if (targets.isEmpty()) {
                addMessage(Msg("out", "text", toId, "nadie", text = text, ok = false, error = "no hay PCs conectadas"))
                return@execute
            }
            val errors = targets.mapNotNull { p -> postMessage(p, text)?.let { "${p.name}: $it" } }
            val ok = errors.size < targets.size
            addMessage(Msg("out", "text", toId, labelFor(toId, targets), text = text, ok = ok, error = errors.joinToString("; ")))
        }
    }

    /** Manda un archivo (content Uri) a un peer o a "todos". */
    fun sendFile(toId: String, uri: Uri) {
        pool.execute {
            val targets = resolveTargets(toId)
            val name = displayNameOf(uri)
            if (targets.isEmpty()) {
                addMessage(Msg("out", "file", toId, "nadie", fileName = name, ok = false, error = "no hay PCs conectadas"))
                return@execute
            }
            val errors = targets.mapNotNull { p -> postFile(p, uri, name)?.let { "${p.name}: $it" } }
            val ok = errors.size < targets.size
            addMessage(Msg("out", "file", toId, labelFor(toId, targets), fileName = name, ok = ok, error = errors.joinToString("; "), uri = uri.toString()))
        }
    }

    /** Devuelve null si salió bien, o el motivo del error. */
    private fun postMessage(p: Peer, text: String): String? {
        return try {
            val url = URL("http://${p.host}:${p.port}/inbox/message")
            val c = url.openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            if (prefs.key.isNotEmpty()) c.setRequestProperty("x-intercom-key", prefs.key)
            val body = JSONObject()
                .put("fromId", prefs.id).put("fromName", prefs.name).put("fromPort", PORT)
                .put("text", text).toString().toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            responseError(c)
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /** Lee la respuesta: null si fue 2xx, si no el "error" del JSON o el código HTTP. */
    private fun responseError(c: HttpURLConnection): String? {
        try {
            val code = c.responseCode
            if (code in 200..299) {
                c.inputStream.use { it.readBytes() }
                return null
            }
            val body = c.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            val msg = try { JSONObject(body).optString("error") } catch (_: Exception) { "" }
            return msg.ifEmpty { "HTTP $code" }
        } finally {
            c.disconnect()
        }
    }

    /** Devuelve null si salió bien, o el motivo del error. */
    private fun postFile(p: Peer, uri: Uri, name: String): String? {
        return try {
            val size = sizeOf(uri)
            val url = URL("http://${p.host}:${p.port}/inbox/file?name=${Uri.encode(name)}&fromId=${Uri.encode(prefs.id)}&fromName=${Uri.encode(prefs.name)}&fromPort=$PORT")
            val c = url.openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 600000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/octet-stream")
            if (prefs.key.isNotEmpty()) c.setRequestProperty("x-intercom-key", prefs.key)
            if (size >= 0) c.setFixedLengthStreamingMode(size) else c.setChunkedStreamingMode(0)
            val tid = transferStart(true, name, p.name, size)
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    c.outputStream.use { o ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val r = input.read(buf)
                            if (r < 0) break
                            o.write(buf, 0, r)
                            transferBytes(tid, r)
                        }
                    }
                } ?: return "no se pudo leer el archivo"
                responseError(c)
            } finally {
                transferEnd(tid)
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    private fun sizeOf(uri: Uri): Long {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cur ->
                val idx = cur.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && cur.moveToFirst() && !cur.isNull(idx)) return cur.getLong(idx)
            }
        } catch (_: Exception) {}
        return -1
    }

    private fun displayNameOf(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cur.moveToFirst() && !cur.isNull(idx)) return safeName(cur.getString(idx))
            }
        } catch (_: Exception) {}
        return safeName(uri.lastPathSegment ?: "archivo")
    }

    private fun resolveTargets(toId: String): List<Peer> {
        val all = synchronized(peersMap) { peersMap.values.toList() }
        return if (toId == TODOS) all else all.filter { it.id == toId }
    }

    private fun labelFor(toId: String, targets: List<Peer>): String {
        return if (toId == TODOS) "Todos" else targets.firstOrNull()?.name ?: "PC"
    }

    // --------------------------------------------------------- descubrimiento UDP

    private fun runDiscovery() {
        try {
            val sock = DatagramSocket(null)
            sock.reuseAddress = true
            sock.broadcast = true
            sock.bind(InetSocketAddress(DISCOVERY_PORT))
            udpSocket = sock
            val buf = ByteArray(4096)
            while (alive) {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                try {
                    val m = JSONObject(String(pkt.data, 0, pkt.length, Charsets.UTF_8))
                    if (m.optString("app") != APP) continue
                    val id = m.optString("id")
                    if (id.isEmpty() || id == prefs.id) continue
                    if (m.optString("grupo") != grupo()) continue
                    upsertPeer(id, m.optString("name", pkt.address.hostAddress ?: "PC"), pkt.address.hostAddress ?: "?", m.optInt("port", PORT))
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        }
    }

    private fun scheduleAnnounce() {
        if (!alive) return
        pool.execute { announce() }
        main.postDelayed({ scheduleAnnounce() }, 3000)
    }

    private fun announce() {
        try {
            val sock = udpSocket ?: return
            val msg = JSONObject()
                .put("app", APP).put("id", prefs.id).put("name", prefs.name)
                .put("port", PORT).put("grupo", grupo())
                .put("grupoNombre", prefs.key.ifEmpty { "general" })
                .toString().toByteArray(Charsets.UTF_8)
            // 255.255.255.255 + broadcast de cada subred: algunos routers/Android solo dejan pasar uno de los dos.
            val dests = LinkedHashSet<InetAddress>()
            dests.add(InetAddress.getByName("255.255.255.255"))
            try {
                for (ni in java.net.NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (ia in ni.interfaceAddresses) ia.broadcast?.let { dests.add(it) }
                }
            } catch (_: Exception) {}
            // La PC del QR, por unicast: anda aunque el router bloquee el broadcast entre WiFi.
            prefs.pcHost?.let { h -> try { dests.add(InetAddress.getByName(h)) } catch (_: Exception) {} }
            for (d in dests) {
                try { sock.send(DatagramPacket(msg, msg.size, d, DISCOVERY_PORT)) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        pingPairedPc()
    }

    /** Pregunta /ping directo a la PC del QR y la agrega como peer si está en el mismo grupo. */
    private fun pingPairedPc() {
        val host = prefs.pcHost ?: return
        val port = prefs.pcPort
        try {
            val c = URL("http://$host:$port/ping").openConnection() as HttpURLConnection
            c.connectTimeout = 2500
            c.readTimeout = 2500
            try {
                if (c.responseCode != 200) return
                val m = JSONObject(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
                val id = m.optString("id")
                if (m.optString("app") != APP || id.isEmpty() || id == prefs.id) return
                if (m.optString("grupo") != grupo()) return
                upsertPeer(id, m.optString("name", host), host, m.optInt("port", port))
            } finally {
                c.disconnect()
            }
        } catch (_: Exception) {}
    }

    private fun schedulePeerSweep() {
        if (!alive) return
        val now = System.currentTimeMillis()
        var changed = false
        synchronized(peersMap) {
            val it = peersMap.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value.lastSeen > PEER_TTL_MS) { it.remove(); changed = true }
            }
        }
        if (changed) { publishPeers(); notifyUi() }
        main.postDelayed({ schedulePeerSweep() }, 2000)
    }

    // -------------------------------------------------------------- estado/peers

    /** Inserta o actualiza un peer por id (dedup). Nunca duplica. */
    private fun upsertPeer(id: String, name: String, host: String, port: Int) {
        if (id.isEmpty() || id == prefs.id) return
        val prev = peersMap[id]
        peersMap[id] = Peer(id, name.take(60), host, port, System.currentTimeMillis())
        if (prev == null || prev.name != name || prev.host != host) {
            publishPeers(); notifyUi()
        }
    }

    private fun publishPeers() {
        val list = synchronized(peersMap) { peersMap.values.sortedBy { it.name.lowercase() } }
        peers = list
    }

    private fun addMessage(m: Msg) {
        synchronized(messages) {
            messages.add(m)
            while (messages.size > 500) messages.removeAt(0)
        }
        notifyUi()
    }

    // ------------------------------------------------------------------ util HTTP

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var c = input.read()
        if (c == -1) return null
        while (c != -1) {
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            c = input.read()
        }
        return sb.toString()
    }

    private fun readN(input: InputStream, len: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var remaining = len
        while (len < 0 || remaining > 0) {
            val toRead = if (len < 0 || remaining > buf.size) buf.size else remaining.toInt()
            val r = input.read(buf, 0, toRead)
            if (r < 0) break
            out.write(buf, 0, r)
            if (len >= 0) remaining -= r
        }
        return out.toByteArray()
    }

    private fun respondJson(out: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status ${statusText(status)}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun statusText(s: Int) = when (s) {
        200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error"
    }

    private fun errJson(m: String) = JSONObject().put("error", m).toString()

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        return q.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else Uri.decode(it.substring(0, i)) to Uri.decode(it.substring(i + 1))
        }.toMap()
    }

    private fun safeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[<>:\"|?*\\x00-\\x1f]"), "_").trim().take(180)
        return base.ifEmpty { "archivo" }
    }

    private fun localIp(): String {
        try {
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address && addr.isSiteLocalAddress) {
                        return addr.hostAddress ?: "?"
                    }
                }
            }
        } catch (_: Exception) {}
        return "?"
    }

    data class Peer(val id: String, val name: String, val host: String, val port: Int, val lastSeen: Long)

    data class Msg(
        val dir: String,            // "in" | "out"
        val kind: String,           // "text" | "file"
        val peerId: String,
        val peerName: String,
        val text: String = "",
        val fileName: String = "",
        val ok: Boolean = true,
        val error: String = "",
        val uri: String = "",       // content:// del archivo (recibido o enviado), para abrirlo
        val at: Long = System.currentTimeMillis(),
    )

    companion object {
        const val APP = "lan-intercom"
        const val PORT = 7777
        const val DISCOVERY_PORT = 7778
        const val PEER_TTL_MS = 12_000L
        const val ACTION_STOP = "py.lan.intercom.STOP"
        const val TODOS = "todos"

        @Volatile var running = false
        @Volatile var ip = "?"

        // Mapa interno (dedup por id) y snapshots que lee la UI.
        private val peersMap = ConcurrentHashMap<String, Peer>()
        @Volatile var peers: List<Peer> = emptyList()
        val messages: MutableList<Msg> = Collections.synchronizedList(mutableListOf())

        /** Transferencia en curso (subida o bajada), para el loader y la velocidad. */
        class Transfer(val up: Boolean, val name: String, val peer: String, val total: Long) {
            @Volatile var done = 0L
            @Volatile var speed = 0.0          // bytes/s, suavizado
            internal var lastT = System.currentTimeMillis()
            internal var lastB = 0L
            val percent: Int get() = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
        }
        val transfers: MutableMap<Long, Transfer> = ConcurrentHashMap()

        @Volatile var progressListener: (() -> Unit)? = null
        @Volatile private var lastProgressPost = 0L
        fun notifyProgress(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastProgressPost < 250) return
            lastProgressPost = now
            val l = progressListener ?: return
            Handler(Looper.getMainLooper()).post(l)
        }

        fun formatSize(b: Double): String = when {
            b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30))
            b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20))
            b >= 1L shl 10 -> "%.0f KB".format(b / (1L shl 10))
            else -> "%.0f B".format(b)
        }

        @Volatile var uiListener: (() -> Unit)? = null
        fun notifyUi() {
            val l = uiListener ?: return
            Handler(Looper.getMainLooper()).post(l)
        }

        /** Para que la UI mande sin bindear el servicio: referencia viva. */
        @Volatile var instance: IntercomService? = null
    }
}
