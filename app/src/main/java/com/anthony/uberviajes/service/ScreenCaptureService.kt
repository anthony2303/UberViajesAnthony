package com.anthony.uberviajes.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
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
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.anthony.uberviajes.R
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
        const val EXTRA_AUTO_RESUME = "extra_auto_resume"
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 1001
        private const val RESUME_NOTIFICATION_ID = 1002
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
    private var overlayTierLabel: TextView? = null
    private var overlayStats: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

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

        // OJO: ya NO se crea el overlay aquí. Crearlo de una vez, oculto y
        // con texto de relleno ("…"), era la causa real del bug de tamaño:
        // esa primera medición "vacía" dejaba la ventana WRAP_CONTENT
        // atascada en un tamaño chico para siempre, sin importar qué texto
        // se le pusiera después. Ahora la ventana se crea perezosamente,
        // ya con el contenido real, la primera vez que hay una oferta real
        // (ver ensureOverlayCreated(), llamada desde updateOverlay()).

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
                // Android corta la proyección a propósito cuando se apaga la
                // pantalla (protección de privacidad del sistema — ninguna
                // app puede evitar esto). No podemos "seguir grabando" con
                // la pantalla apagada, pero sí dejamos un acceso directo de
                // un toque para reanudar en cuanto se vuelva a encender.
                showDebugToast("La captura se detuvo (¿se apagó la pantalla?)")
                notifyProjectionStoppedWithResumeAction()
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

    private var lastFrameProcessedAt = 0L

    private fun processImage(image: Image) {
        // Evita solapar llamadas a ML Kit si el frame anterior aún no terminó.
        if (isProcessing) {
            image.close()
            return
        }

        // Límite de frecuencia: sin esto, el VirtualDisplay puede mandar
        // muchos cuadros por segundo (cada vez que el mapa se redibuja),
        // y cada uno crea un Bitmap de pantalla completa (~10MB). Con el
        // tiempo eso satura la memoria y probablemente era la causa de que
        // la app se cerrara sola después de un rato.
        val now = System.currentTimeMillis()
        if (now - lastFrameProcessedAt < 1000) {
            image.close()
            return
        }
        lastFrameProcessedAt = now

        var bitmap: Bitmap? = null
        try {
            bitmap = imageToBitmap(image)
            isProcessing = true
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val bitmapToRecycle = bitmap

            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText -> handleRecognizedText(visionText) }
                .addOnFailureListener { e ->
                    showDebugToast("ERROR de ML Kit OCR: ${e.message}")
                }
                .addOnCompleteListener {
                    isProcessing = false
                    bitmapToRecycle.recycle()
                }
        } catch (e: Exception) {
            showDebugToast("ERROR procesando frame: ${e.message}")
            isProcessing = false
            bitmap?.recycle()
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
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, captureWidth, captureHeight)
            bitmap.recycle()
            cropped
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
     * reconocido. Calibrado con ofertas reales de Uber:
     *
     *   "$32.96 ... Total: 23 min (9.7 km) ..."
     *
     * A propósito exige el patrón EXACTO "Total: X min (Y km)" en vez de
     * buscar "cualquier km" y "cualquier min" sueltos en toda la pantalla —
     * una pantalla de "Detalles del viaje" (viaje ya completado) también
     * tiene un $ y campos separados de "Duración"/"Distancia", y con el
     * patrón suelto eso se confundía con una oferta nueva.
     *
     * Si tu formato varía entre Uber/DiDi/Cabify, puede que necesites
     * ajustar este patrón — mándame otra captura de pantalla del texto
     * que no matchee y lo afinamos.
     */
    private fun parseTripOffer(text: String): TripOffer? {
        val fareMatch = Pattern.compile("\\$\\s?(\\d+(?:[.,]\\d{1,2})?)").matcher(text)
        val totalMatch = Pattern.compile(
            "Total:?\\s*(\\d+)\\s*min\\s*\\((\\d+(?:[.,]\\d+)?)\\s*km\\)"
        ).matcher(text)

        val fare = if (fareMatch.find()) fareMatch.group(1)?.replace(",", ".")?.toDoubleOrNull() else null

        if (fare == null || !totalMatch.find()) return null

        val min = totalMatch.group(1)?.toIntOrNull()
        val km = totalMatch.group(2)?.replace(",", ".")?.toDoubleOrNull()

        if (min == null || km == null) return null
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

        val ratePerHour = if (offer.minutes != null && offer.minutes > 0) {
            offer.fareMx * 60.0 / offer.minutes
        } else null

        val tier = PricingConfig.tierFor(this, ratePerKm)
        val minFare = PricingConfig.getMinFare(this)

        val ratePerKmText = ratePerKm?.let { "%.1f".format(it) } ?: "N/A"
        val ratePerHourText = ratePerHour?.let { "%.0f".format(it) } ?: "N/A"
        val statsText = "\$$ratePerKmText/km  ·  \$$ratePerHourText/h"

        showDebugToast("${tier.label} — $statsText")
        updateOverlay(tier.label, statsText, tier.color)

        if (offer.fareMx < minFare) {
            showDebugToast("(tarifa \$${offer.fareMx} está bajo el mínimo absoluto de \$$minFare)")
        }
    }

    // --- Overlay flotante ---

    /**
     * Crea la ventana del overlay YA con el contenido real puesto
     * (tierLabel/statsText/accentColor recibidos), en vez de crearla
     * vacía/oculta ("…") y cambiarla después.
     *
     * Esta es la diferencia real frente a la versión que sí funciona
     * (`OverlayManager.crearVista()` + `actualizarContenido()` en el otro
     * proyecto): ahí la ventana NUNCA se agrega vacía. Se agrega la
     * primera vez que hay un veredicto real, ya con ese contenido. Crearla
     * antes con texto de relleno dejaba la ventana WRAP_CONTENT fijada en
     * un tamaño chico para siempre en este dispositivo, sin importar qué
     * texto se pusiera después — por eso "DI $88" se veía siempre igual
     * de chico sin importar el nivel real.
     */
    private fun ensureOverlayCreated(tierLabel: String, statsText: String, accentColor: Int) {
        if (overlayContainer != null) return // ya existe, solo hay que actualizar su contenido

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

            // Inflar desde XML (overlay_trip_info.xml) en vez de construir las
            // vistas a mano en Kotlin — este es el patrón que ya probaste que
            // funciona en tu otro proyecto (OverlayManager.crearVista()).
            val vista = LayoutInflater.from(this).inflate(R.layout.overlay_trip_info, null) as LinearLayout
            vista.background = buildOverlayBackground(accentColor)

            val tierLabelView = vista.findViewById<TextView>(R.id.tvOverlayResultado).apply {
                text = tierLabel
                setTextColor(accentColor)
            }
            val statsView = vista.findViewById<TextView>(R.id.tvOverlayDetalle).apply {
                text = statsText
            }
            vista.findViewById<TextView>(R.id.tvOverlayClose).setOnClickListener {
                dismissedSignature = currentSignature
                hideOverlay()
            }

            windowManager?.addView(vista, params)
            overlayContainer = vista
            overlayParams = params
            overlayTierLabel = tierLabelView
            overlayStats = statsView
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

    private fun updateOverlay(tierLabel: String, statsText: String, accentColor: Int = Color.parseColor("#18FFFF")) {
        mainHandler.post {
            if (overlayContainer == null) {
                // Primera oferta real (o primera después de un removeOverlay):
                // se crea la ventana ya con este contenido.
                ensureOverlayCreated(tierLabel, statsText, accentColor)
                return@post
            }

            // La ventana ya existe y ya tenía contenido real (nunca estuvo
            // vacía/oculta) — un simple cambio de texto es todo lo que hace
            // falta; WRAP_CONTENT se reajusta solo. Nada de measure() ni
            // updateViewLayout() manual: eso fue lo que rompía el
            // auto-resize en los intentos anteriores.
            overlayTierLabel?.apply {
                text = tierLabel
                setTextColor(accentColor)
            }
            overlayStats?.text = statsText
            overlayContainer?.apply {
                background = buildOverlayBackground(accentColor)
                visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun hideOverlay() {
        mainHandler.post {
            // Se quita la ventana por completo (no solo GONE): así la
            // próxima vez que haya una oferta real, ensureOverlayCreated()
            // la vuelve a crear ya con el contenido correcto desde cero,
            // en vez de reciclar una ventana que pudo quedar con un
            // tamaño viejo.
            removeOverlay()
        }
    }

    private fun removeOverlay() {
        try {
            overlayContainer?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
            // la vista ya pudo haber sido removida por el sistema
        }
        overlayContainer = null
        overlayTierLabel = null
        overlayStats = null
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
            .setContentTitle("Viajes Rentables 2.0")
            .setContentText("Analizando ofertas de viaje en segundo plano…")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Notificación aparte (no la del foreground service) que se muestra
     * cuando el sistema corta la proyección — normalmente por apagar la
     * pantalla. Al tocarla abre MainActivity con un extra que dispara el
     * flujo de captura automáticamente, para que reanudar sea de un solo
     * toque en vez de tener que navegar la app de nuevo.
     */
    private fun notifyProjectionStoppedWithResumeAction() {
        val resumeIntent = Intent(this, com.anthony.uberviajes.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_AUTO_RESUME, true)
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            resumeIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Captura de pantalla detenida")
            .setContentText("Probablemente se apagó la pantalla. Toca para reanudar.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(RESUME_NOTIFICATION_ID, notification)
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
