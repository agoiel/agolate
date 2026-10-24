package com.manhwatranslator

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.manhwatranslator.core.Glossary
import com.manhwatranslator.core.GlossaryStore
import com.manhwatranslator.core.Settings

/**
 * Редактор глоссария. Каждая строка: «оригинал = перевод | заметка».
 * Заметка необязательна и помогает ИИ-переводчикам (пол, кто это, как обращаться).
 */
class GlossaryActivity : Activity() {

    companion object {
        const val EXTRA_FILE = "file"
    }

    private var file: String? = null
    private lateinit var settings: Settings
    private lateinit var ui: Ui
    private lateinit var nameField: Field
    private lateinit var bodyField: Field
    private lateinit var counter: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        T.init(this)
        ui = Ui(this)
        settings = Settings(this)
        file = intent.getStringExtra(EXTRA_FILE)
        val g = file?.let { GlossaryStore.load(this, it) } ?: Glossary("", settings.sourceLang)
        val c = T.c

        val page = ui.vertical().apply { setBackgroundColor(c.bg) }

        // ---- верхняя панель
        val bar = ui.horizontal().apply { setBackgroundColor(Brand.DEEP) }
        val back = FrameLayout(this).apply {
            isClickable = true
            background = pressable(null, dpF(20f), android.graphics.Color.argb(60, 255, 255, 255))
            addView(ImageView(this@GlossaryActivity).apply { setImageDrawable(tinted(R.drawable.ic_back, Brand.ICE)) },
                FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener { finish() }
        }
        bar.addView(back, LinearLayout.LayoutParams(dp(40), dp(40)))
        val titles = ui.vertical()
        titles.addView(ui.text(if (file == null) "Новый глоссарий" else "Глоссарий", 19f,
            android.graphics.Color.WHITE, Fonts.medium))
        titles.addView(ui.text("Имена и термины тайтла", 12.5f, Brand.ICE))
        bar.addView(titles, lp(0, weight = 1f).margins(this, start = 8))
        page.addView(bar)

        // ---- содержимое
        val content = ui.vertical().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        nameField = Field(this, "НАЗВАНИЕ ТАЙТЛА", g.name, "Например: мой любимый тайтл")
        content.addView(nameField)

        val help = ui.vertical().apply {
            background = rounded(c.selectedBg, dpF(16f))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        help.addView(ui.text("Одна запись на строку", 14f, c.text, Fonts.medium))
        help.addView(ui.text("헌터 = охотник\n형 = хён | обращение к старшему мужчине", 13.5f, c.primary, Fonts.mono),
            lp().margins(this, top = 6))
        help.addView(ui.caption("После | можно добавить заметку: пол, кто это, как обращаться. " +
            "Она помогает ИИ-переводчикам."), lp().margins(this, top = 6))
        content.addView(help, lp().margins(this, top = 14))

        bodyField = Field(this, "ЗАПИСИ", g.toText(), "оригинал = перевод | заметка", multiline = true) { updateCounter() }
        content.addView(bodyField, lp().margins(this, top = 14))
        counter = ui.caption("")
        content.addView(counter, lp().margins(this, top = 6))
        updateCounter()

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        page.addView(scroll, lp(h = 0, weight = 1f))

        // ---- нижняя панель
        val bottom = ui.horizontal().apply {
            setBackgroundColor(c.surface)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        if (file != null) {
            val del = PillButton(this, "Удалить", null, PillStyle.OUTLINE, 48)
            del.onClick { confirmDelete() }
            bottom.addView(del, lp(0, weight = 1f).margins(this, end = 10))
        }
        val save = PillButton(this, "Сохранить", R.drawable.ic_check, PillStyle.PRIMARY, 48)
        save.onClick { save() }
        bottom.addView(save, lp(0, weight = 2f))
        page.addView(View(this).apply { setBackgroundColor(c.border) }, lp(h = dp(1)))
        page.addView(bottom)

        setContentView(page)
        setupEdgeToEdge()
        page.onSystemBars { top, bottomInset ->
            bar.setPadding(dp(8), top + dp(8), dp(16), dp(12))
            bottom.setPadding(dp(16), dp(12), dp(16), bottomInset + dp(12))
        }
    }

    private fun updateCounter() {
        val n = Glossary.parseText(bodyField.edit.text.toString()).size
        counter.text = "Записей: $n"
    }

    private fun save() {
        val name = nameField.edit.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Введите название тайтла", Toast.LENGTH_SHORT).show()
            nameField.edit.requestFocus()
            return
        }
        val entries = Glossary.parseText(bodyField.edit.text.toString())
        val target = file ?: GlossaryStore.fileNameFor(this, name)
        GlossaryStore.save(this, target, Glossary(name, settings.sourceLang, entries))
        settings.glossary = target
        Toast.makeText(this, "Сохранено и выбрано: ${entries.size} записей", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmDelete() {
        val f = file ?: return finish()
        AlertDialog.Builder(this)
            .setTitle("Удалить глоссарий?")
            .setMessage("Записи этого тайтла будут удалены без возможности восстановления.")
            .setPositiveButton("Удалить") { _, _ ->
                GlossaryStore.delete(this, f)
                if (settings.glossary == f) settings.glossary = ""
                finish()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun dp(v: Int) = ui.dp(v)
}
