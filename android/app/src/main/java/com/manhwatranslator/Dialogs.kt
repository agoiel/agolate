package com.manhwatranslator

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.content.res.ColorStateList
import com.manhwatranslator.core.PageResult

/** Диалоги в стиле приложения: ожидание, результат перевода, справка. */
object Dialogs {

    private fun base(ctx: Context, content: LinearLayout, cancelable: Boolean = true): Dialog {
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setCancelable(cancelable)
        content.background = rounded(T.c.surface, ctx.dpF(24f))
        val wrap = FrameLayout(ctx).apply {
            setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        d.setContentView(wrap)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return d
    }

    fun progress(ctx: Context, text: String): Dialog {
        val ui = Ui(ctx)
        val box = ui.horizontal().apply { setPadding(ctx.dp(22), ctx.dp(22), ctx.dp(22), ctx.dp(22)) }
        val bar = ProgressBar(ctx).apply { indeterminateTintList = ColorStateList.valueOf(T.c.primary) }
        box.addView(bar, LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36)))
        box.addView(ui.text(text, 15.5f, T.c.text, Fonts.medium), lp(0, weight = 1f).margins(ctx, start = 16))
        return base(ctx, box, cancelable = false).also { it.show() }
    }

    fun result(ctx: Context, res: PageResult) {
        val ui = Ui(ctx)
        val c = T.c
        val box = ui.vertical().apply { setPadding(ctx.dp(20), ctx.dp(20), ctx.dp(20), ctx.dp(16)) }
        box.addView(ui.text("Результат", 20f, c.text, Fonts.medium))
        val stats = buildString {
            val n = res.bubbles.count { it.translation.isNotBlank() }
            append("Облаков: $n · OCR ${res.ocrMs} мс · перевод ${res.translateMs} мс")
            if (res.cacheHits > 0) append(" · из кэша ${res.cacheHits}")
        }
        box.addView(ui.caption(stats), lp().margins(ctx, top = 2))

        val list = ui.vertical()
        if (res.error.isNotEmpty()) {
            list.addView(ui.text(res.error, 14f, c.text).apply {
                background = rounded(c.selectedBg, ctx.dpF(14f))
                setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
            }, lp().margins(ctx, top = 12))
        }
        if (res.bubbles.isEmpty() && res.error.isEmpty()) {
            list.addView(ui.caption("Текст не найден. Попробуйте картинку крупнее или другой язык оригинала."),
                lp().margins(ctx, top = 12))
        }
        res.bubbles.forEachIndexed { i, b ->
            val item = ui.horizontal().apply {
                gravity = Gravity.TOP
                background = rounded(c.surfaceAlt, ctx.dpF(14f), ctx.dp(1), c.border)
                setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(14), ctx.dp(12))
            }
            val badge = TextView(ctx).apply {
                text = (i + 1).toString()
                textSize = 12f
                typeface = Fonts.medium
                gravity = Gravity.CENTER
                setTextColor(c.primary)
                background = oval(c.selectedBg)
            }
            item.addView(badge, LinearLayout.LayoutParams(ctx.dp(26), ctx.dp(26)))
            val col = ui.vertical()
            if (b.translation.isNotBlank()) {
                col.addView(ui.text(b.translation, 15.5f, c.text, Fonts.medium).apply { setTextIsSelectable(true) })
            } else {
                col.addView(ui.text("— ${b.skipReason.ifBlank { "нет перевода" }} —", 14f, c.text2))
            }
            col.addView(ui.text(b.text, 12.5f, c.text2).apply { setTextIsSelectable(true) }, lp().margins(ctx, top = 4))
            item.addView(col, lp(0, weight = 1f).margins(ctx, start = 12))
            list.addView(item, lp().margins(ctx, top = 8))
        }
        val maxH = (ctx.resources.displayMetrics.heightPixels * 0.62f).toInt()
        box.addView(MaxHeightScrollView(ctx, maxH).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }, lp().margins(ctx, top = 6))

        val d = base(ctx, box)
        val ok = PillButton(ctx, "Готово", null, PillStyle.PRIMARY, 48)
        ok.onClick { d.dismiss() }
        box.addView(ok, lp().margins(ctx, top = 16))
        d.show()
    }

    fun help(ctx: Context) {
        val ui = Ui(ctx)
        val c = T.c
        val box = ui.vertical().apply { setPadding(ctx.dp(20), ctx.dp(20), ctx.dp(20), ctx.dp(16)) }
        box.addView(ui.text("Как пользоваться", 20f, c.text, Fonts.medium))
        val steps = listOf(
            "Нажмите «Запустить переводчик». Разрешите показ поверх других приложений и запись экрана — выберите «Весь экран».",
            "Откройте читалку. Справа появится круглая кнопка «Аа» — её можно перетащить куда удобно.",
            "Нажмите «Аа»: в первый раз выделите пальцем область чтения, дальше она запомнится.",
            "Перевод появится прямо поверх облаков. Нажмите «Аа» ещё раз — перевод исчезнет, листайте дальше.",
            "Долгое нажатие на «Аа» — выделить новую область. Остановить переводчик можно здесь или из уведомления.",
        )
        val list = ui.vertical()
        steps.forEachIndexed { i, t ->
            val row = ui.horizontal().apply { gravity = Gravity.TOP }
            row.addView(TextView(ctx).apply {
                text = (i + 1).toString()
                textSize = 13f
                typeface = Fonts.medium
                gravity = Gravity.CENTER
                setTextColor(c.onPrimary)
                background = oval(c.primary)
            }, LinearLayout.LayoutParams(ctx.dp(26), ctx.dp(26)))
            row.addView(ui.text(t, 14.5f, c.text), lp(0, weight = 1f).margins(ctx, start = 12))
            list.addView(row, lp().margins(ctx, top = 14))
        }
        list.addView(ui.caption("Android спрашивает разрешение на запись экрана при каждом запуске переводчика — " +
            "это ограничение системы. При блокировке экрана запись может остановиться, тогда запустите снова.").apply {
            background = rounded(c.selectedBg, ctx.dpF(14f))
            setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
        }, lp().margins(ctx, top = 18))
        val maxH = (ctx.resources.displayMetrics.heightPixels * 0.62f).toInt()
        box.addView(MaxHeightScrollView(ctx, maxH).apply { addView(list) })
        val d = base(ctx, box)
        val ok = PillButton(ctx, "Понятно", null, PillStyle.PRIMARY, 48)
        ok.onClick { d.dismiss() }
        box.addView(ok, lp().margins(ctx, top = 16))
        d.show()
    }
}
