package com.fishmerge.macro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import java.io.File

/** 합치기 대상 종류. 종류별로 템플릿(그림)과 인식 영역을 따로 저장한다. */
enum class Profile(val id: String, val label: String, val icon: String) {
    FISH("fish", "물고기", "🐟"),
    GUN("gun", "총", "🔫");

    fun next(): Profile = if (this == FISH) GUN else FISH

    /** 총은 기본적으로 '등급(타일 색)'으로 찾는다. 설정에서 끄면 그림 매칭으로 동작. */
    fun colorMode(c: Context): Boolean =
        this == GUN && Store.getInt(c, Cfg.GUN_COLOR, Cfg.GUN_COLOR_DEF) == 1
}

object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("fishmerge", Context.MODE_PRIVATE)

    // ---------- 템플릿 ----------
    fun dir(c: Context, p: Profile): File = File(c.filesDir, "tpl/${p.id}").also { it.mkdirs() }

    fun templates(c: Context, p: Profile): List<File> =
        dir(c, p).listFiles { f -> f.extension == "png" }?.sortedBy { it.name } ?: emptyList()

    private fun key(p: Profile, f: File) = "${p.id}/${f.name}"

    fun isEnabled(c: Context, p: Profile, f: File): Boolean {
        val set = prefs(c).getStringSet("disabled", emptySet()) ?: emptySet()
        return !set.contains(key(p, f))
    }

    fun setEnabled(c: Context, p: Profile, f: File, enabled: Boolean) {
        val set = HashSet(prefs(c).getStringSet("disabled", emptySet()) ?: emptySet())
        if (enabled) set.remove(key(p, f)) else set.add(key(p, f))
        prefs(c).edit().putStringSet("disabled", set).apply()
    }

    fun delete(c: Context, p: Profile, f: File) {
        f.delete()
        setEnabled(c, p, f, true)
    }

    fun saveTemplate(c: Context, p: Profile, bmp: Bitmap): File {
        val f = File(dir(c, p), "t_${System.currentTimeMillis()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    // ---------- 인식 영역(ROI) ----------
    fun roi(c: Context, p: Profile): Rect? {
        val s = prefs(c).getString("roi_${p.id}", null) ?: return null
        val v = s.split(",").mapNotNull { it.toIntOrNull() }
        return if (v.size == 4) Rect(v[0], v[1], v[2], v[3]) else null
    }

    fun setRoi(c: Context, p: Profile, r: Rect?) {
        val e = prefs(c).edit()
        if (r == null) e.remove("roi_${p.id}")
        else e.putString("roi_${p.id}", "${r.left},${r.top},${r.right},${r.bottom}")
        e.apply()
    }

    // ---------- 설정값 ----------
    fun getInt(c: Context, key: String, def: Int): Int = prefs(c).getInt(key, def)
    fun putInt(c: Context, key: String, v: Int) = prefs(c).edit().putInt(key, v).apply()
}

/** 설정 키 + 기본값 */
object Cfg {
    const val THR = "thr"            // 감도(%) : 낮을수록 겹친 그림도 잘 잡지만 오인식 증가
    const val PAIRS = "pairs"        // 한 번 캡처할 때 합칠 쌍 수
    const val DRAG_MS = "dragMs"     // 드래그 한 번에 걸리는 시간
    const val GAP_MS = "gapMs"       // 드래그 사이 대기
    const val SCALE = "scale"        // 인식 해상도 비율(%)
    const val PARTIAL = "partial"    // 겹침 보정(부분 매칭) 1/0
    const val GUN_COLOR = "gunColor" // 총: 등급(색상) 모드 1/0
    const val COLOR_TOL = "colorTol" // 색상 허용 범위

    const val THR_DEF = 62
    const val PAIRS_DEF = 4
    const val DRAG_MS_DEF = 110
    const val GAP_MS_DEF = 40
    const val SCALE_DEF = 50
    const val PARTIAL_DEF = 1
    const val GUN_COLOR_DEF = 1
    const val COLOR_TOL_DEF = 18
}
