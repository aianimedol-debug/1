package com.fishmerge.macro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.random.Random
import org.opencv.core.Rect as CvRect

class MacroService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MacroService? = null
    }

    private enum class SelMode { TEMPLATE, ROI }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager

    private var panel: LinearLayout? = null
    private var panelBody: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var statusView: TextView? = null
    private var startBtn: TextView? = null
    private var profBtn: TextView? = null
    private var selectRoot: View? = null

    private var job: Job? = null
    private var profile = Profile.FISH

    // ------------------------------------------------------------------ 생명주기

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Cv.init()
        showPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopMacro()
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        panel?.let { runCatching { wm.removeView(it) } }
        selectRoot?.let { runCatching { wm.removeView(it) } }
        panel = null
        selectRoot = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 플로팅 패널

    fun showPanel() {
        if (panel != null) {
            panel?.visibility = View.VISIBLE
            return
        }
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(0xDD1E2630.toInt())
                cornerRadius = dp(12).toFloat()
            }
        }

        fun btn(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
            this.text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(0xFF3A4A5C.toInt())
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panelBody = body

        val handle = TextView(this).apply {
            this.text = "☰ 이동/접기"
            setTextColor(0xFFB8C4D0.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        handle.setOnTouchListener(object : View.OnTouchListener {
            var ox = 0
            var oy = 0
            var tx = 0f
            var ty = 0f
            var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val p = panelParams ?: return false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        ox = p.x; oy = p.y; tx = e.rawX; ty = e.rawY; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - tx
                        val dy = e.rawY - ty
                        if (abs(dx) > 10 || abs(dy) > 10) moved = true
                        if (moved) {
                            p.x = ox + dx.toInt()
                            p.y = oy + dy.toInt()
                            panel?.let { wm.updateViewLayout(it, p) }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            val b = panelBody
                            if (b != null) {
                                b.visibility = if (b.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                            }
                        }
                    }
                }
                return true
            }
        })
        root.addView(handle)

        val status = TextView(this).apply {
            this.text = "대기 중"
            setTextColor(0xFFFFE08A.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
        }
        statusView = status
        body.addView(status)

        val start = btn("▶ 시작") { toggleMacro() }
        startBtn = start
        body.addView(start)
        body.addView(btn("📷 그림 등록") { beginSelect(SelMode.TEMPLATE) })
        body.addView(btn("▭ 인식 영역") { beginSelect(SelMode.ROI) })
        val prof = btn("") { switchProfile() }
        profBtn = prof
        body.addView(prof)
        refreshProfileLabel()
        body.addView(btn("✕ 패널 닫기") {
            stopMacro()
            panel?.visibility = View.GONE
        })
        root.addView(body)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp(220)
        }
        panelParams = lp
        panel = root
        wm.addView(root, lp)
    }

    private fun refreshProfileLabel() {
        profBtn?.text = "대상: ${profile.icon} ${profile.label}"
    }

    private fun switchProfile() {
        stopMacro()
        profile = profile.next()
        refreshProfileLabel()
        setStatus("${profile.label} 모드")
    }

    private fun setStatus(s: String) {
        statusView?.text = s
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ 매크로 본체

    private fun toggleMacro() {
        if (job?.isActive == true) stopMacro() else startMacro()
    }

    private fun stopMacro() {
        job?.cancel()
        job = null
        startBtn?.text = "▶ 시작"
        setStatus("정지됨")
    }

    private fun startMacro() {
        if (job?.isActive == true) return
        val prof = profile
        startBtn?.text = "■ 정지"
        job = scope.launch {
            setStatus("준비 중…")
            val scale = Store.getInt(this@MacroService, Cfg.SCALE, Cfg.SCALE_DEF).coerceIn(25, 100) / 100.0
            val partial = Store.getInt(this@MacroService, Cfg.PARTIAL, Cfg.PARTIAL_DEF) == 1
            val colorMode = prof.colorMode(this@MacroService)
            val tpls: List<Tpl> = if (colorMode) emptyList<Tpl>() else
                withContext(Dispatchers.Default) { loadTemplates(prof, scale, partial) }
            val samples: List<ColorSample> = if (colorMode)
                withContext(Dispatchers.Default) { loadSamples(prof) } else emptyList<ColorSample>()
            if (tpls.isEmpty() && samples.isEmpty()) {
                toast(
                    if (colorMode) "등록된 등급이 없어요. 📷 그림 등록으로 등급(색)마다 타일을 하나씩 추가하세요"
                    else "등록된 그림이 없어요. 📷 그림 등록으로 먼저 추가하세요"
                )
                stopMacro()
                return@launch
            }

            var attempts = HashMap<String, Int>()
            var cycle = 0
            var tried = 0
            while (isActive) {
                val bmp = screenshot()
                if (bmp == null) {
                    delay(150)
                    continue
                }
                val screenW = bmp.width
                val screenH = bmp.height
                val thr = Store.getInt(this@MacroService, Cfg.THR, Cfg.THR_DEF).coerceIn(30, 95) / 100.0
                val maxPairs = Store.getInt(this@MacroService, Cfg.PAIRS, Cfg.PAIRS_DEF).coerceIn(1, 10)
                val dragMs = Store.getInt(this@MacroService, Cfg.DRAG_MS, Cfg.DRAG_MS_DEF).coerceIn(30, 600).toLong()
                val gapMs = Store.getInt(this@MacroService, Cfg.GAP_MS, Cfg.GAP_MS_DEF).coerceIn(0, 1000).toLong()
                val roi = Store.roi(this@MacroService, prof)
                val tol = Store.getInt(this@MacroService, Cfg.COLOR_TOL, Cfg.COLOR_TOL_DEF)
                    .coerceIn(6, 40).toDouble()

                val result = withContext(Dispatchers.Default) {
                    analyze(bmp, tpls, samples, roi, scale, thr, tol, maxPairs)
                }
                bmp.recycle()
                val found = result.first
                val pairs = result.second

                if (pairs.isEmpty()) {
                    setStatus("인식 $found · 합칠 쌍 없음")
                    delay(200)
                } else {
                    for (p in pairs) {
                        if (!isActive) break
                        val key = "${p.a.tpl}:${(p.a.cx / 60).toInt()}:${(p.a.cy / 60).toInt()}"
                        val n = attempts[key] ?: 0
                        if (n >= 4) continue // 계속 실패하는 자리는 잠시 건너뜀
                        attempts[key] = n + 1

                        // 재시도일 때는 잡는 위치를 조금씩 바꿔 가려진 개체도 건드려 본다
                        val jx = if (n > 0) Random.nextDouble(-0.18, 0.18) * p.a.w else 0.0
                        val jy = if (n > 0) Random.nextDouble(-0.18, 0.18) * p.a.h else 0.0
                        val ax = (p.a.cx + jx).coerceIn(1.0, screenW - 2.0)
                        val ay = (p.a.cy + jy).coerceIn(1.0, screenH - 2.0)
                        val bx = p.b.cx.coerceIn(1.0, screenW - 2.0)
                        val by = p.b.cy.coerceIn(1.0, screenH - 2.0)

                        val pts = ArrayList<Pair<Float, Float>>()
                        pts.add(Pair(ax.toFloat(), ay.toFloat()))
                        if (p.dist() < 70) {
                            // 거의 포개져 있으면 한번 끌어냈다가 다시 얹어서 '놓기' 동작이 확실히 일어나게 함
                            val side = if (ax + 170 < screenW) 170.0 else -170.0
                            pts.add(Pair((ax + side).toFloat(), ay.toFloat()))
                        }
                        pts.add(Pair(bx.toFloat(), by.toFloat()))
                        swipe(pts, dragMs)
                        tried++
                        if (gapMs > 0) delay(gapMs)
                    }
                    setStatus("인식 $found · 시도 $tried")
                }
                cycle++
                if (cycle % 12 == 0) attempts = HashMap()
            }
        }
    }

    private fun analyze(
        bmp: Bitmap,
        tpls: List<Tpl>,
        samples: List<ColorSample>,
        roi: Rect?,
        scale: Double,
        thr: Double,
        tol: Double,
        maxPairs: Int
    ): Pair<Int, List<MergePair>> {
        val frame = Cv.toBgr(bmp)
        try {
            val cols = frame.cols()
            val rows = frame.rows()
            val r = if (roi != null) {
                val l = roi.left.coerceIn(0, cols - 1)
                val t = roi.top.coerceIn(0, rows - 1)
                CvRect(l, t, maxOf(1, minOf(roi.width(), cols - l)), maxOf(1, minOf(roi.height(), rows - t)))
            } else {
                CvRect(0, 0, cols, rows)
            }
            val dets = if (samples.isNotEmpty()) ColorDetector.detect(frame, r, scale, samples, tol)
            else Detector.detect(frame, r, scale, tpls, thr)
            return Pair(dets.size, Detector.makePairs(dets, maxPairs))
        } finally {
            frame.release()
        }
    }

    /** 총(등급 색상 모드): 등록한 타일 그림 한 장 = 등급 하나 */
    private fun loadSamples(p: Profile): List<ColorSample> {
        val out = ArrayList<ColorSample>()
        for (f in Store.templates(this, p)) {
            if (!Store.isEnabled(this, p, f)) continue
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: continue
            val bgr = Cv.toBgr(bmp)
            bmp.recycle()
            ColorDetector.sample(bgr)?.let { out.add(it) }
            bgr.release()
        }
        return out
    }

    private fun loadTemplates(p: Profile, scale: Double, partial: Boolean): List<Tpl> {
        val out = ArrayList<Tpl>()
        for (f in Store.templates(this, p)) {
            if (!Store.isEnabled(this, p, f)) continue
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: continue
            val bgr = Cv.toBgr(bmp)
            bmp.recycle()
            Detector.prepare(bgr, scale, partial)?.let { out.add(it) }
            bgr.release()
        }
        return out
    }

    // ------------------------------------------------------------------ 화면 캡처 / 제스처

    private suspend fun screenshot(): Bitmap? = suspendCancellableCoroutine<Bitmap?> { cont ->
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        var bmp: Bitmap? = null
                        try {
                            val hb = result.hardwareBuffer
                            bmp = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            hb.close()
                        } catch (e: Exception) {
                            bmp = null
                        }
                        if (cont.isActive) cont.resume(bmp)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    /** 손가락 하나로 pts 를 순서대로 지나가는 드래그를 수행하고 끝날 때까지 기다린다. */
    private suspend fun swipe(pts: List<Pair<Float, Float>>, durMs: Long): Boolean =
        suspendCancellableCoroutine<Boolean> { cont ->
            val path = Path()
            path.moveTo(pts[0].first, pts[0].second)
            for (i in 1 until pts.size) path.lineTo(pts[i].first, pts[i].second)
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durMs))
                .build()
            val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!ok && cont.isActive) cont.resume(false)
        }

    // ------------------------------------------------------------------ 그림/영역 선택 화면

    private fun beginSelect(mode: SelMode) {
        stopMacro()
        if (selectRoot != null) return
        scope.launch {
            panel?.visibility = View.INVISIBLE
            delay(300) // 패널이 화면에서 사라질 시간
            val bmp = screenshot()
            if (bmp == null) {
                panel?.visibility = View.VISIBLE
                toast("화면 캡처에 실패했어요. 다시 시도해 주세요")
                return@launch
            }
            showSelect(bmp, mode)
        }
    }

    private fun showSelect(bmp: Bitmap, mode: SelMode) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val frame = FrameLayout(this)
        var saved = 0

        val info = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        fun refreshInfo() {
            info.text = if (mode == SelMode.TEMPLATE) {
                if (profile.colorMode(this)) {
                    "${profile.icon} 등급(색)마다 타일 1칸을 드래그로 감싸세요 (D, C, B, A … 각각 하나씩)\n" +
                        "총 모양은 상관없고 칸의 바탕색으로 구분해요 · 등록 $saved 개"
                } else {
                    "${profile.icon} ${profile.label}: 합칠 그림 1개를 드래그로 감싸세요\n" +
                        "(겹치지 않은 깨끗한 개체로, 종류마다 하나씩) · 등록 $saved 개"
                }
            } else {
                "인식할 영역을 드래그로 지정하세요\n(이 영역 안에서만 찾고 합칩니다)"
            }
        }
        refreshInfo()

        val sv = SelectView(this, bmp) { rect ->
            if (mode == SelMode.TEMPLATE) {
                val crop = Bitmap.createBitmap(bmp, rect.left, rect.top, rect.width(), rect.height())
                Store.saveTemplate(this, profile, crop)
                crop.recycle()
                saved++
                refreshInfo()
            } else {
                Store.setRoi(this, profile, rect)
                toast("인식 영역을 저장했어요")
                closeSelect()
            }
        }
        frame.addView(
            sv,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(12), dp(36), dp(12), dp(10))
        }
        bar.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val done = TextView(this).apply {
            this.text = if (mode == SelMode.TEMPLATE) "완료" else "취소"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                setColor(0xFF2E7D32.toInt())
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { closeSelect() }
        }
        bar.addView(done)
        frame.addView(
            bar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        )

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        selectRoot = frame
        wm.addView(frame, lp)
    }

    private fun closeSelect() {
        selectRoot?.let { runCatching { wm.removeView(it) } }
        selectRoot = null
        panel?.visibility = View.VISIBLE
    }
}
