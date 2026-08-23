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

## Qué falta / próximos pasos sugeridos

1. Portar el `AccessibilityService` principal desde tu proyecto Viajes
   Rentables (no incluido aquí porque es lógica propia tuya que no estaba en
   este manifest).
2. Completar `processImage()` en `ScreenCaptureService.kt` (conversión de
   `Image` a `Bitmap` y llamado a ML Kit).
3. Implementar `handleRecognizedText()` con tus reglas de rentabilidad
   (tarifa mínima, distancia, zona, etc.).
4. Agregar `google-services.json` de tu proyecto Firebase y descomentar el
   plugin/dependencias correspondientes.
5. Configurar Play Billing con tus SKUs de licencia/suscripción.
6. Íconos, splash screen y branding.

## Subir a GitHub

```bash
git remote add origin https://github.com/anthony2303/UberViajesAnthony.git
git add .
git commit -m "Skeleton inicial: MediaProjection + ML Kit OCR, Firebase/Billing/Ads stubs"
git branch -M main
git push -u origin main
```
