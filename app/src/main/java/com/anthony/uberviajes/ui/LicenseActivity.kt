package com.anthony.uberviajes.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.anthony.uberviajes.license.LicenseManager

class LicenseActivity : AppCompatActivity() {

    private lateinit var keyInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var activateButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        val pad = (28 * density).toInt()

        val title = TextView(this).apply {
            text = "VIAJES RENTABLES 2.0"
            setTextColor(Color.parseColor("#18FFFF"))
            setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            textSize = 22f
            gravity = Gravity.CENTER
            setLetterSpacing(0.04f)
        }

        val subtitle = TextView(this).apply {
            text = "Ingresa tu clave de licencia para activar la app"
            setTextColor(Color.parseColor("#8A91B0"))
            textSize = 13f
            gravity = Gravity.CENTER
            val v = (10 * density).toInt()
            setPadding(0, v, 0, (28 * density).toInt())
        }

        keyInput = EditText(this).apply {
            hint = "Clave de licencia"
            setHintTextColor(Color.parseColor("#8A91B0"))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                cornerRadius = 8f
                setColor(Color.parseColor("#0D1224"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#232A45"))
            }
        }

        activateButton = Button(this).apply {
            text = "ACTIVAR"
            setOnClickListener { onActivateClicked() }
        }

        progress = ProgressBar(this).apply {
            visibility = View.GONE
        }

        val spacer1 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, (18 * density).toInt())
        }
        val spacer2 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, (16 * density).toInt())
        }

        val footer = TextView(this).apply {
            text = "App creada por Anthony — Derechos reservados © 2026"
            setTextColor(Color.parseColor("#5A6285"))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, (40 * density).toInt(), 0, 0)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0A0E1A"))
            setPadding(pad, pad, pad, pad)
            addView(title)
            addView(subtitle)
            addView(keyInput)
            addView(spacer1)
            addView(activateButton)
            addView(spacer2)
            addView(progress)
            addView(footer)
        }

        setContentView(root)
    }

    private fun onActivateClicked() {
        val key = keyInput.text.toString().trim()
        if (key.isEmpty()) {
            Toast.makeText(this, "Escribe tu clave de licencia", Toast.LENGTH_SHORT).show()
            return
        }
        progress.visibility = View.VISIBLE
        activateButton.isEnabled = false

        Thread {
            val result = LicenseManager.activate(this, key)
            runOnUiThread {
                progress.visibility = View.GONE
                activateButton.isEnabled = true
                when (result) {
                    is LicenseManager.Result.Success -> {
                        Toast.makeText(this, "¡Licencia activada!", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                    is LicenseManager.Result.Error -> {
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }
}
