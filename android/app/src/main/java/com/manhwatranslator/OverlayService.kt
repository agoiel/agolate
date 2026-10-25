package com.manhwatranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.manhwatranslator.core.PageResult
import com.manhwatranslator.core.Pipeline
import com.manhwatranslator.core.Settings
import java.util.concurrent.Executors

/**
 * Фоновая служба: держит захват экрана, плавающую кнопку и слой с переводом.
 *
 * Нажатие на кнопку — перевести область (или скрыть перевод, если он показан).
 * Долгое нажатие — выделить новую область.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_RESET = "reset"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "overlay"
        private const val NOTIF_ID = 1

        @Volatile var running = false
            private set
    }

    private lateinit var wm: WindowManager
    private lateinit var settings: Settings
    private lateinit var pipeline: Pipeline
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var capturer: ScreenCapturer? = null
    private var button: FloatingButton? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var translationView: TranslationView? = null
    private var selectView: RegionSelectView? = null
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        settings = Settings(this)
        pipeline = Pipeline(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_RESET -> {
                pipeline.reset()
                translationView?.clear()
                updateButton()
                return START_NOT_STICKY
            }
        }

        // Android 14+: служба должна стать «передней» ДО получения разрешения на запись экрана
        startForegroundCompat()

        if (capturer == null) {
            val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
            }
            if (data == null) {
                toast("Нет разрешения на запись экрана. Запустите заново из приложения.")
                stopEverything()
                return START_NOT_STICKY
            }
            try {
                val mpm = getSystemService(MediaProjectionManager::class.java)
                val projection = mpm.getMediaProjection(code, data)
                capturer = ScreenCapturer(this, projection) {
                    main.post {
                        toast("Запись экрана остановлена. Чтобы продолжить, запустите переводчик снова.")
                        stopEverything()
                    }
                }
            } catch (e: Exception) {
                toast("Не удалось начать захват экрана: ${e.message}")
                stopEverything()
                return START_NOT_STICKY
            }
        }
        if (button == null) showButton()
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        removeViews()
        capturer?.stop()
        capturer = null
        worker.shutdownNow()
        pipeline.close()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- уведомление
    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Переводчик поверх экрана", NotificationManager.IMPORTANCE_LOW))
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stopPi = PendingIntent.getService(this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP), flags)
        val openPi = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), flags)
        val notif = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(Brand.COCOA)
            .setContentTitle("agolate работает")
            .setContentText("Нажмите на кнопку «Аа», чтобы перевести область")
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notification), "Остановить", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ---------------------------------------------------------------- окна поверх экрана
    private fun overlayParams(w: Int, h: Int, extraFlags: Int): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or extraFlags,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return p
    }

    private fun showButton() {
        // Слой перевода: на весь экран, касания проходят сквозь него.
        // Android 12+ пропускает касания только через окна с прозрачностью не выше 0.8.
        val tv = TranslationView(this)
        val tp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        tp.alpha = 0.8f
        wm.addView(tv, tp)
        translationView = tv

        // Кнопка добавляется после слоя — значит, она поверх него
        val b = FloatingButton(this,
            onTap = { onButtonTap() },
            onLongPress = { startSelection() },
            onMove = { dx, dy ->
                buttonParams?.let { p ->
                    p.x += dx; p.y += dy
                    button?.let { wm.updateViewLayout(it, p) }
                }
            },
            onMoveEnd = {
                buttonParams?.let { settings.buttonX = it.x; settings.buttonY = it.y }
            },
        )
        val p = overlayParams(b.sizePx, b.sizePx, 0)
        p.x = settings.buttonX
        p.y = settings.buttonY
        wm.addView(b, p)
        button = b
        buttonParams = p

        if (settings.region == null) {
            toast("Долгое нажатие на кнопку — выделить область, обычное — перевести")
        }
    }

    private fun removeViews() {
        listOf<View?>(selectView, translationView, button).forEach { v ->
            if (v != null) try { wm.removeView(v) } catch (_: Exception) {}
        }
        selectView = null
        translationView = null
        button = null
    }

    private fun stopEverything() {
        running = false
        removeViews()
        capturer?.stop()
        capturer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateButton() {
        button?.busy = busy
        button?.showingTranslation = translationView?.hasContent == true
    }

    // ---------------------------------------------------------------- действия
    private fun onButtonTap() {
        if (busy) return
        val tv = translationView
        if (tv != null && tv.hasContent) {
            tv.clear()
            updateButton()
            return
        }
        if (settings.region == null) startSelection() else translate()
    }

    private fun startSelection() {
        if (selectView != null || busy) return
        translationView?.clear()
        updateButton()
        val v = RegionSelectView(this,
            onSelected = { r ->
                closeSelection()
                settings.region = r
                // даём экрану перерисоваться без затемнения
                main.postDelayed({ translate() }, 150)
            },
            onCancel = { closeSelection() },
        )
        val p = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, 0)
        wm.addView(v, p)
        selectView = v
        button?.visibility = View.GONE
    }

    private fun closeSelection() {
        selectView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        selectView = null
        button?.visibility = View.VISIBLE
    }

    private fun translate() {
        val region = settings.region ?: return startSelection()
        val cap = capturer ?: return
        if (busy) return
        busy = true
        updateButton()

        // Прячем свои окна, чтобы они не попали в снимок, и ждём новый кадр
        button?.visibility = View.INVISIBLE
        translationView?.clear()
        main.postDelayed({
            val full = cap.capture()
            button?.visibility = View.VISIBLE
            if (full == null) {
                busy = false
                updateButton()
                toast("Нет кадра экрана, попробуйте ещё раз")
                return@postDelayed
            }
            val r = Rect(region)
            if (!r.intersect(0, 0, full.width, full.height) || r.width() < 10 || r.height() < 10) {
                busy = false
                updateButton()
                toast("Область вне экрана. Выделите её заново (долгое нажатие)")
                return@postDelayed
            }
            val crop = Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height())
            if (crop !== full) full.recycle()
            worker.execute {
                val res = try {
                    pipeline.process(crop, settings)
                } catch (e: Exception) {
                    PageResult(error = "Ошибка: ${e.message}")
                }
                crop.recycle()
                main.post { showResult(res, r) }
            }
        }, 220)
    }

    private fun showResult(res: PageResult, region: Rect) {
        busy = false
        translationView?.show(res.bubbles, region, settings.maxFontSp)
        updateButton()
        when {
            res.error.isNotEmpty() -> toast(res.error)
            res.bubbles.isEmpty() -> toast("Текст не найден в выделенной области")
            res.bubbles.none { it.translation.isNotBlank() } -> toast("Нечего переводить")
        }
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg.take(300), Toast.LENGTH_LONG).show() }
    }
}
