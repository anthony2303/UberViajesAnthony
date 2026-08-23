package com.anthony.uberviajes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
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

class MainActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val captureRequestLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                startForegroundService(serviceIntent)
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

        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        findViewById<Button>(R.id.btnStartCapture).setOnClickListener {
            startCaptureFlow()
        }
    }

    private fun startCaptureFlow() {
        // TODO: pedir permiso de overlay (SYSTEM_ALERT_WINDOW) si aún no se otorgó
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
                mediaProjectionManager =
                    getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                findViewById<Button>(R.id.btnStartCapture).setOnClickListener {
                    startCaptureFlow()
                }
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
