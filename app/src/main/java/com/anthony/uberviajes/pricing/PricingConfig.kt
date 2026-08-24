package com.anthony.uberviajes.pricing

import android.content.Context
import android.graphics.Color

data class PricingTier(
    val label: String,
    val minRatePerKm: Double,
    val color: Int
)

/**
 * Niveles de $/km configurables desde la app (pantalla de Ajustes). Se
 * guardan en SharedPreferences para persistir entre sesiones.
 *
 * ROJO es el nivel base (todo lo que no alcanza el umbral de NARANJA).
 * Los demás umbrales son editables; estos son solo valores de arranque.
 */
object PricingConfig {
    private const val PREFS_NAME = "pricing_prefs"
    private const val KEY_NARANJA = "min_naranja"
    private const val KEY_VERDE = "min_verde"
    private const val KEY_DORADO = "min_dorado"
    private const val KEY_DIAMANTE = "min_diamante"
    private const val KEY_MIN_FARE = "min_fare_mx"

    const val DEFAULT_NARANJA = 8.0
    const val DEFAULT_VERDE = 10.0
    const val DEFAULT_DORADO = 13.0
    const val DEFAULT_DIAMANTE = 16.0
    const val DEFAULT_MIN_FARE = 60.0

    fun getMinFare(context: Context): Double =
        prefs(context).getFloat(KEY_MIN_FARE, DEFAULT_MIN_FARE.toFloat()).toDouble()

    fun setMinFare(context: Context, value: Double) {
        prefs(context).edit().putFloat(KEY_MIN_FARE, value.toFloat()).apply()
    }

    /** Umbrales actuales, ordenados ascendente (Rojo -> Diamante). */
    fun getThresholds(context: Context): List<PricingTier> {
        val p = prefs(context)
        val naranja = p.getFloat(KEY_NARANJA, DEFAULT_NARANJA.toFloat()).toDouble()
        val verde = p.getFloat(KEY_VERDE, DEFAULT_VERDE.toFloat()).toDouble()
        val dorado = p.getFloat(KEY_DORADO, DEFAULT_DORADO.toFloat()).toDouble()
        val diamante = p.getFloat(KEY_DIAMANTE, DEFAULT_DIAMANTE.toFloat()).toDouble()
        return listOf(
            PricingTier("🔴 ROJO", 0.0, Color.parseColor("#F44336")),
            PricingTier("🟠 NARANJA", naranja, Color.parseColor("#FF9800")),
            PricingTier("🟢 VERDE", verde, Color.parseColor("#4CAF50")),
            PricingTier("🟡 DORADO", dorado, Color.parseColor("#FFD700")),
            PricingTier("💎 DIAMANTE", diamante, Color.parseColor("#18FFFF"))
        )
    }

    fun saveThresholds(
        context: Context,
        naranja: Double,
        verde: Double,
        dorado: Double,
        diamante: Double
    ) {
        prefs(context).edit()
            .putFloat(KEY_NARANJA, naranja.toFloat())
            .putFloat(KEY_VERDE, verde.toFloat())
            .putFloat(KEY_DORADO, dorado.toFloat())
            .putFloat(KEY_DIAMANTE, diamante.toFloat())
            .apply()
    }

    /** Devuelve el nivel (tier) que corresponde a un $/km dado. */
    fun tierFor(context: Context, ratePerKm: Double?): PricingTier {
        val tiers = getThresholds(context)
        if (ratePerKm == null) return tiers[0]
        return tiers.lastOrNull { ratePerKm >= it.minRatePerKm } ?: tiers[0]
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
