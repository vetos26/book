package com.ishimura.diaries

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/** An offline, dependency-free reader. Pages are laid out for the current screen and font size. */
class MainActivity : Activity() {
    private data class Chapter(val title: String, val text: String)
    private data class Page(val chapter: Int, val start: Int, val end: Int)

    private val ink = Color.rgb(220, 226, 222)
    private val muted = Color.rgb(132, 152, 151)
    private val accent = Color.rgb(111, 213, 204)
    private val background = Color.rgb(10, 17, 20)
    private val panel = Color.rgb(18, 29, 32)
    private val chapters = mutableListOf<Chapter>()
    private val pages = mutableListOf<Page>()
    private val prefs by lazy { getSharedPreferences("reading", MODE_PRIVATE) }
    private var pageIndex = 0
    private var fontSize = 20
    private var screen = "cover"
    private var animating = false
    private var layoutWidth = 0
    private var layoutHeight = 0
    private lateinit var pageText: TextView
    private lateinit var chapterLabel: TextView
    private lateinit var progressLabel: TextView
    private lateinit var pageCard: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = background
        fontSize = prefs.getInt("fontSize", 20).coerceIn(16, 26)
        for (i in 1..10) {
            val file = "chapters/%02d.txt".format(i)
            val raw = assets.open(file).bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
            chapters.add(Chapter(raw.substringBefore('\n').trim(), raw.substringAfter('\n').trim()))
        }
        showCover()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
    private fun shape(color: Int, stroke: Int = color, radius: Int = 0) = GradientDrawable().apply {
        setColor(color); setStroke(dp(1), stroke); cornerRadius = dp(radius).toFloat()
    }
    private fun label(text: String, size: Float, color: Int = ink, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        includeFontPadding = true
    }
    private fun button(text: String, action: () -> Unit): TextView = label(text, 14f, accent, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = shape(panel, Color.rgb(50, 80, 79), 4)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }
    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(this@MainActivity.background)
    }
    private fun space(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }
    private fun line(): View = View(this).apply {
        setBackgroundColor(Color.rgb(44, 69, 68))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun showCover() {
        screen = "cover"
        val scroll = ScrollView(this).apply { setBackgroundColor(this@MainActivity.background); isFillViewport = true }
        val root = column().apply { setPadding(dp(28), dp(30), dp(28), dp(28)) }
        scroll.addView(root)
        root.addView(label("USG ISHIMURA  /  ЛИЧНЫЙ АРХИВ", 11f, accent, true).apply { letterSpacing = .12f })
        root.addView(space(24)); root.addView(line()); root.addView(space(38))
        root.addView(label("ДНЕВНИКИ\nИШИМУРЫ", 38f, ink, true).apply {
            letterSpacing = .035f
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        root.addView(space(18))
        root.addView(label("История о тех, кто остался\nза пределами сигнала.", 17f, muted).apply { setLineSpacing(dp(4).toFloat(), 1f) })
        root.addView(space(42))
        root.addView(label("✦", 38f, accent).apply { gravity = Gravity.CENTER })
        root.addView(space(30))
        root.addView(line()); root.addView(space(18))
        root.addView(label("10 ГЛАВ   ·   НАУЧНАЯ ФАНТАСТИКА   ·   УЖАСЫ", 10f, muted, true).apply { letterSpacing = .06f })
        root.addView(space(24))
        val hasProgress = prefs.contains("chapter")
        root.addView(button(if (hasProgress) "ПРОДОЛЖИТЬ ЧТЕНИЕ  →" else "НАЧАТЬ ЧТЕНИЕ  →") { showReader() }, LinearLayout.LayoutParams(-1, -2))
        root.addView(space(10))
        root.addView(button("СОДЕРЖАНИЕ") { showContents() }, LinearLayout.LayoutParams(-1, -2))
        root.addView(space(22))
        root.addView(label("Неофициальное фанатское произведение по мотивам вселенной Dead Space. Не связано с правообладателями.", 11f, muted).apply { setLineSpacing(dp(2).toFloat(), 1f) })
        setContentView(scroll)
    }

    private fun showContents() {
        val titles = chapters.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Содержание")
            .setItems(titles) { _, which ->
                prefs.edit().putInt("chapter", which).putInt("offset", 0).apply()
                if (screen == "reader") {
                    selectPosition(which, 0)
                    renderPage()
                } else showReader()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun showReader() {
        screen = "reader"
        animating = false
        layoutWidth = 0; layoutHeight = 0
        val root = column().apply { setPadding(dp(20), dp(12), dp(20), dp(14)) }
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(button("←") { showCover() })
        toolbar.addView(label("  АРХИВ / 01", 11f, muted, true).apply { letterSpacing = .08f }, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(button("A−") { changeFont(-2) })
        toolbar.addView(spaceHorizontal(6))
        toolbar.addView(button("A+") { changeFont(2) })
        root.addView(toolbar)
        root.addView(space(18)); root.addView(line()); root.addView(space(16))
        chapterLabel = label("", 12f, accent, true).apply { letterSpacing = .04f }
        root.addView(chapterLabel)
        root.addView(space(12))
        pageCard = FrameLayout(this).apply {
            background = shape(Color.rgb(14, 23, 26), Color.rgb(35, 53, 55), 3)
            cameraDistance = resources.displayMetrics.density * 14000
        }
        pageText = TextView(this).apply {
            setTextColor(ink)
            typeface = Typeface.create("serif", Typeface.NORMAL)
            textSize = fontSize.toFloat()
            setLineSpacing(dp(5).toFloat(), 1f)
            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            setPadding(dp(18), dp(17), dp(18), dp(17))
            gravity = Gravity.TOP
            isFocusable = true
        }
        pageCard.addView(pageText, FrameLayout.LayoutParams(-1, -1))
        root.addView(pageCard, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(space(12))
        progressLabel = label("", 11f, muted, true).apply { gravity = Gravity.CENTER; letterSpacing = .06f }
        root.addView(progressLabel, LinearLayout.LayoutParams(-1, -2))
        root.addView(space(12))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nav.addView(button("← НАЗАД") { turn(-1) }, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(spaceHorizontal(6))
        nav.addView(button("ГЛАВЫ") { showContents() }, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(spaceHorizontal(6))
        nav.addView(button("ВПЕРЁД →") { turn(1) }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(nav)
        setContentView(root)
        installGesture()
        pageText.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val w = pageText.width - pageText.paddingLeft - pageText.paddingRight
            val h = pageText.height - pageText.paddingTop - pageText.paddingBottom
            if (w > 0 && h > 0 && (w != layoutWidth || h != layoutHeight)) {
                layoutWidth = w; layoutHeight = h
                paginate(prefs.getInt("chapter", 0), prefs.getInt("offset", 0))
            }
        }
    }

    private fun spaceHorizontal(width: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(width), 1) }

    private fun paginate(chapter: Int, offset: Int) {
        if (layoutWidth <= 0 || layoutHeight <= 0) return
        pages.clear()
        pageText.textSize = fontSize.toFloat()
        chapters.forEachIndexed { index, item ->
            val text = item.text
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, pageText.paint, layoutWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(dp(5).toFloat(), 1f)
                .setIncludePad(true)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .build()
            var firstLine = 0
            while (firstLine < layout.lineCount) {
                var lastLine = firstLine
                val top = layout.getLineTop(firstLine)
                // Leave a little room for the TextView's first/last-line font padding.
                while (lastLine + 1 < layout.lineCount && layout.getLineBottom(lastLine + 1) - top <= layoutHeight - dp(4)) lastLine++
                // Always advance even on unusually small displays.
                val start = layout.getLineStart(firstLine)
                val end = layout.getLineEnd(lastLine)
                pages.add(Page(index, start, end))
                firstLine = lastLine + 1
            }
        }
        selectPosition(chapter, offset)
        renderPage()
    }

    private fun selectPosition(chapter: Int, offset: Int) {
        val c = chapter.coerceIn(0, chapters.lastIndex)
        val found = pages.indexOfLast { it.chapter == c && it.start <= offset }
        pageIndex = if (found >= 0) found else pages.indexOfFirst { it.chapter == c }.coerceAtLeast(0)
    }

    private fun renderPage() {
        if (pages.isEmpty()) return
        pageIndex = pageIndex.coerceIn(0, pages.lastIndex)
        val page = pages[pageIndex]
        val chapter = chapters[page.chapter]
        chapterLabel.text = chapter.title.uppercase()
        pageText.text = chapter.text.substring(page.start, page.end)
        val chapterPages = pages.count { it.chapter == page.chapter }
        val localPage = pages.take(pageIndex + 1).count { it.chapter == page.chapter }
        progressLabel.text = "СТРАНИЦА $localPage / $chapterPages     ·     ${pageIndex + 1} / ${pages.size}"
        prefs.edit().putInt("chapter", page.chapter).putInt("offset", page.start).apply()
    }

    private fun changeFont(delta: Int) {
        val newSize = (fontSize + delta).coerceIn(16, 26)
        if (fontSize == newSize || animating) return
        val current = pages.getOrNull(pageIndex)
        fontSize = newSize
        prefs.edit().putInt("fontSize", fontSize).apply()
        paginate(current?.chapter ?: 0, current?.start ?: 0)
    }

    private fun turn(direction: Int) {
        if (animating || pages.isEmpty()) return
        val next = pageIndex + direction
        if (next !in pages.indices) return
        animating = true
        pageCard.pivotX = if (direction > 0) 0f else pageCard.width.toFloat()
        val close = ObjectAnimator.ofFloat(pageCard, View.ROTATION_Y, 0f, if (direction > 0) -80f else 80f).setDuration(150)
        close.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                pageIndex = next
                renderPage()
                pageCard.pivotX = if (direction > 0) pageCard.width.toFloat() else 0f
                val open = ObjectAnimator.ofFloat(pageCard, View.ROTATION_Y, if (direction > 0) 80f else -80f, 0f).setDuration(180)
                open.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { animating = false }
                })
                open.start()
            }
        })
        close.start()
    }

    private fun installGesture() {
        var downX = 0f
        var downY = 0f
        pageText.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; true }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) > dp(45) && abs(dx) > abs(dy)) turn(if (dx < 0) 1 else -1)
                    else if (abs(dx) < dp(15) && abs(dy) < dp(15)) turn(if (event.x < pageText.width / 2) -1 else 1)
                    true
                }
                else -> true
            }
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (screen == "reader") showCover() else super.onBackPressed()
    }
}
