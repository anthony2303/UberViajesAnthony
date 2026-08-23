package com.anthony.uberviajes.notifications

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class UberViajesFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // TODO: enviar el token a tu backend/servicio de licencias, igual que
        // hacías en GananciasPro para notificaciones dirigidas por usuario.
        Log.d(TAG, "Nuevo token FCM: $token")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        // TODO: mostrar la notificación (avisos de nuevas features, cambios
        // de licencia, promociones, etc.)
        message.notification?.let {
            Log.d(TAG, "Notificación recibida: ${it.title} - ${it.body}")
        }
    }

    companion object {
        private const val TAG = "FCMService"
    }
}
