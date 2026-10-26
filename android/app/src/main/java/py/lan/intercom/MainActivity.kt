package py.lan.intercom

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import py.lan.intercom.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val adapter = MessageAdapter { abrirArchivo(it) }

    private var targetIds = listOf(IntercomService.TODOS)
    private var currentTarget = IntercomService.TODOS

    // Referencias de la pantalla de onboarding (para actualizar su estado).
    private var setupDialog: AlertDialog? = null
    private var setupFolderStatus: TextView? = null
    private var setupQrStatus: TextView? = null
    private var setupStart: Button? = null

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs.folderUri = uri.toString()
            Toast.makeText(this, "Carpeta elegida", Toast.LENGTH_SHORT).show()
            updateSetupUi()
            refresh()
        }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Permiso persistente de lectura: para poder abrir el archivo enviado más tarde desde el chat.
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            sendFileTo(uri)
        }
    }

    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val scanZxing = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { aplicarVinculo(it) }
    }

    /**
     * Android 15+ dibuja la app de borde a borde: sin esto la barra superior queda bajo el
     * notch/barra de estado y la caja de texto queda tapada por el teclado.
     */
    private fun aplicarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
    }

    /** Abre un archivo del chat con la app que corresponda (visor de PDF, galería, instalador de APK...). */
    private fun abrirArchivo(m: IntercomService.Msg) {
        val uri = Uri.parse(m.uri)
        val ext = m.fileName.substringAfterLast('.', "").lowercase()
        val mime = contentResolver.getType(uri)?.takeIf { it != "application/octet-stream" }
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Abrir ${m.fileName}"))
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        aplicarInsets()
        prefs = Prefs(this)

        b.messages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        b.messages.adapter = adapter

        b.send.setOnClickListener { enviarTexto() }
        b.attach.setOnClickListener { if (ensureReady()) pickFile.launch(arrayOf("*/*")) }
        b.settings.setOnClickListener { abrirAjustes() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (!setupComplete()) showSetup() else autoStart()
    }

    override fun onResume() {
        super.onResume()
        IntercomService.uiListener = { runOnUiThread { refresh() } }
        IntercomService.progressListener = { renderTransfers() }
        renderTransfers()
        if (setupComplete()) autoStart()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        IntercomService.uiListener = null
        IntercomService.progressListener = null
    }

    // --------------------------------------------------------------- onboarding

    private fun setupComplete(): Boolean = prefs.folderUri != null && prefs.paired

    private fun showSetup() {
        if (setupDialog?.isShowing == true) return
        val v = layoutInflater.inflate(R.layout.dialog_setup, null)
        setupFolderStatus = v.findViewById(R.id.folderStatus)
        setupQrStatus = v.findViewById(R.id.qrStatus)
        setupStart = v.findViewById(R.id.start)
        v.findViewById<Button>(R.id.pickFolder).setOnClickListener { pickFolder.launch(null) }
        v.findViewById<Button>(R.id.scanVertical).setOnClickListener { scanVertical() }
        v.findViewById<Button>(R.id.scanHorizontal).setOnClickListener { scanHorizontal() }
        v.findViewById<Button>(R.id.scanGoogle).setOnClickListener { scanGoogle() }
        v.findViewById<Button>(R.id.createGroup).setOnClickListener { crearGrupoPropio() }
        v.findViewById<Button>(R.id.exit).setOnClickListener { finishAffinity() }
        setupStart?.setOnClickListener {
            if (setupComplete()) {
                setupDialog?.dismiss()
                autoStart()
                refresh()
            }
        }
        setupDialog = AlertDialog.Builder(this)
            .setCancelable(false)
            .setView(v)
            .create()
        setupDialog?.show()
        updateSetupUi()
    }

    private fun updateSetupUi() {
        val folderOk = prefs.folderUri != null
        val qrOk = prefs.paired
        setupFolderStatus?.text = if (folderOk) "✓ ${folderLabel()}" else "✗ sin elegir"
        setupQrStatus?.text = if (qrOk) "✓ grupo ${grupoLabel()}" else "✗ sin vincular"
        setupStart?.isEnabled = folderOk && qrOk
    }

    // ------------------------------------------------------------------ acciones

    private fun enviarTexto() {
        val text = b.input.text.toString().trim()
        if (text.isEmpty()) return
        if (!ensureReady()) return
        IntercomService.instance?.sendText(currentTarget, text)
        b.input.setText("")
    }

    private fun sendFileTo(uri: Uri) {
        if (!ensureReady()) return
        IntercomService.instance?.sendFile(currentTarget, uri)
        Toast.makeText(this, "Enviando archivo…", Toast.LENGTH_SHORT).show()
    }

    private fun autoStart() {
        if (!IntercomService.running && prefs.folderUri != null) {
            ContextCompat.startForegroundService(this, Intent(this, IntercomService::class.java))
        }
    }

    /** Verifica que esté configurado; si no, abre el onboarding. */
    private fun ensureReady(): Boolean {
        if (!setupComplete()) {
            showSetup()
            return false
        }
        if (!IntercomService.running) autoStart()
        return true
    }

    // -------------------------------------------------------------------- ajustes

    private fun abrirAjustes() {
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        val name = v.findViewById<EditText>(R.id.name)
        val key = v.findViewById<EditText>(R.id.key)
        val folder = v.findViewById<TextView>(R.id.folder)
        val toggle = v.findViewById<Button>(R.id.toggle)
        val scanBtn = v.findViewById<Button>(R.id.scan)
        val pick = v.findViewById<Button>(R.id.pickFolder)
        val light = v.findViewById<MaterialSwitch>(R.id.lightMode)

        name.setText(prefs.name)
        key.setText(prefs.key)
        folder.text = "Carpeta: " + folderLabel()
        toggle.text = if (IntercomService.running) "Detener" else "Iniciar"
        light.isChecked = prefs.theme == "light"

        light.setOnCheckedChangeListener { _, checked ->
            prefs.theme = if (checked) "light" else "dark"
            IntercomApp.aplicarTema(prefs.theme)
            recreate()
        }
        pick.setOnClickListener { pickFolder.launch(null) }

        val dlg = AlertDialog.Builder(this)
            .setTitle("Ajustes")
            .setView(v)
            .setPositiveButton("Guardar") { _, _ ->
                prefs.name = name.text.toString().trim().ifEmpty { Build.MODEL ?: "android" }
                val nuevaClave = key.text.toString()
                val cambioGrupo = nuevaClave != prefs.key
                prefs.key = nuevaClave
                if (cambioGrupo && IntercomService.running) {
                    stopService(Intent(this, IntercomService::class.java))
                    b.root.postDelayed({ autoStart() }, 500)
                }
                refresh()
            }
            .setNegativeButton("Cerrar", null)
            .create()

        toggle.setOnClickListener {
            if (IntercomService.running) {
                stopService(Intent(this, IntercomService::class.java))
            } else {
                prefs.name = name.text.toString().trim().ifEmpty { Build.MODEL ?: "android" }
                prefs.key = key.text.toString()
                autoStart()
            }
            dlg.dismiss()
        }
        // Cerrar Ajustes antes de escanear: si no, "Guardar" pisaría la clave que trae el QR.
        scanBtn.setOnClickListener { dlg.dismiss(); scanVertical() }
        v.findViewById<Button>(R.id.scanHorizontal).setOnClickListener { dlg.dismiss(); scanHorizontal() }
        v.findViewById<Button>(R.id.scanGoogle).setOnClickListener { dlg.dismiss(); scanGoogle() }
        v.findViewById<Button>(R.id.myQr).setOnClickListener {
            // Toma el nombre/clave editados para que el QR los refleje.
            prefs.name = name.text.toString().trim().ifEmpty { Build.MODEL ?: "android" }
            prefs.key = key.text.toString()
            if (!IntercomService.running) autoStart()
            dlg.dismiss()
            b.root.postDelayed({ mostrarMiQr() }, 300)
        }
        dlg.show()
    }

    /** Empezar sin PC: crear un grupo propio y quedar listo para mostrar el QR. */
    private fun crearGrupoPropio() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            hint = "nombre del grupo (vacío = general)"
            setText(prefs.key)
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle("Crear mi grupo")
            .setMessage("Elegí una clave. Los demás entran con la misma clave o escaneando tu QR.")
            .setView(input)
            .setPositiveButton("Crear") { _, _ ->
                prefs.key = input.text.toString().trim()
                prefs.paired = true
                Toast.makeText(this, "Grupo listo. Tocá «Empezar» y después mostrá tu QR desde Ajustes.", Toast.LENGTH_LONG).show()
                updateSetupUi()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Muestra el QR de ESTE dispositivo para que otro celu lo escanee y entre al mismo grupo. */
    private fun mostrarMiQr() {
        if (!IntercomService.running) autoStart()
        val ip = IntercomService.ip
        if (ip.isEmpty() || ip == "?") {
            Toast.makeText(this, "Todavía no tengo IP. Conectate al WiFi y esperá unos segundos.", Toast.LENGTH_LONG).show()
            return
        }
        val payload = "intercom:pair?host=${Uri.encode(ip)}&port=${IntercomService.PORT}" +
            "&key=${Uri.encode(prefs.key)}&name=${Uri.encode(prefs.name)}"
        val px = (resources.displayMetrics.density * 260).toInt()
        val bmp = try {
            BarcodeEncoder().encodeBitmap(payload, BarcodeFormat.QR_CODE, px, px)
        } catch (e: Exception) {
            Toast.makeText(this, "No pude generar el QR: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            setBackgroundColor(Color.WHITE)
            setPadding(pad, pad, pad, pad)
        }
        val grupo = if (prefs.key.isEmpty()) "general" else "«${prefs.key}»"
        AlertDialog.Builder(this)
            .setTitle("Mi QR · grupo $grupo")
            .setMessage("Que el otro celu lo escanee (Vincular / Ajustes → escanear). Quedan en el mismo grupo y se ven para mandarse mensajes y archivos.")
            .setView(iv)
            .setPositiveButton("Listo", null)
            .show()
    }

    /** ZXing en vertical (actividad portrait). */
    private fun scanVertical() {
        scanZxing.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Escaneá el QR de la PC (vertical)")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .setCaptureActivity(PortraitCaptureActivity::class.java)
        )
    }

    /** ZXing horizontal (actividad por defecto, apaisada). */
    private fun scanHorizontal() {
        scanZxing.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Escaneá el QR de la PC (horizontal)")
                .setBeepEnabled(false)
                .setOrientationLocked(true)
        )
    }

    /** Escáner de Google (ML Kit Code Scanner), vertical nativo. */
    private fun scanGoogle() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { bc ->
                val raw = bc.rawValue
                if (raw.isNullOrEmpty()) {
                    Toast.makeText(this, "El QR vino vacío", Toast.LENGTH_SHORT).show()
                } else {
                    aplicarVinculo(raw)
                }
            }
            .addOnCanceledListener { }
            .addOnFailureListener { e ->
                Toast.makeText(this, "No se pudo abrir el escáner de Google: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    /** payload: intercom:pair?host=&port=&key=&name= */
    private fun aplicarVinculo(data: String) {
        val q = data.substringAfter('?', "")
        if (q.isEmpty()) {
            Toast.makeText(this, "QR no reconocido", Toast.LENGTH_SHORT).show()
            return
        }
        val params = q.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else Uri.decode(it.substring(0, i)) to Uri.decode(it.substring(i + 1))
        }.toMap()
        val cambioGrupo = params["key"] != null && params["key"] != prefs.key
        params["key"]?.let { prefs.key = it }
        prefs.paired = true
        val pcName = params["name"] ?: "la PC"
        val host = params["host"]
        if (!host.isNullOrBlank()) {
            prefs.pcHost = host
            prefs.pcPort = params["port"]?.toIntOrNull() ?: 7777
        }
        val grupoTxt = if (prefs.key.isEmpty()) "grupo general (sin clave)" else "grupo «${prefs.key}»"
        val detalle = if (host != null) "Compartido por la PC: $pcName ($host)" else "Compartido por la PC: $pcName"

        if (cambioGrupo && IntercomService.running) {
            stopService(Intent(this, IntercomService::class.java))
            b.root.postDelayed({ autoStart() }, 500)
        } else {
            autoStart()
        }
        updateSetupUi()
        refresh()

        AlertDialog.Builder(this)
            .setTitle("Vinculado ✓")
            .setMessage("Quedaste en el $grupoTxt.\n$detalle\n\nTodas las máquinas con la misma clave se ven entre sí. Ya podés mandar y recibir.")
            .setPositiveButton("Listo", null)
            .show()
    }

    // --------------------------------------------------------------------- render

    private fun grupoLabel(): String = if (prefs.key.isEmpty()) "general" else "«${prefs.key}»"

    private fun refresh() {
        val base = if (IntercomService.running) "Activo · ${IntercomService.ip}" else "Detenido · tocá ⚙"
        val cuantas = IntercomService.peers.size
        b.status.text = "$base · grupo ${grupoLabel()} · $cuantas en línea"
        refreshSpinner()
        adapter.submit(synchronized(IntercomService.messages) { IntercomService.messages.toList() })
        if (adapter.itemCount > 0) b.messages.scrollToPosition(adapter.itemCount - 1)
    }

    /** Loader de transferencias: barra + % + velocidad de subida/bajada. */
    private fun renderTransfers() {
        val list = IntercomService.transfers.values.toList()
        if (list.isEmpty()) {
            b.transferBox.visibility = android.view.View.GONE
            return
        }
        b.transferBox.visibility = android.view.View.VISIBLE
        val t = list.first()
        val fmt = IntercomService::formatSize
        val flecha = if (t.up) "⬆" else "⬇"
        val pct = if (t.percent >= 0) "${t.percent}%" else ""
        val restante = if (t.speed > 0 && t.total > 0) {
            val s = ((t.total - t.done) / t.speed).toLong()
            " · faltan " + if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"
        } else ""
        b.transferTitle.text = "$flecha ${t.name} · ${t.peer}" + if (list.size > 1) "  (+${list.size - 1})" else ""
        b.transferDetail.text = "$pct  ${fmt(t.done.toDouble())} / ${fmt(t.total.toDouble())} · ${fmt(t.speed)}/s$restante"
        b.transferBar.isIndeterminate = t.percent < 0
        if (t.percent >= 0) b.transferBar.setProgressCompat(t.percent, true)
        val up = list.filter { it.up }.sumOf { it.speed }
        val down = list.filter { !it.up }.sumOf { it.speed }
        b.transferSpeeds.text = "⬆ ${fmt(up)}/s   ⬇ ${fmt(down)}/s"
    }

    private fun refreshSpinner() {
        val peers = IntercomService.peers
        val ids = ArrayList<String>().apply { add(IntercomService.TODOS); addAll(peers.map { it.id }) }
        val labels = ArrayList<String>().apply { add("Todos"); addAll(peers.map { it.name }) }
        if (ids == targetIds) return
        targetIds = ids
        val sp = b.peerSpinner
        val aa = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        aa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = aa
        val keep = targetIds.indexOf(currentTarget).let { if (it >= 0) it else 0 }
        sp.setSelection(keep)
        sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                currentTarget = targetIds.getOrElse(position) { IntercomService.TODOS }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun folderLabel(): String {
        val uriStr = prefs.folderUri ?: return "(sin elegir)"
        return try {
            DocumentFile.fromTreeUri(this, Uri.parse(uriStr))?.name ?: uriStr
        } catch (_: Exception) {
            uriStr
        }
    }
}
