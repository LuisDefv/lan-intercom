package py.lan.intercom

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class IntercomApp : Application() {
    override fun onCreate() {
        super.onCreate()
        aplicarTema(Prefs(this).theme)
    }

    companion object {
        /** Modo oscuro por defecto; claro si el usuario lo elige. */
        fun aplicarTema(theme: String) {
            val mode = if (theme == "light") {
                AppCompatDelegate.MODE_NIGHT_NO
            } else {
                AppCompatDelegate.MODE_NIGHT_YES
            }
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
