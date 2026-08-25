# Viajes Rentables 2.0

Skeleton funcional de app Android para evaluar ofertas de viaje (rideshare),
reconstruido en base a las funciones identificadas en una app anterior tuya
("GananciasPro") de la que perdiste el código fuente. **No es una copia
descompilada** — es código nuevo, escrito desde cero, que implementa el mismo
conjunto de funciones. Tema visual oscuro/neón, igual que tu panel admin.

## Qué incluye este skeleton

- `MainActivity`: pantalla principal + flujo para pedir permiso de
  `MediaProjection`.
- `ScreenCaptureService`: servicio en primer plano que captura la pantalla
  (fallback para dispositivos donde el AccessibilityService falla) e integra
  ML Kit Text Recognition para OCR.
- Dependencias ya declaradas para Google Play Billing y AdMob.

## Sistema de licencias

Confirmado contra tu `server.js` real — la app ya usa exactamente estas rutas:

```
POST /api/license/activate   body: {"key", "deviceId"}
  -> {"ok": true, "cliente": "...", "expiraEn": 123}
  -> {"ok": false, "error": "..."}

POST /api/license/verify     body: {"key", "deviceId"}
  -> {"valid": true, "expiraEn": 123}
  -> {"valid": false, "reason": "..."}
```

Tu servidor ya rechaza correctamente activar la misma clave en un segundo
`deviceId` distinto, así que "solo se puede activar una vez" ya queda
cubierto del lado del servidor.

El botón de renovación abre WhatsApp (`523344800814`) con un mensaje
prellenado cuando la licencia está vencida.

⚠️ Nota aparte sobre tu `server.js`: el `ADMIN_SECRET` tiene un valor por
defecto hardcodeado en el código (`'Jesus2505'`) que se usa si la variable
de entorno no está puesta. Si ese archivo llega a subirse a un repo (aunque
sea privado) o si el hosting no tiene la variable de entorno configurada,
cualquiera con ese secreto por defecto podría administrar tus licencias.
Vale la pena quitar el fallback y asegurarte de que `ADMIN_SECRET` siempre
venga de una variable de entorno real.

Desde el botón "Configurar niveles de \$/km" en la pantalla principal puedes
ajustar los umbrales de cada nivel — 🔴 Rojo (base), 🟠 Naranja, 🟢 Verde,
🟡 Dorado, 💎 Diamante. El overlay flotante usa el color del nivel actual
como borde de neón.

## Segundo plano

Antes de pedir el permiso de captura de pantalla, la app ahora pide quedar
excluida de la optimización de batería (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
y la notificación del servicio es "ongoing" (no se puede deslizar para
quitarla) — ambas cosas ayudan a que Android no mate el servicio mientras
trabajas en la app de Uber/DiDi.

⚠️ En algunas marcas (Xiaomi/MIUI, Huawei, Honor, Oppo, Vivo) eso no basta —
tienen su propio "administrador de batería" con un permiso extra de
"Autoarranque"/"Inicio automático" que no se puede pedir por código; hay que
activarlo a mano en Ajustes del sistema → Batería → (nombre de la app) →
"Sin restricciones" / "Permitir actividad en segundo plano". Si después de
este cambio la app se sigue cerrando, revisa esa opción según tu marca de
celular.

## Estado actual

- ✅ `ScreenCaptureService`: conversión `Image → Bitmap` implementada, llamada
  a ML Kit funcional, parseo básico de tarifa/distancia con regex, y
  evaluación simple de rentabilidad (`evaluateOffer`).
- ✅ Workflow de GitHub Actions que compila un APK debug en cada push a
  `main` y lo deja descargable como artifact.
- ⛔ Firebase (Messaging/Crashlytics/Remote Config) removido — si más
  adelante lo quieres agregar, hay que volver a declarar el plugin
  `com.google.gms.google-services` en los `build.gradle.kts`, las
  dependencias correspondientes, y colocar tu `google-services.json`.

## Qué falta / próximos pasos sugeridos

1. Portar el `AccessibilityService` principal desde tu proyecto Viajes
   Rentables (no incluido aquí porque es lógica propia tuya que no estaba en
   este manifest).
2. Ajustar las expresiones regulares de `parseTripOffer()` al formato real
   de texto que muestra la app de la plataforma en tu pantalla.
3. Conectar `evaluateOffer()` a un overlay visual (WindowManager) o a un
   callback/broadcast hacia `MainActivity`.
4. Configurar Play Billing con tus SKUs de licencia/suscripción.
5. Íconos, splash screen y branding.
6. (Opcional) Volver a agregar Firebase si más adelante lo necesitas.
7. **Reemplazar el App ID de AdMob** en `AndroidManifest.xml` (actualmente
   tiene el ID de prueba oficial de Google) por tu App ID real cuando quieras
   ver anuncios de verdad — Consola AdMob → Apps → tu app → App ID.

## Descargar el APK compilado desde GitHub Actions

Cada push a `main` dispara el workflow `.github/workflows/build-apk.yml`, que
compila un APK debug y lo sube como artifact descargable (pestaña **Actions**
del repo → selecciona el run → sección **Artifacts**, abajo del todo).

## Subir a GitHub

```bash
git remote add origin https://github.com/anthony2303/UberViajesAnthony.git
git add .
git commit -m "Agrega workflow de build de APK"
git push
```
