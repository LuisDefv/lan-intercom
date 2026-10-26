package py.lan.intercom

import android.content.Context
import java.util.UUID

/** Preferencias simples persistidas en SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("intercom", Context.MODE_PRIVATE)

    var name: String
        get() = sp.getString("name", android.os.Build.MODEL ?: "android") ?: "android"
        set(v) = sp.edit().putString("name", v).apply()

    var key: String
        get() = sp.getString("key", "") ?: ""
        set(v) = sp.edit().putString("key", v).apply()

    /** Uri (como String) del árbol de carpeta elegido por el usuario (SAF). */
    var folderUri: String?
        get() = sp.getString("folderUri", null)
        set(v) = sp.edit().putString("folderUri", v).apply()

    /** Tema: "dark" (por defecto) o "light". */
    var theme: String
        get() = sp.getString("theme", "dark") ?: "dark"
        set(v) = sp.edit().putString("theme", v).apply()

    /** true cuando el usuario ya escaneó un QR de vinculación al menos una vez. */
    var paired: Boolean
        get() = sp.getBoolean("paired", false)
        set(v) = sp.edit().putBoolean("paired", v).apply()

    /** IP/puerto de la PC del último QR escaneado: se le habla directo, sin depender del broadcast. */
    var pcHost: String?
        get() = sp.getString("pcHost", null)
        set(v) = sp.edit().putString("pcHost", v).apply()

    var pcPort: Int
        get() = sp.getInt("pcPort", 7777)
        set(v) = sp.edit().putInt("pcPort", v).apply()

    /** Id estable de este dispositivo en la red. */
    val id: String
        get() {
            var v = sp.getString("id", null)
            if (v == null) {
                v = UUID.randomUUID().toString()
                sp.edit().putString("id", v).apply()
            }
            return v
        }
}
