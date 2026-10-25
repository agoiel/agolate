package com.manhwatranslator

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.manhwatranslator.core.Enhance
import com.manhwatranslator.core.GlossaryStore
import com.manhwatranslator.core.Languages
import com.manhwatranslator.core.PageResult
import com.manhwatranslator.core.Pipeline
import com.manhwatranslator.core.Settings
import com.manhwatranslator.core.Translators
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private companion object {
        const val REQ_CAPTURE = 1
        const val REQ_PICK = 2
        const val REQ_NOTIF = 3
    }

    private lateinit var s: Settings
    private lateinit var ui: Ui
    private val main = Handler(Looper.getMainLooper())
    private var testPipeline: Pipeline? = null

    private lateinit var heroInner: LinearLayout
    private lateinit var statusChip: StatusChip
    private lateinit var startBtn: PillButton
    private lateinit var srcPicker: PickerView
    private lateinit var tgtPicker: PickerView
    private lateinit var glossPicker: PickerView
    private lateinit var keysBox: LinearLayout
    private lateinit var translatorDropdown: Dropdown
    private var lastRunning: Boolean? = null

    private val poll = object : Runnable {
        override fun run() {
            updateRunning()
            main.postDelayed(this, 1000)
        }
    }

    // ================================================================ экран
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        T.init(this)
        s = Settings(this)
        ui = Ui(this)

        val root = ui.vertical()
        root.addView(buildHero())
        root.addView(buildTiles(), lp().margins(this, 16, -34, 16, 0))
        val content = ui.vertical()
        content.addView(buildLanguages(), lp().margins(this, top = 16))
        content.addView(buildTranslator(), lp().margins(this, top = 14))
        content.addView(buildGlossary(), lp().margins(this, top = 14))
        content.addView(buildTuning(), lp().margins(this, top = 14))
        content.addView(ui.caption("agolate 0.1 · распознавание: Google ML Kit").apply {
            gravity = Gravity.CENTER
            alpha = 0.8f
        }, lp().margins(this, top = 22))
        root.addView(content, lp().margins(this, 16, 0, 16, 0))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(T.c.bg)
            addView(root)
        }
        setContentView(scroll)
        setupEdgeToEdge()
        scroll.onSystemBars { top, bottom ->
            heroInner.setPadding(dp(20), top + dp(18), dp(20), dp(56))
            root.setPadding(0, 0, 0, bottom + dp(24))
        }
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshGlossary()
        main.post(poll)
    }

    override fun onPause() {
        main.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() {
        testPipeline?.close()
        super.onDestroy()
    }

    private fun dp(v: Int) = ui.dp(v)

    // ---------------------------------------------------------------- шапка
    private fun buildHero(): View {
        heroInner = ui.vertical().apply { background = HeroDrawable(this@MainActivity, dpF(28f)) }

        val brandRow = ui.horizontal()
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_art)
            scaleType = ImageView.ScaleType.CENTER_CROP
            // показываем середину слоя иконки (как на рабочем столе)
            scaleX = 1.45f
            scaleY = 1.45f
        }
        val logoFrame = FrameLayout(this).apply {
            background = oval(Brand.SAND)
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            addView(logo, FrameLayout.LayoutParams(dp(50), dp(50)))
            foreground = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Brand.CREAM)
            }
        }
        brandRow.addView(logoFrame, LinearLayout.LayoutParams(dp(50), dp(50)))
        val titles = ui.vertical()
        titles.addView(ui.text("agolate", 24f, android.graphics.Color.WHITE, Fonts.medium))
        titles.addView(ui.text("Перевод манхвы прямо с экрана", 13.5f, Brand.CREAM))
        brandRow.addView(titles, lp(0, weight = 1f).margins(this, start = 14))
        heroInner.addView(brandRow)

        statusChip = StatusChip(this)
        heroInner.addView(statusChip, lp(LinearLayout.LayoutParams.WRAP_CONTENT).margins(this, top = 22))

        startBtn = PillButton(this, "", R.drawable.ic_play, PillStyle.HERO, 56)
        startBtn.onClick { onStartClicked() }
        heroInner.addView(startBtn, lp().margins(this, top = 14))

        heroInner.addView(ui.text("«Аа» в читалке — перевести  ·  ещё раз — убрать  ·  долгое нажатие — новая область",
            12.5f, Brand.CREAM).apply { alpha = 0.85f }, lp().margins(this, top = 12))
        updateRunning()
        return heroInner
    }

    private fun updateRunning() {
        val running = OverlayService.running
        if (running == lastRunning) return
        lastRunning = running
        statusChip.set(running, if (running) "Работает — кнопка «Аа» на экране" else "Переводчик выключен")
        if (running) startBtn.set("Остановить переводчик", R.drawable.ic_stop, PillStyle.HERO_OUTLINE)
        else startBtn.set("Запустить переводчик", R.drawable.ic_play, PillStyle.HERO)
    }

    // ---------------------------------------------------------------- быстрые действия
    private fun buildTiles(): View {
        val row = ui.horizontal().apply { gravity = Gravity.TOP }
        val tiles = listOf(
            Tile(this, "Проверить\nна картинке", R.drawable.ic_image) { pickImage() },
            Tile(this, "Новый\nтайтл", R.drawable.ic_refresh) { resetSession() },
            Tile(this, "Как\nпользоваться", R.drawable.ic_help) { showHelp() },
        )
        tiles.forEachIndexed { i, t ->
            row.addView(t, lp(0, weight = 1f).margins(this, start = if (i == 0) 0 else 10))
        }
        return row
    }

    // ---------------------------------------------------------------- языки
    private fun buildLanguages(): View {
        val card = ui.card()
        card.addView(ui.cardHeader("Языки", R.drawable.ic_lang, "Откуда и куда переводить"))
        val row = ui.horizontal()
        srcPicker = PickerView(this, "С ЯЗЫКА") { pickSource() }
        tgtPicker = PickerView(this, "НА ЯЗЫК") { pickTarget() }
        row.addView(srcPicker, lp(0, weight = 1f))
        row.addView(ui.icon(R.drawable.ic_arrow, T.c.primary, 20), LinearLayout.LayoutParams(dp(20), dp(20))
            .margins(this, 8, 0, 8, 0))
        row.addView(tgtPicker, lp(0, weight = 1f))
        card.addView(row, lp().margins(this, top = 14))
        renderLanguages()
        return card
    }

    private fun renderLanguages() {
        srcPicker.setValue(Languages.src(s.sourceLang).name.substringBefore(" ("))
        tgtPicker.setValue(Languages.tgt(s.targetLang).name)
    }

    private fun pickSource() {
        val items = Languages.sources.map { it.name }.toTypedArray()
        val cur = Languages.sources.indexOfFirst { it.code == s.sourceLang }
        AlertDialog.Builder(this).setTitle("Язык оригинала")
            .setSingleChoiceItems(items, cur) { d, which ->
                s.sourceLang = Languages.sources[which].code
                renderLanguages()
                d.dismiss()
            }.show()
    }

    private fun pickTarget() {
        val items = Languages.targets.map { it.name }.toTypedArray()
        val cur = Languages.targets.indexOfFirst { it.code == s.targetLang }
        AlertDialog.Builder(this).setTitle("Переводить на")
            .setSingleChoiceItems(items, cur) { d, which ->
                s.targetLang = Languages.targets[which].code
                renderLanguages()
                d.dismiss()
            }.show()
    }

    // ---------------------------------------------------------------- переводчик
    private fun buildTranslator(): View {
        val card = ui.card()
        card.addView(ui.cardHeader("Переводчик", R.drawable.ic_bolt, "Чем переводить текст облаков"))
        translatorDropdown = Dropdown(this, "ПЕРЕВОДЧИК",
            Translators.all.map { Dropdown.Item(it.key, it.title, it.subtitle, it.group) }) { selectTranslator(it) }
        card.addView(translatorDropdown, lp().margins(this, top = 14))

        keysBox = ui.vertical()
        card.addView(keysBox, lp().margins(this, top = 4))

        card.addView(View(this).apply { setBackgroundColor(T.c.border) }, lp(h = dp(1)).margins(this, top = 16))
        card.addView(Stepper(this, "Контекст", "Сколько прошлых реплик учитывать", s.contextSize.toFloat(),
            1f, 0f, 40f, { it.toInt().toString() }) { s.contextSize = it.toInt() }, lp().margins(this, top = 12))
        card.addView(Stepper(this, "Пауза между запросами", "Защищает от блокировки за частые запросы",
            s.requestIntervalSec, 0.5f, 0f, 10f, { String.format("%.1f с", it) }) { s.requestIntervalSec = it },
            lp().margins(this, top = 12))
        selectTranslator(s.translator)
        return card
    }

    private fun selectTranslator(key: String) {
        s.translator = key
        translatorDropdown.select(key)
        keysBox.removeAllViews()
        fun field(label: String, value: String, hint: String, secret: Boolean = false, save: (String) -> Unit) {
            keysBox.addView(Field(this, label, value, hint, secret) { save(it.trim()) }, lp().margins(this, top = 12))
        }
        fun note(text: String) {
            keysBox.addView(ui.caption(text), lp().margins(this, top = 12))
        }
        fun link(text: String, url: String) {
            val row = ui.horizontal().apply {
                isClickable = true
                background = pressable(null, dpF(10f))
                setPadding(dp(4), dp(8), dp(4), dp(8))
                addView(ui.icon(R.drawable.ic_link, T.c.primary, 18))
                addView(ui.text(text, 14f, T.c.primary, Fonts.medium), lp(LinearLayout.LayoutParams.WRAP_CONTENT)
                    .margins(this@MainActivity, start = 8))
                setOnClickListener {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                }
            }
            keysBox.addView(row, lp(LinearLayout.LayoutParams.WRAP_CONTENT).margins(this, top = 6))
        }
        when (key) {
            "google_free" -> note("Ключ не нужен. Это неофициальный способ: при VPN Google часто ограничивает " +
                "запросы — тогда выберите Lingva или Яндекс.")
            "yandex" -> {
                note("Работает без ключа. Если бесплатный способ перестанет отвечать, укажите ключ " +
                    "Yandex Cloud — тогда перевод пойдёт через официальный API (платно, по символам).")
                field("API-ключ Yandex Cloud (необязательно)", s.yandexKey, "AQVN…", secret = true) { s.yandexKey = it }
                field("ID каталога (нужен вместе с ключом)", s.yandexFolder, "b1g…") { s.yandexFolder = it }
                link("Как получить ключ", "https://yandex.cloud/ru/docs/translate/quickstart")
            }
            "duckduckgo" -> note("Ключ не нужен. DuckDuckGo переводит через Microsoft Bing. Способ неофициальный " +
                "и может временно не отвечать.")
            "lingva" -> {
                note("Ключ не нужен. Lingva — открытый посредник к Google Переводчику: Google видит запрос " +
                    "от сервера Lingva, а не от вас, поэтому ограничения VPN обычно не мешают.")
                field("Адрес сервера Lingva", s.lingvaUrl, "https://lingva.ml") {
                    s.lingvaUrl = it.ifEmpty { "https://lingva.ml" }
                }
                link("Список других серверов", "https://github.com/thedaviddelta/lingva-translate#instances")
            }
            "papago" -> {
                note("Переводчик Naver — лучший для корейского. Нужна регистрация в Naver Cloud Platform. " +
                    "На русский Papago переводит только с корейского и английского.")
                field("Client ID", s.papagoId, "", secret = true) { s.papagoId = it }
                field("Client Secret", s.papagoSecret, "", secret = true) { s.papagoSecret = it }
                link("Открыть Naver Cloud Platform", "https://www.ncloud.com/product/aiService/papagoTranslation")
            }
            "claude" -> {
                field("API-ключ Claude", s.claudeKey, "sk-ant-…", secret = true) { s.claudeKey = it }
                field("Модель", s.claudeModel, "claude-haiku-5-5") { s.claudeModel = it.ifEmpty { "claude-haiku-5-5" } }
                link("Получить ключ", "https://platform.claude.com")
            }
            "deepl" -> {
                field("API-ключ DeepL", s.deeplKey, "xxxxxxxx-xxxx:fx", secret = true) { s.deeplKey = it }
                link("Получить бесплатный ключ", "https://www.deepl.com/pro-api")
            }
            "microsoft" -> {
                field("Ключ Azure Translator", s.microsoftKey, "ключ ресурса", secret = true) { s.microsoftKey = it }
                field("Регион", s.microsoftRegion, "global") { s.microsoftRegion = it.ifEmpty { "global" } }
                link("Открыть портал Azure", "https://portal.azure.com")
            }
            "gemini" -> {
                field("API-ключ Gemini", s.geminiKey, "AIza…", secret = true) { s.geminiKey = it }
                field("Модель", s.geminiModel, "gemini-2.5-flash-lite") {
                    s.geminiModel = it.ifEmpty { "gemini-2.5-flash-lite" }
                }
                link("Получить ключ в Google AI Studio", "https://aistudio.google.com/apikey")
            }
            "openai" -> {
                field("Адрес сервера", s.openaiUrl, "https://api.openai.com/v1") { s.openaiUrl = it }
                field("API-ключ", s.openaiKey, "sk-…", secret = true) { s.openaiKey = it }
                field("Модель", s.openaiModel, "gpt-4o-mini") { s.openaiModel = it }
            }
        }
    }

    // ---------------------------------------------------------------- глоссарий
    private fun buildGlossary(): View {
        val card = ui.card()
        card.addView(ui.cardHeader("Глоссарий тайтла", R.drawable.ic_book, "Имена и термины — всегда одинаково"))
        glossPicker = PickerView(this, "АКТИВНЫЙ ГЛОССАРИЙ") { pickGlossary() }
        card.addView(glossPicker, lp().margins(this, top = 14))
        val row = ui.horizontal()
        val edit = PillButton(this, "Изменить", null, PillStyle.OUTLINE, 44)
        edit.onClick { openGlossary(s.glossary.ifBlank { null }) }
        val create = PillButton(this, "Новый", R.drawable.ic_plus, PillStyle.TONAL, 44)
        create.onClick { openGlossary(null) }
        row.addView(edit, lp(0, weight = 1f))
        row.addView(create, lp(0, weight = 1f).margins(this, start = 10))
        card.addView(row, lp().margins(this, top = 12))
        return card
    }

    private fun refreshGlossary() {
        val g = s.glossary.takeIf { it.isNotBlank() }?.let { GlossaryStore.load(this, it) }
        if (g == null && s.glossary.isNotBlank()) s.glossary = ""
        glossPicker.setValue(g?.let { "${it.name.ifBlank { s.glossary }} · ${it.entries.size} зап." } ?: "Без глоссария")
    }

    private fun pickGlossary() {
        val files = GlossaryStore.list(this)
        val names = listOf("Без глоссария") + files.map { GlossaryStore.load(this, it)?.name?.ifBlank { null } ?: it }
        val cur = files.indexOf(s.glossary) + 1
        AlertDialog.Builder(this).setTitle("Глоссарий")
            .setSingleChoiceItems(names.toTypedArray(), cur) { d, which ->
                s.glossary = if (which == 0) "" else files[which - 1]
                refreshGlossary()
                d.dismiss()
            }.show()
    }

    private fun openGlossary(file: String?) {
        val i = Intent(this, GlossaryActivity::class.java)
        if (file != null) i.putExtra(GlossaryActivity.EXTRA_FILE, file)
        startActivity(i)
    }

    // ---------------------------------------------------------------- тонкая настройка
    private fun buildTuning(): View {
        val card = ui.card()
        val header = ui.horizontal().apply { isClickable = true }
        header.addView(ui.cardHeader("Тонкая настройка", R.drawable.ic_tune, "Распознавание и вид перевода"),
            lp(0, weight = 1f))
        val chevron = ui.icon(R.drawable.ic_chevron, T.c.primary, 22)
        header.addView(chevron)
        card.addView(header)

        val body = ui.vertical().apply { visibility = View.GONE }
        val modes = Dropdown(this, "РАСПОЗНАВАНИЕ ТЕКСТА", Enhance.modes.map {
            Dropdown.Item(it.first.toString(), it.second, it.third, "")
        }) { s.ocrMode = it.toInt() }
        modes.select(s.ocrMode.toString())
        body.addView(modes, lp().margins(this, top = 16))
        body.addView(ui.caption("Если текст на тёмном или пёстром фоне не находится, выберите режим потщательнее. " +
            "Каждый проход добавляет примерно 0,2–0,4 с."), lp().margins(this, top = 6))
        body.addView(Stepper(this, "Склейка строк", "Больше — охотнее собирает строки в одно облако",
            s.bubbleGap, 0.1f, 0.2f, 4f, { String.format("%.1f", it) }) { s.bubbleGap = it },
            lp().margins(this, top = 16))
        body.addView(Stepper(this, "Шрифт перевода", "Максимальный размер", s.maxFontSp.toFloat(),
            1f, 8f, 40f, { "${it.toInt()} sp" }) { s.maxFontSp = it.toInt() }, lp().margins(this, top = 14))
        body.addView(SwitchRow(this, "Не переводить звуки", "Текст поверх рисунка, а не в облаке", s.skipFreeText) {
            s.skipFreeText = it
        }, lp().margins(this, top = 10))
        body.addView(SwitchRow(this, "Пропускать обрезанное", "Текст, который режет край области", s.skipCut) {
            s.skipCut = it
        }, lp().margins(this, top = 2))
        card.addView(body)

        header.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            chevron.animate().rotation(if (open) 180f else 0f).setDuration(180).start()
        }
        return card
    }

    // ================================================================ запуск плавающей кнопки
    private fun onStartClicked() {
        if (OverlayService.running) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            main.postDelayed({ updateRunning() }, 300)
            return
        }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Нужно разрешение")
                .setMessage("Чтобы кнопка и перевод появлялись поверх читалки, разрешите приложению " +
                    "«Показ поверх других приложений». Затем вернитесь сюда и снова нажмите «Запустить».")
                .setPositiveButton("Открыть настройки") { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        requestCapture()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) requestCapture()
    }

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // весь экран, а не одно приложение — иначе координаты области не совпадут
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        startActivityForResult(intent, REQ_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_CAPTURE -> {
                if (resultCode != RESULT_OK || data == null) {
                    toast("Без разрешения на запись экрана переводчик не сможет видеть текст")
                    return
                }
                val i = Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_START)
                    .putExtra(OverlayService.EXTRA_CODE, resultCode)
                    .putExtra(OverlayService.EXTRA_DATA, data)
                startForegroundService(i)
                toast("Готово! Откройте читалку и нажмите на кнопку «Аа»")
                main.postDelayed({ updateRunning(); moveTaskToBack(true) }, 400)
            }
            REQ_PICK -> data?.data?.let { translateImage(it) }
        }
    }

    // ================================================================ проверка на картинке
    private fun pickImage() {
        startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_PICK)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri != null) translateImage(uri)
    }

    private fun translateImage(uri: Uri) {
        val progress = Dialogs.progress(this, "Распознаю и перевожу…")
        thread {
            val res: PageResult = try {
                val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                if (bmp == null) {
                    PageResult(error = "Не удалось открыть картинку")
                } else {
                    val soft = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
                    val p = testPipeline ?: Pipeline(this).also { testPipeline = it }
                    p.process(cropStatusBar(soft), s)
                }
            } catch (e: Exception) {
                PageResult(error = "Ошибка: ${e.message}")
            }
            main.post {
                progress.dismiss()
                Dialogs.result(this, res)
            }
        }
    }

    /** Если это скриншот всего экрана — отрезаем строку состояния (часы, значки, заряд). */
    private fun cropStatusBar(bmp: Bitmap): Bitmap {
        val screenW = resources.displayMetrics.widthPixels
        val looksLikeScreenshot = kotlin.math.abs(bmp.width - screenW) <= 4 && bmp.height > bmp.width
        if (!looksLikeScreenshot) return bmp
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val barH = if (id > 0) resources.getDimensionPixelSize(id) else dp(28)
        if (barH <= 0 || barH >= bmp.height / 4) return bmp
        return Bitmap.createBitmap(bmp, 0, barH, bmp.width, bmp.height - barH)
    }

    private fun resetSession() {
        testPipeline?.reset()
        if (OverlayService.running) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_RESET))
        }
        toast("Контекст и кэш очищены — можно читать новый тайтл")
    }

    private fun showHelp() = Dialogs.help(this)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
