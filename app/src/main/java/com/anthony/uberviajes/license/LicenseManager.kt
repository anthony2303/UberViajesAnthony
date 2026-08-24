package com.anthony.uberviajes.license

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Maneja la activación y verificación de licencias contra tu servidor.
 * Contrato confirmado contra tu server.js real:
 *
 *   POST /api/license/activate   body: {"key", "deviceId"}
 *     -> 200 {"ok": true, "cliente": "...", "expiraEn": 123}
 *     -> 4xx {"ok": false, "error": "..."}
 *
 *   POST /api/license/verify     body: {"key", "deviceId"}
 *     -> 200 {"valid": true, "expiraEn": 123}
 *     -> 200 {"valid": false, "reason": "..."}
 */
object LicenseManager {
    private const val PREFS = "license_prefs"
    private const val KEY_LICENSE_KEY = "license_key"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_ACTIVE = "license_active"
    private const val KEY_EXPIRES_AT = "license_expires_at"

    private const val BASE_URL = "http://144.126.137.93:1763"
    private const val ACTIVATE_PATH = "/api/license/activate"
    private const val VERIFY_PATH = "/api/license/verify"

    const val WHATSAPP_NUMBER = "523344800814" // 52 = México, sin + ni 00

    sealed class Result {
        data class Success(val expiraEn: Long) : Result()
        data class Error(val message: String) : Result()
    }

    fun getDeviceId(context: Context): String {
        val prefs = prefs(context)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    fun getSavedKey(context: Context): String? = prefs(context).getString(KEY_LICENSE_KEY, null)

    fun getExpiresAt(context: Context): Long = prefs(context).getLong(KEY_EXPIRES_AT, 0L)

    fun isActivatedLocally(context: Context): Boolean = prefs(context).getBoolean(KEY_ACTIVE, false)

    fun isCurrentlyValid(context: Context): Boolean =
        isActivatedLocally(context) && getExpiresAt(context) > System.currentTimeMillis()

    /** Llamada de red BLOQUEANTE — llamar siempre desde un hilo de fondo. */
    fun activate(context: Context, key: String): Result {
        return try {
            val deviceId = getDeviceId(context)
            val body = JSONObject().apply {
                put("key", key)
                put("deviceId", deviceId)
            }.toString()

            val json = postJson("$BASE_URL$ACTIVATE_PATH", body)

            if (json.optBoolean("ok", false)) {
                val expiraEn = json.optLong("expiraEn", 0L)
                prefs(context).edit()
                    .putString(KEY_LICENSE_KEY, key)
                    .putBoolean(KEY_ACTIVE, true)
                    .putLong(KEY_EXPIRES_AT, expiraEn)
                    .apply()
                Result.Success(expiraEn)
            } else {
                Result.Error(json.optString("error", "Clave inválida o ya activada en otro dispositivo"))
            }
        } catch (e: Exception) {
            Result.Error("Error de conexión con el servidor: ${e.message}")
        }
    }

    /** Revisa el estado actual en el servidor (por si se desactivó/extendió desde el panel admin). */
    fun refreshStatus(context: Context): Result {
        val key = getSavedKey(context) ?: return Result.Error("Sin licencia guardada")
        return try {
            val deviceId = getDeviceId(context)
            val body = JSONObject().apply {
                put("key", key)
                put("deviceId", deviceId)
            }.toString()

            val json = postJson("$BASE_URL$VERIFY_PATH", body)

            val valid = json.optBoolean("valid", false)
            val expiraEn = json.optLong("expiraEn", getExpiresAt(context))
            prefs(context).edit()
                .putBoolean(KEY_ACTIVE, valid)
                .putLong(KEY_EXPIRES_AT, expiraEn)
                .apply()

            if (valid) Result.Success(expiraEn)
            else Result.Error(json.optString("reason", "Licencia no válida"))
        } catch (e: Exception) {
            // Sin internet: seguimos confiando en el último estado guardado
            // localmente en vez de bloquear al usuario de golpe.
            Result.Error("Sin conexión, usando el último estado guardado")
        }
    }

    private fun postJson(urlStr: String, body: String): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 10000
            readTimeout = 10000
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        return JSONObject(text)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
