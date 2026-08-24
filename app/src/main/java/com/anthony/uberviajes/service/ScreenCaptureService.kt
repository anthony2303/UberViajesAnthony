package com.anthony.uberviajes.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.anthony.uberviajes.pricing.PricingConfig
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.regex.Pattern

/**
 * Servicio de respaldo (fallback) para leer las ofertas de viaje mediante
 * captura de pantalla + OCR, usado en dispositivos (p. ej. Samsung/OPPO)
 * donde el AccessibilityService no recibe los eventos de forma confiable.
 *
 * Muestra el resultado en dos lugares:
 *  - Toasts de debug (temporales, se van a ir quitando conforme se calibre).
 *  - Un overlay flotante fijo (WindowManager) encima de Uber/DiDi, que es lo
 *    que se queda visible mientras se comparte pantalla.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureWidth = 0
    private var captureHeight = 0

    // Evita procesar/alertar el mismo texto en cuadros consecutivos.
    private var lastProcessedText: String = ""
    private var isProcessing = false

    // Throttle para no saturar de Toasts (modo debug, temporal).
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastDebugToastAt = 0L

    // Los umbrales de $/km ahora se manejan en PricingConfig (configurables
    // desde SettingsActivity), no hace falta guardarlos aquí.

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // --- Overlay flotante ---
    private var windowManager: WindowManager? = null
    private var overlayContainer: LinearLayout? = null
    private var overlayText: TextView? = null

    // Firma de la oferta actualmente mostrada/descartada, para saber si el
    // usuario ya la cerró con la ✕ y no reaparezca sola mientras siga siendo
    // la misma oferta en pantalla.
    private var dismissedSignature: String? = null
    private var currentSignature: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            showDebugToast("ERROR en startForeground: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        addOverlayIfPossible()

        // Ojo: Activity.RESULT_OK vale -1, así que NO se puede usar -1 como
        // valor "no hay dato" (bug que ya nos mordió una vez). Int.MIN_VALUE
        // no colisiona con ningún resultCode real.
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode != Int.MIN_VALUE && resultData != null) {
            try {
                startProjection(resultCode, resultData)
            } catch (e: Exception) {
                showDebugToast("ERROR en startProjection: ${e.javaClass.simpleName}: ${e.message}")
            }
        } else {
            showDebugToast("ERROR: el servicio arrancó sin datos de proyección válidos")
        }

        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        showDebugToast("Iniciando proyección de pantalla…")

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        // Obligatorio desde Android 14 (API 34): hay que registrar un
        // callback ANTES de createVirtualDisplay(), o revienta con
        // IllegalStateException("Must register a callback...").
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                showDebugToast("La proyección de pantalla se detuvo")
                stopSelf()
            }
        }, mainHandler)

        val metrics = resources.displayMetrics
        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "UberViajesAnthonyCapture",
            captureWidth, captureHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        if (virtualDisplay == null) {
            showDebugToast("ERROR: no se pudo crear el VirtualDisplay")
        }

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            processImage(image)
        }, mainHandler)
    }

    private fun processImage(image: Image) {
        // Evita solapar llamadas a ML Kit si el frame anterior aún no terminó.
        if (isProcessing) {
            image.close()
            return
        }

        try {
            val bitmap = imageToBitmap(image)
            isProcessing = true
            val inputImage = InputImage.fromBitmap(bitmap, 0)

            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText -> handleRecognizedText(visionText) }
                .addOnFailureListener { e ->
                    showDebugToast("ERROR de ML Kit OCR: ${e.message}")
                }
                .addOnCompleteListener { isProcessing = false }
        } catch (e: Exception) {
            showDebugToast("ERROR procesando frame: ${e.message}")
            isProcessing = false
        } finally {
            image.close()
        }
    }

    /**
     * Convierte el Image capturado (RGBA_8888) a Bitmap, recortando el padding
     * de fila que agrega el compositor en algunos dispositivos.
     */
    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * captureWidth

        val bitmap = Bitmap.createBitmap(
            captureWidth + rowPadding / pixelStride,
            captureHeight,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, captureWidth, captureHeight)
        }
    }

    private fun handleRecognizedText(visionText: Text) {
        val text = visionText.text
        if (text.isBlank() || text == lastProcessedText) return
        lastProcessedText = text

        // DEBUG: muestra un fragmento de lo que el OCR está leyendo, para
        // confirmar que la captura de pantalla sí funciona aunque todavía
        // no matchee ninguna tarifa. Quítalo cuando ya esté calibrado.
        val preview = text.take(120).replace("\n", " | ")
        showDebugToast("OCR leyó: \"$preview\"")

        val offer = parseTripOffer(text)
        if (offer == null) {
            showDebugToast("Sin oferta completa (tarifa+km+min) en pantalla")
            currentSignature = null
            dismissedSignature = null // ya no está en pantalla, se olvida el descarte
            hideOverlay()
            return
        }
        evaluateOffer(offer)
    }

    /**
     * Extrae tarifa (MXN), distancia (km) y tiempo (min) del texto
     * reconocido. Calibrado con una oferta real de Uber:
     *
     *   "$32.96 ... Total: 23 min (9.7 km) ..."
     *
     * A propósito exige que aparezcan LOS TRES juntos (tarifa + km + min) —
     * así una pantalla que solo tiene un "$" suelto (p. ej. ganancias del
     * día, resumen semanal) no se confunde con una oferta de viaje real, que
     * era lo que dejaba el overlay "pegado" mostrando una oferta vieja.
     *
     * Si tu formato varía entre Uber/DiDi/Cabify, puede que necesites
     * ajustar estos patrones — mándame otra captura de pantalla del texto
     * que no matchee y lo afinamos.
     */
    private fun parseTripOffer(text: String): TripOffer? {
        val fareMatch = Pattern.compile("\\$\\s?(\\d+(?:[.,]\\d{1,2})?)").matcher(text)
        val kmMatch = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s?km").matcher(text)
        val minMatch = Pattern.compile("(\\d+)\\s?min").matcher(text)

        val fare = if (fareMatch.find()) fareMatch.group(1)?.replace(",", ".")?.toDoubleOrNull() else null
        val km = if (kmMatch.find()) kmMatch.group(1)?.replace(",", ".")?.toDoubleOrNull() else null
        val min = if (minMatch.find()) minMatch.group(1)?.toIntOrNull() else null

        // Los tres deben estar presentes — si falta km o min, no lo tratamos
        // como oferta real (evita falsos positivos de un "$" suelto).
        if (fare == null || km == null || min == null) return null
        return TripOffer(fareMx = fare, distanceKm = km, minutes = min)
    }

    private fun evaluateOffer(offer: TripOffer) {
        val signature = "${offer.fareMx}-${offer.distanceKm}-${offer.minutes}"
        currentSignature = signature

        if (signature == dismissedSignature) {
            // El usuario ya cerró esta misma oferta con la ✕; sigue en
            // pantalla pero no la volvemos a mostrar hasta que cambie.
            return
        }

        val ratePerKm = if (offer.distanceKm != null && offer.distanceKm > 0) {
            offer.fareMx / offer.distanceKm
        } else null

        val tier = PricingConfig.tierFor(this, ratePerKm)
        val minFare = PricingConfig.getMinFare(this)
        val ratePerKmText = ratePerKm?.let { "%.1f".format(it) } ?: "N/A"

        val resumen = "${tier.label}\n\$${offer.fareMx} · ${offer.distanceKm ?: "?"} km · ${offer.minutes ?: "?"} min\n\$/km: $ratePerKmText"

        showDebugToast(resumen.replace("\n", " | "))
        updateOverlay(resumen, tier.color)

        if (offer.fareMx < minFare) {
            showDebugToast("(tarifa \$${offer.fareMx} está bajo el mínimo absoluto de \$$minFare)")
        }
    }

    // --- Overlay flotante ---

    private fun addOverlayIfPossible() {
        if (overlayContainer != null) return // ya está agregado
        if (!Settings.canDrawOverlays(this)) {
            showDebugToast("Sin permiso de overlay, no se puede mostrar encima de otras apps")
            return
        }

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val density = resources.displayMetrics.density

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                // Centrado horizontalmente, pegado un poco arriba (debajo del
                // badge de multiplicador tipo "601" que Uber muestra en la
                // parte superior), en vez de en medio de la pantalla.
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (170 * density).toInt()
            }


            val textView = TextView(this).apply {
                text = "UBER VIAJES ANTHONY\nEsperando oferta…"
                setTextColor(Color.WHITE)
                typeface = Typeface.MONOSPACE
                setLetterSpacing(0.05f)
                textSize = 13f
                gravity = Gravity.CENTER
            }

            val closeButton = TextView(this).apply {
                text = "✕"
                setTextColor(Color.WHITE)
                textSize = 15f
                val pad = (6 * density).toInt()
                setPadding(pad, 0, pad, pad)
                setOnClickListener {
                    dismissedSignature = currentSignature
                    hideOverlay()
                }
            }

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val padH = (18 * density).toInt()
                val padV = (14 * density).toInt()
                setPadding(padH, padV, padH, padV)
                background = buildOverlayBackground(Color.parseColor("#18FFFF"))
                elevation = 12f
                visibility = android.view.View.GONE // oculto hasta que haya una oferta real

                addView(closeButton, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.END })
                addView(textView)
            }

            windowManager?.addView(container, params)
            overlayContainer = container
            overlayText = textView
        } catch (e: Exception) {
            showDebugToast("ERROR agregando overlay: ${e.message}")
        }
    }

    /**
     * Fondo "futurista": esquinas redondeadas, negro casi opaco, con un
     * borde de neón del color del nivel actual (rojo/naranja/verde/
     * dorado/diamante).
     */
    private fun buildOverlayBackground(accentColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f
            setColor(Color.argb(235, 8, 10, 18))
            setStroke((2.5f * resources.displayMetrics.density).toInt(), accentColor)
        }
    }

    private fun updateOverlay(text: String, accentColor: Int = Color.parseColor("#18FFFF")) {
        mainHandler.post {
            overlayText?.text = "UBER VIAJES ANTHONY\n$text"
            overlayContainer?.apply {
                background = buildOverlayBackground(accentColor)
                visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun hideOverlay() {
        mainHandler.post {
            overlayContainer?.visibility = android.view.View.GONE
        }
    }

    private fun removeOverlay() {
        try {
            overlayContainer?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
            // la vista ya pudo haber sido removida por el sistema
        }
        overlayContainer = null
        overlayText = null
    }

    /**
     * Toast simple para depurar sin ADB. Throttleado a ~1 cada 2.5s (solo los
     * mensajes normales, no los de ERROR) para no saturar la pantalla si hay
     * muchos frames seguidos. Corre en el hilo principal porque el
     * ImageReader ahora usa mainHandler.
     */
    private fun showDebugToast(message: String) {
        val isError = message.startsWith("ERROR")
        val now = System.currentTimeMillis()
        if (!isError) {
            if (now - lastDebugToastAt < 2500) return
            lastDebugToastAt = now
        }
        mainHandler.post {
            Toast.makeText(this, message, if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    private data class TripOffer(
        val fareMx: Double,
        val distanceKm: Double?,
        val minutes: Int?
    )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Captura de pantalla",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Uber Viajes Anthony")
            .setContentText("Analizando ofertas de viaje…")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
