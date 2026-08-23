package com.anthony.uberviajes

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Captura cualquier excepción no controlada, la guarda en SharedPreferences
 * y luego deja que el sistema mate el proceso normalmente. La próxima vez
 * que se abra la app, MainActivity revisa si hay un crash guardado y lo
 * muestra en pantalla (útil quiero depurar desde el celular, sin PC/ADB).
 */
class CrashHandler(private val appContext: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            appContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_CRASH, sw.toString())
                .apply()
        } catch (_: Exception) {
            // Si guardar el crash también falla, no hay mucho más que hacer;
            // dejamos que el manejador por defecto siga su curso normal.
        }
        defaultHandler?.uncaughtException(thread, throwable)
    }

    companion object {
        const val PREFS_NAME = "crash_prefs"
        const val KEY_LAST_CRASH = "last_crash"
    }
}
