package com.anthony.uberviajes.ui

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.anthony.uberviajes.pricing.PricingConfig

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (20 * resources.displayMetrics.density).toInt()
        val tiers = PricingConfig.getThresholds(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0E1A"))
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "NIVELES DE RENTABILIDAD (\$/km)"
            textSize = 18f
            setTextColor(Color.parseColor("#18FFFF"))
            setPadding(0, 0, 0, pad)
        })

        root.addView(TextView(this).apply {
            text = "🔴 ROJO es siempre la base (todo lo que no llegue al umbral de NARANJA)."
            setTextColor(Color.parseColor("#8A91B0"))
            setPadding(0, 0, 0, pad)
        })

        fun addField(label: String, initial: Double): EditText {
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(initial.toString())
                setTextColor(Color.WHITE)
            }
            root.addView(TextView(this).apply {
                text = label
                setTextColor(Color.parseColor("#E6E9F5"))
            })
            root.addView(input)
            return input
        }

        val naranjaInput = addField("🟠 NARANJA desde (\$/km)", tiers[1].minRatePerKm)
        val verdeInput = addField("🟢 VERDE desde (\$/km)", tiers[2].minRatePerKm)
        val doradoInput = addField("🟡 DORADO desde (\$/km)", tiers[3].minRatePerKm)
        val diamanteInput = addField("💎 DIAMANTE desde (\$/km)", tiers[4].minRatePerKm)
        val minFareInput = addField("Tarifa mínima absoluta (\$)", PricingConfig.getMinFare(this))

        val saveButton = Button(this).apply {
            text = "Guardar"
            setOnClickListener {
                try {
                    val naranja = naranjaInput.text.toString().toDouble()
                    val verde = verdeInput.text.toString().toDouble()
                    val dorado = doradoInput.text.toString().toDouble()
                    val diamante = diamanteInput.text.toString().toDouble()
                    val minFare = minFareInput.text.toString().toDouble()

                    if (!(naranja < verde && verde < dorado && dorado < diamante)) {
                        Toast.makeText(
                            this@SettingsActivity,
                            "Los umbrales deben ir en orden: Naranja < Verde < Dorado < Diamante",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnClickListener
                    }

                    PricingConfig.saveThresholds(this@SettingsActivity, naranja, verde, dorado, diamante)
                    PricingConfig.setMinFare(this@SettingsActivity, minFare)
                    Toast.makeText(this@SettingsActivity, "Guardado", Toast.LENGTH_SHORT).show()
                    finish()
                } catch (e: NumberFormatException) {
                    Toast.makeText(
                        this@SettingsActivity,
                        "Revisa que todos los campos sean números válidos",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        root.addView(saveButton)

        setContentView(ScrollView(this).apply { addView(root) })
    }
}
