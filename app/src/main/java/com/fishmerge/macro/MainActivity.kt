package com.fishmerge.macro

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var content: LinearLayout
    private lateinit var statusTv: TextView
    private lateinit var tplBox: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(s: String, size: Float = 14f, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = s
        textSize = size
        if (bold) setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(2))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(32))
        }
        scroll.addView(content)
        setContentView(scroll)
        build()
    }

    override fun onResume() {
        super.onResume()
        statusTv.text = if (MacroService.instance != null) {
            "✅ 접근성 서비스 연결됨 (화면 가장자리에 패널이 떠 있어요)"
        } else {
            "❌ 접근성 서비스가 꺼져 있어요 → 아래 ① 버튼으로 켜 주세요"
        }
        renderTemplates()
    }

    private fun build() {
        content.addView(tv("🐟 생선 합치기 매크로", 22f, true))
        statusTv = tv("", 14f)
        content.addView(statusTv)

        content.addView(Button(this).apply {
            this.text = "① 접근성 서비스 켜기"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        content.addView(Button(this).apply {
            this.text = "② 플로팅 패널 다시 띄우기"
            setOnClickListener {
                val s = MacroService.instance
                if (s == null) Toast.makeText(this@MainActivity, "먼저 접근성 서비스를 켜 주세요", Toast.LENGTH_SHORT).show()
                else s.showPanel()
            }
        })

        content.addView(
            tv(
                "사용법\n" +
                    "1) 게임을 열고, 패널의 [📷 그림 등록]으로 합칠 그림(물고기/총)을 종류마다 하나씩 드래그해서 저장\n" +
                    "   - 안 보이는 종류는 나중에 그 종류가 나왔을 때 다시 등록하면 됩니다\n" +
                    "2) (선택) [▭ 인식 영역]으로 물고기 수조/총 영역만 지정하면 더 빠르고 정확해요\n" +
                    "3) [▶ 시작]을 누르면 같은 그림끼리 자동으로 끌어다 합칩니다\n" +
                    "4) 물고기 ↔ 총은 패널의 [대상] 버튼으로 전환합니다 (그림 목록이 따로 저장돼요)\n" +
                    "5) 총은 '등급(색상) 모드'라서, [대상]을 총으로 바꾸고 등급(D, C, B …)마다 타일 1칸씩만 등록하면 됩니다.\n" +
                    "   총 모양이 달라도 같은 색(등급)끼리 합칩니다. 더 합칠 수 없는 최고 등급은 등록하지 마세요.\n\n" +
                    "※ Android 13 이상에서 서비스 스위치가 회색이면: 설정 > 앱 > 이 앱 > ⋮ > '제한된 설정 허용' 후 다시 켜세요.",
                13f
            )
        )

        content.addView(tv("⚙ 설정", 18f, true))
        slider("감도 (낮을수록 겹친 그림도 잘 잡음, 너무 낮으면 오인식)", Cfg.THR, 40, 90, Cfg.THR_DEF) { "$it%" }
        slider("한 번 캡처할 때 합칠 쌍 수 (많을수록 빠름)", Cfg.PAIRS, 1, 10, Cfg.PAIRS_DEF) { "${it}쌍" }
        slider("드래그 시간 (짧을수록 빠름, 안 먹히면 늘리기)", Cfg.DRAG_MS, 40, 400, Cfg.DRAG_MS_DEF) { "${it}ms" }
        slider("드래그 사이 대기", Cfg.GAP_MS, 0, 400, Cfg.GAP_MS_DEF) { "${it}ms" }
        slider("인식 해상도 (낮을수록 빠름, 높을수록 정밀) ※ 시작할 때 적용", Cfg.SCALE, 30, 100, Cfg.SCALE_DEF) { "$it%" }
        content.addView(CheckBox(this).apply {
            this.text = "총: 등급(색상) 모드 — 총 모양 무관, 타일 색이 같으면 합침 (끄면 그림 매칭)"
            isChecked = Store.getInt(this@MainActivity, Cfg.GUN_COLOR, Cfg.GUN_COLOR_DEF) == 1
            setOnCheckedChangeListener { _, v ->
                Store.putInt(this@MainActivity, Cfg.GUN_COLOR, if (v) 1 else 0)
            }
        })
        slider("총 색상 허용 범위 (크면 관대, 비슷한 등급을 섞어 잡으면 낮추기)", Cfg.COLOR_TOL, 6, 40, Cfg.COLOR_TOL_DEF) { "$it" }
        content.addView(CheckBox(this).apply {
            this.text = "겹침 보정 (일부가 가려진 그림도 중앙 부분만으로 찾기)"
            isChecked = Store.getInt(this@MainActivity, Cfg.PARTIAL, Cfg.PARTIAL_DEF) == 1
            setOnCheckedChangeListener { _, v ->
                Store.putInt(this@MainActivity, Cfg.PARTIAL, if (v) 1 else 0)
            }
        })

        tplBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(tplBox)
    }

    private fun slider(label: String, key: String, lo: Int, hi: Int, def: Int, fmt: (Int) -> String) {
        val cur = Store.getInt(this, key, def).coerceIn(lo, hi)
        val t = tv("$label: ${fmt(cur)}", 13f)
        content.addView(t)
        val sb = SeekBar(this)
        sb.max = hi - lo
        sb.progress = cur - lo
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress + lo
                t.text = "$label: ${fmt(v)}"
                if (fromUser) Store.putInt(this@MainActivity, key, v)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        content.addView(sb)
    }

    private fun renderTemplates() {
        tplBox.removeAllViews()
        for (p in Profile.values()) {
            tplBox.addView(tv("${p.icon} ${p.label} 그림 목록", 18f, true).apply { setPadding(0, dp(20), 0, dp(2)) })
            val roi = Store.roi(this, p)
            tplBox.addView(
                tv(
                    if (roi == null) "인식 영역: 전체 화면"
                    else "인식 영역: (${roi.left}, ${roi.top}) ~ (${roi.right}, ${roi.bottom})",
                    12f
                )
            )
            if (roi != null) {
                tplBox.addView(Button(this).apply {
                    this.text = "인식 영역 초기화"
                    setOnClickListener {
                        Store.setRoi(this@MainActivity, p, null)
                        renderTemplates()
                    }
                })
            }
            val files = Store.templates(this, p)
            if (files.isEmpty()) {
                tplBox.addView(tv("등록된 그림 없음 — 패널의 [📷 그림 등록]을 사용하세요", 13f))
            }
            files.forEachIndexed { i, f ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val iv = ImageView(this).apply {
                    setImageBitmap(BitmapFactory.decodeFile(f.absolutePath))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
                }
                val cb = CheckBox(this).apply {
                    this.text = "#${i + 1} 사용"
                    isChecked = Store.isEnabled(this@MainActivity, p, f)
                    setOnCheckedChangeListener { _, v -> Store.setEnabled(this@MainActivity, p, f, v) }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val del = Button(this).apply {
                    this.text = "삭제"
                    setOnClickListener {
                        Store.delete(this@MainActivity, p, f)
                        renderTemplates()
                    }
                }
                row.addView(iv)
                row.addView(cb)
                row.addView(del)
                tplBox.addView(row)
            }
        }
    }
}
