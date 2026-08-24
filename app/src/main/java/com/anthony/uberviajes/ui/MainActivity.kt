package com.anthony.uberviajes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.anthony.uberviajes.CrashHandler
import com.anthony.uberviajes.R
import com.anthony.uberviajes.service.ScreenCaptureService
import com.anthony.uberviajes.ui.SettingsActivity

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

    /**
     * Paso 1: permiso de overlay ("Mostrar sobre otras apps"), igual que en
     * GananciasPro — es lo que permite que el resultado se vea ENCIMA de
     * Uber/DiDi mientras se comparte pantalla. Paso 2: permiso de captura.
     */
    private fun startCaptureFlow() {
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
