package com.anthony.uberviajes.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.regex.Pattern

/**
 * Servicio de respaldo (fallback) para leer las ofertas de viaje mediante captura
 * de pantalla + OCR, usado en dispositivos (p. ej. Samsung/OPPO) donde el
 * AccessibilityService no recibe los eventos de forma confiable.
 *
 * Este es un skeleton funcional: implementa el flujo de MediaProjection + ImageReader
 * + ML Kit, pero la lógica de parseo/evaluación de la oferta (tarifa, distancia,
 * tiempo) queda como TODO para que la completes con tus reglas de negocio.
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

    // TODO: ajusta estos umbrales a tus criterios reales de "viaje rentable"
    private var minFareMx = 60.0
    private var minRatePerKm = 12.0

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode != -1 && resultData != null) {
            startProjection(resultCode, resultData)
        }

        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        showDebugToast("Iniciando proyección de pantalla…")

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        val metrics = resources.displayMetrics
        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "GananciasProCapture",
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
            showDebugToast("No se encontró tarifa (\$XX.XX) en el texto")
            return
        }
        evaluateOffer(offer)
    }

    /**
     * Extrae tarifa (MXN) y distancia (km) del texto reconocido.
     * TODO: ajusta las expresiones regulares al formato real que muestra la
     * app de la plataforma (Uber/DiDi/etc.) en tu dispositivo — los patrones
     * de abajo son un punto de partida genérico.
     */
    private fun parseTripOffer(text: String): TripOffer? {
        val fareMatch = Pattern.compile("\\$\\s?(\\d+(?:\\.\\d{1,2})?)").matcher(text)
        val kmMatch = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s?km").matcher(text)

        val fare = if (fareMatch.find()) fareMatch.group(1)?.toDoubleOrNull() else null
        val km = if (kmMatch.find()) kmMatch.group(1)?.replace(",", ".")?.toDoubleOrNull() else null

        if (fare == null) return null
        return TripOffer(fareMx = fare, distanceKm = km)
    }

    private fun evaluateOffer(offer: TripOffer) {
        val ratePerKm = if (offer.distanceKm != null && offer.distanceKm > 0) {
            offer.fareMx / offer.distanceKm
        } else null

        val isRentable = offer.fareMx >= minFareMx &&
            (ratePerKm == null || ratePerKm >= minRatePerKm)

        val ratePerKmText = ratePerKm?.let { "%.1f".format(it) } ?: "N/A"
        val veredicto = if (isRentable) "✅ RENTABLE" else "❌ NO rentable"
        showDebugToast(
            "$veredicto — Tarifa: \$${offer.fareMx} | Km: ${offer.distanceKm ?: "N/A"} | \$/km: $ratePerKmText"
        )

        // TODO: además del Toast de debug, mostrar el resultado en un overlay
        // visual (WindowManager) más permanente/legible, según cómo lo tenías
        // en Viajes Rentables.
    }

    /**
     * Toast simple para depurar sin ADB. Throttleado a ~1 cada 2.5s para no
     * saturar la pantalla si hay muchos frames seguidos. Corre en el hilo
     * principal porque el ImageReader ahora usa mainHandler.
     */
    private fun showDebugToast(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastDebugToastAt < 2500) return
        lastDebugToastAt = now
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private data class TripOffer(
        val fareMx: Double,
        val distanceKm: Double?
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
            .setContentTitle("GananciasPro")
            .setContentText("Analizando ofertas de viaje…")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
