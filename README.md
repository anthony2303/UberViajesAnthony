# Uber Viajes Anthony

Skeleton funcional de app Android para evaluar ofertas de viaje (rideshare),
reconstruido en base a las funciones identificadas en una app anterior tuya
("GananciasPro") de la que perdiste el código fuente. **No es una copia
descompilada** — es código nuevo, escrito desde cero, que implementa el mismo
conjunto de funciones.

## Qué incluye este skeleton

- `MainActivity`: pantalla principal + flujo para pedir permiso de
  `MediaProjection`.
- `ScreenCaptureService`: servicio en primer plano que captura la pantalla
  (fallback para dispositivos donde el AccessibilityService falla) e integra
  ML Kit Text Recognition para OCR. La conversión `Image → Bitmap` y el
  parseo de la oferta de viaje quedan como `TODO` — son tu lógica de negocio.
- Dependencias ya declaradas para Firebase (Messaging/Crashlytics/Remote
  Config — comentadas hasta que agregues tu `google-services.json`),
  Google Play Billing y AdMob.

## Estado actual

- ✅ `ScreenCaptureService`: conversión `Image → Bitmap` implementada, llamada
  a ML Kit funcional, parseo básico de tarifa/distancia con regex, y
  evaluación simple de rentabilidad (`evaluateOffer`).
- ✅ Firebase: `UberViajesApplication` inicializa Crashlytics y Remote Config
  (con `min_fare_mx` / `min_rate_per_km` como valores remotos), y
  `UberViajesFirebaseMessagingService` recibe notificaciones push.
- ⚠️ **IMPORTANTE**: el proyecto NO compilará todavía porque falta
  `app/google-services.json` — el plugin de Firebase lo requiere en tiempo
  de build. Descárgalo desde Firebase Console (Configuración del proyecto →
  Tus apps → Android) y colócalo en `app/google-services.json` (ya está en
  `.gitignore`, no se sube al repo).

## Qué falta / próximos pasos sugeridos

1. Agregar tu `google-services.json` (ver arriba) antes de compilar.
2. Portar el `AccessibilityService` principal desde tu proyecto Viajes
   Rentables (no incluido aquí porque es lógica propia tuya que no estaba en
   este manifest).
3. Ajustar las expresiones regulares de `parseTripOffer()` al formato real
   de texto que muestra la app de la plataforma en tu pantalla.
4. Conectar `evaluateOffer()` a un overlay visual (WindowManager) o a un
   callback/broadcast hacia `MainActivity`.
5. Leer `min_fare_mx`/`min_rate_per_km` desde Remote Config en vez de los
   valores fijos actuales en `ScreenCaptureService`.
6. Configurar Play Billing con tus SKUs de licencia/suscripción.
7. Íconos, splash screen y branding.

## Subir a GitHub

```bash
git remote add origin https://github.com/anthony2303/UberViajesAnthony.git
git add .
git commit -m "Skeleton inicial: MediaProjection + ML Kit OCR, Firebase/Billing/Ads stubs"
git branch -M main
git push -u origin main
```
