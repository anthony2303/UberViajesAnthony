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

## Sistema de licencias (⚠️ requiere confirmar tus endpoints)

La app ahora exige activar una licencia contra tu servidor
(`144.126.137.93:1763`) antes de dejar usar la captura de ofertas. Vi tu
panel `admin.html`, pero ese panel solo expone rutas para TI como admin
(`/api/admin/licenses/*`, protegidas con `x-admin-secret`). No hay ahí
ninguna ruta pública para que la APP del cliente active su propia clave —
tuve que inventar un contrato razonable en `LicenseManager.kt`:

```
POST /api/license/activate   body: {"key": "...", "deviceId": "..."}
  -> {"ok": true, "expiraEn": 1234567890123}
  -> {"ok": false, "error": "Clave inválida o ya activada"}

GET /api/license/status?key=...&deviceId=...
  -> {"activa": true, "expiraEn": 1234567890123}
```

**Si tu servidor no tiene estas rutas todavía, hay que agregarlas** (la
activación debe rechazar una clave que ya tenga un `deviceId` distinto
asociado, para que "solo se pueda activar una vez"). Si prefieres rutas
distintas, dime el contrato exacto y ajusto `LicenseManager.kt`.

El botón de renovación abre WhatsApp (`523344800814`) con un mensaje
prellenado cuando la licencia está vencida.

Desde el botón "Configurar niveles de \$/km" en la pantalla principal puedes
ajustar los umbrales de cada nivel — 🔴 Rojo (base), 🟠 Naranja, 🟢 Verde,
🟡 Dorado, 💎 Diamante. El overlay flotante usa el color del nivel actual
como borde de neón.

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
