package com.example.orman

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class FocusService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var start = 0L
    private var durMs = 0L
    private var mode = "down"
    private var spc = "oak"
    private var strict = true
    private var last = ""
    private val ignore = setOf("com.android.systemui")

    private val loop = object : Runnable {
        override fun run() {
            step()
            if (Session.active) h.postDelayed(this, 1000)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        Store.init(applicationContext)
        if (i?.action == "STOP") {
            if (Session.active) finish(mode == "up", "Vazgeçtin.")
            else { Store.aStart = 0L; Store.save(); stopSelf() }
            return START_NOT_STICKY
        }
        val n = notif("Odak oturumu hazırlanıyor…")
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        if (Session.active) return START_STICKY

        if (i != null && i.hasExtra("min")) {
            // Yeni oturum
            Store.aStart = System.currentTimeMillis()
            Store.aDur = i.getIntExtra("min", 25)
            Store.aMode = i.getStringExtra("mode") ?: "down"
            Store.aSp = Store.sel
            Store.aTag = Store.tag
            Store.aStrict = Store.strict
            Store.save()
        } else if (Store.aStart == 0L) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        // Yeni veya yarım kalan (devam eden) oturumu diskten yükle
        start = Store.aStart
        mode = Store.aMode
        durMs = Store.aDur * 60_000L
        spc = Store.aSp
        strict = Store.aStrict
        last = packageName
        Session.sp = spc
        Session.tag = Store.aTag
        Session.mode = mode
        Session.remaining = if (mode == "down") durMs / 1000 else 0L
        Session.progress = 0f
        Session.away = 0
        Session.result = null
        Session.active = true
        Sound.play(Store.sound)
        h.removeCallbacks(loop)
        h.post(loop)
        return START_STICKY
    }

    private fun step() {
        val el = System.currentTimeMillis() - start
        if (mode == "down") {
            if (el >= durMs) { finish(true, ""); return }
            Session.remaining = (durMs - el) / 1000
            Session.progress = el.toFloat() / durMs
        } else {
            Session.remaining = el / 1000
            Session.progress = minOf(1f, el / 3_600_000f)
        }
        if (strict) {
            fg()?.let { last = it }
            val okApp = last == packageName || last in ignore || last in Store.allowed
            if (okApp) Session.away = 0 else Session.away++
            if (Session.away >= 10) { finish(false, "İzinli olmayan bir uygulamaya girdin."); return }
        }
        val s = Session.remaining
        val txt = if (mode == "down") "Kalan: ${s / 60} dk ${s % 60} sn" else "Geçen: ${s / 60} dk ${s % 60} sn"
        getSystemService(NotificationManager::class.java).notify(1, notif(txt))
    }

    /** Son 60 sn içinde öne gelen son uygulama (yoksa null). */
    @Suppress("DEPRECATION")
    private fun fg(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val ev = usm.queryEvents(now - 60_000, now)
        val e = UsageEvents.Event()
        var p: String? = null
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) p = e.packageName
        }
        return p
    }

    private fun finish(ok0: Boolean, reason0: String) {
        var ok = ok0
        var reason = reason0
        h.removeCallbacks(loop)
        val el = System.currentTimeMillis() - start
        val min = if (mode == "down") (durMs / 60_000L).toInt() else (el / 60_000L).toInt()
        if (ok && mode == "up" && min < 10) {
            ok = false
            reason = "Kronometre modunda ağacın büyümesi için en az 10 dakika gerekir."
        }
        Store.trees = Store.trees + Tree(System.currentTimeMillis(), spc, min, ok, Session.tag)
        if (ok) Store.coins += min
        Store.aStart = 0L
        Store.save()
        Sound.stop()
        Session.result = Result(ok, min, spc, reason)
        Session.active = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("done", "Sonuç", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            2,
            Notification.Builder(this, "done")
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle(if (ok) "Ağacın büyüdü! 🌳" else "Ağacın soldu 🥀")
                .setContentText(if (ok) "$min dakika odaklandın, $min altın kazandın." else reason)
                .setContentIntent(pi).setAutoCancel(true).build()
        )
        stopSelf()
    }

    private fun notif(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("focus", "Odak", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "focus")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Odaklanıyorsun 🌱")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        h.removeCallbacks(loop)
        Sound.stop()
        Session.active = false
        super.onDestroy()
    }
}
