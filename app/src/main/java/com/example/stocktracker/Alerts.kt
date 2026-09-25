package com.example.stocktracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

object Alerts {
    private const val CH = "deals"

    fun notify(c: Context, item: Item, price: Double?) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "Stock alerts", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(c, item.id.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse(item.url)), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, CH)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("In stock: ${item.name}")
            .setContentText("\$${"%.2f".format(price)} at ${Checker.retailer(item.url)} (target ≤ \$${"%.2f".format(item.maxPrice)})")
            .setContentIntent(pi).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        try { nm.notify(item.id.hashCode(), n) } catch (_: SecurityException) {}
    }

    fun email(s: Settings, subject: String, body: String) {
        val props = Properties().apply {
            put("mail.smtp.auth", "true"); put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", "smtp.gmail.com"); put("mail.smtp.port", "587")
            put("mail.smtp.connectiontimeout", "15000"); put("mail.smtp.timeout", "15000")
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(s.smtpUser, s.smtpPass)
        })
        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(s.smtpUser))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(s.emailTo.ifBlank { s.smtpUser }))
            setSubject(subject); setText(body)
        }
        Transport.send(msg)
    }
}
