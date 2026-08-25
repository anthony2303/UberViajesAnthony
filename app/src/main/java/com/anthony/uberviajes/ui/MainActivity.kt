package com.anthony.uberviajes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.anthony.uberviajes.CrashHandler
import com.anthony.uberviajes.R
import com.anthony.uberviajes.license.LicenseManager
import com.anthony.uberviajes.service.ScreenCaptureService

class MainActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val captureRequestLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                Toast.makeText(this, "Permiso concedido, iniciando servicio…", Toast.LENGTH_SHORT).show()
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                startForegroundService(serviceIntent)
            } else {
                Toast.makeText(this, "Permiso de captura NO concedido (resultCode=${result.resultCode})", Toast.LENGTH_LONG).show()
            }
        }

    // Lanza la pantalla de Ajustes donde se otorga "Mostrar sobre otras apps".
    // Cuando el usuario regresa, seguimos el flujo automáticamente si ya lo
    // concedió (no hay callback directo de éxito/fallo para este permiso).
    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Permiso de overlay concedido", Toast.LENGTH_SHORT).show()
                requestScreenCapture()
            } else {
                Toast.makeText(this, "Sin permiso de overlay, no se mostrará el resultado encima de otras apps", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(CrashHandler.PREFS_NAME, Context.MODE_PRIVATE)
        val lastCrash = prefs.getString(CrashHandler.KEY_LAST_CRASH, null)
        if (lastCrash != null) {
            prefs.edit().remove(CrashHandler.KEY_LAST_CRASH).apply()
            showCrashScreen(lastCrash)
            return
        }

        setContentView(R.layout.activity_main)
        bindMainScreen()
        checkLicense()

        // Si venimos de la notificación "Captura de pantalla detenida",
        // disparamos el flujo de reanudar automáticamente — el permiso de
        // captura en sí SIEMPRE requiere que el usuario lo confirme de
        // nuevo (Android no deja reanudar en silencio una vez que el
        // sistema la cortó), pero así evitamos que tenga que buscar el
        // botón manualmente.
        if (intent?.getBooleanExtra(ScreenCaptureService.EXTRA_AUTO_RESUME, false) == true) {
            startCaptureFlow()
        }
    }

    /**
     * Si no hay ninguna licencia guardada, manda directo a LicenseActivity.
     * Si hay una guardada pero ya venció (según el último estado conocido),
     * muestra el diálogo de renovación. También refresca el estado contra
     * el servidor en segundo plano por si se desactivó/extendió desde el
     * panel admin.
     */
    private fun checkLicense() {
        if (LicenseManager.getSavedKey(this) == null) {
            startActivity(Intent(this, LicenseActivity::class.java))
            finish()
            return
        }

        if (!LicenseManager.isCurrentlyValid(this)) {
            showLicenseExpiredDialog()
        }

        Thread {
            val result = LicenseManager.refreshStatus(this)
            if (result is LicenseManager.Result.Error && LicenseManager.isCurrentlyValid(this).not()) {
                runOnUiThread { showLicenseExpiredDialog() }
            }
        }.start()
    }

    private fun showLicenseExpiredDialog() {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("Tu licencia está vencida")
            .setMessage("¿Quieres renovarla?")
            .setPositiveButton("Renovar por WhatsApp") { _, _ -> openWhatsAppRenewal() }
            .setNegativeButton("Después", null)
            .setCancelable(true)
            .show()
    }

    private fun openWhatsAppRenewal() {
        val mensaje = "Hola Anthony, quiero renovar mi licencia de Viajes Rentables 2.0"
        val uri = Uri.parse("https://wa.me/${LicenseManager.WHATSAPP_NUMBER}?text=${Uri.encode(mensaje)}")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir WhatsApp: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun bindMainScreen() {
        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        findViewById<Button>(R.id.btnStartCapture).setOnClickListener {
            startCaptureFlow()
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // Pantalla del sistema para excluir la app de la optimización de
    // batería. No hay callback de éxito/fallo directo; al volver revisamos
    // el estado real con isIgnoringBatteryOptimizations().
    private val batteryOptimizationLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            proceedAfterBatteryCheck()
        }

    /**
     * Paso 0 (antes que overlay/captura): pide que la app quede excluida de
     * la optimización de batería. Sin esto, en varias marcas (Xiaomi,
     * Huawei, Honor, Oppo, Samsung) el sistema mata el servicio de captura
     * después de un rato en segundo plano, aunque sea un foreground service.
     */
    private fun startCaptureFlow() {
        if (!LicenseManager.isCurrentlyValid(this)) {
            showLicenseExpiredDialog()
            return
        }

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !powerManager.isIgnoringBatteryOptimizations(packageName)
        ) {
            Toast.makeText(
                this,
                "Permite que la app corra sin restricciones de batería, o se puede cerrar sola en segundo plano…",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            try {
                batteryOptimizationLauncher.launch(intent)
            } catch (e: Exception) {
                // Algunos fabricantes bloquean este intent; seguimos igual.
                proceedAfterBatteryCheck()
            }
            return
        }

        proceedAfterBatteryCheck()
    }

    private fun proceedAfterBatteryCheck() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Primero activa 'Mostrar sobre otras apps'…", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        Toast.makeText(this, "Pidiendo permiso de captura de pantalla…", Toast.LENGTH_SHORT).show()
        captureRequestLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    /**
     * Pantalla mínima (armada por código, sin depender del layout XML normal)
     * que muestra el último crash guardado por CrashHandler. Pensada para
     * poder leer/copiar el error directo desde el celular, sin ADB ni PC.
     */
    private fun showCrashScreen(crashText: String) {
        val padding = (16 * resources.displayMetrics.density).toInt()

        val messageView = TextView(this).apply {
            text = "La app se cerró la última vez. Este es el error:\n\n$crashText"
            setTextIsSelectable(true)
            textSize = 12f
            setPadding(padding, padding, padding, padding)
        }

        val scrollView = ScrollView(this).apply {
            addView(messageView)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val copyButton = Button(this).apply {
            text = "Copiar error al portapapeles"
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("crash", crashText))
                Toast.makeText(this@MainActivity, "Copiado", Toast.LENGTH_SHORT).show()
            }
        }

        val closeButton = Button(this).apply {
            text = "Cerrar y continuar"
            setOnClickListener {
                setContentView(R.layout.activity_main)
                bindMainScreen()
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            addView(scrollView)
            addView(copyButton)
            addView(closeButton)
        }

        setContentView(root)
    }
}
