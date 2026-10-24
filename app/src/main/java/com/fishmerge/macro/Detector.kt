package com.fishmerge.macro

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.min

object Cv {
    @Volatile
    private var ready = false

    fun init(): Boolean {
        if (!ready) {
            ready = try {
                System.loadLibrary("opencv_java4") // AAR 에 들어 있는 네이티브 라이브러리
                true
            } catch (t: Throwable) {
                false
            }
        }
        return ready
    }

    /** 비트맵(ARGB) -> BGR Mat */
    fun toBgr(bmp: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        return bgr
    }
}

/** 템플릿의 한 변형 (원본/좌우반전, 전체/중앙부분) */
class Variant(val mat: Mat, val core: Boolean)

/** 크기는 축소(scale) 적용된 값 */
class Tpl(val w: Int, val h: Int, val variants: List<Variant>)

/** 검출 결과. 좌표는 전체 화면(원본 해상도) 기준 중심점 */
class Det(val tpl: Int, val cx: Double, val cy: Double, val score: Double, val w: Double, val h: Double)

class MergePair(val a: Det, val b: Det) {
    fun dist(): Double = hypot(a.cx - b.cx, a.cy - b.cy)
}

object Detector {

    /** 겹침으로 일부가 가려진 경우를 위해 중앙부분 매칭은 기준을 이만큼 더 높게 요구한다. */
    private const val CORE_BONUS = 0.10

    fun prepare(bgr: Mat, scale: Double, partial: Boolean): Tpl? {
        val s = Mat()
        Imgproc.resize(bgr, s, Size(), scale, scale, Imgproc.INTER_AREA)
        if (s.cols() < 10 || s.rows() < 10) return null
        val f = Mat()
        Core.flip(s, f, 1) // 물고기는 좌우로 뒤집혀서 헤엄치므로 반전본도 함께 찾는다
        val list = arrayListOf(Variant(s, false), Variant(f, false))
        if (partial) {
            val mx = (s.cols() * 0.22).toInt()
            val my = (s.rows() * 0.22).toInt()
            val r = Rect(mx, my, s.cols() - 2 * mx, s.rows() - 2 * my)
            if (r.width >= 8 && r.height >= 8) {
                list.add(Variant(Mat(s, r).clone(), true))
                list.add(Variant(Mat(f, r).clone(), true))
            }
        }
        return Tpl(s.cols(), s.rows(), list)
    }

    /**
     * frame 의 roi 영역에서 모든 템플릿을 찾는다.
     * thr: 0..1 (감도). 같은 위치에서 여러 종이 잡히면 점수가 가장 높은 것 하나만 남긴다.
     */
    fun detect(frame: Mat, roi: Rect, scale: Double, tpls: List<Tpl>, thr: Double): List<Det> {
        val sub = Mat(frame, roi)
        val small = Mat()
        Imgproc.resize(sub, small, Size(), scale, scale, Imgproc.INTER_AREA)

        val cands = ArrayList<Det>()
        val res = Mat()
        for ((ti, t) in tpls.withIndex()) {
            for (v in t.variants) {
                val vw = v.mat.cols()
                val vh = v.mat.rows()
                if (small.cols() < vw || small.rows() < vh) continue
                Imgproc.matchTemplate(small, v.mat, res, Imgproc.TM_CCOEFF_NORMED)
                Core.patchNaNs(res, 0.0)
                val limit = if (v.core) min(0.97, thr + CORE_BONUS) else thr
                var n = 0
                while (n < 30) {
                    val mm = Core.minMaxLoc(res)
                    if (mm.maxVal < limit) break
                    val loc = mm.maxLoc
                    val cxs = loc.x + vw / 2.0
                    val cys = loc.y + vh / 2.0
                    val adj = if (v.core) mm.maxVal - CORE_BONUS else mm.maxVal
                    cands.add(
                        Det(
                            ti,
                            roi.x + cxs / scale,
                            roi.y + cys / scale,
                            adj,
                            t.w / scale,
                            t.h / scale
                        )
                    )
                    // 이 주변은 다시 잡히지 않게 지운다
                    val hw = vw * 0.5
                    val hh = vh * 0.5
                    Imgproc.rectangle(
                        res,
                        Point(loc.x - hw / 2, loc.y - hh / 2),
                        Point(loc.x + hw / 2, loc.y + hh / 2),
                        Scalar(-1.0),
                        -1
                    )
                    n++
                }
            }
        }
        res.release()
        small.release()
        sub.release()

        // 전체 종류에 걸친 비최대 억제(NMS): 점수 높은 것부터 채택, 가까운 후보는 같은 개체로 본다
        cands.sortByDescending { it.score }
        val out = ArrayList<Det>()
        for (c in cands) {
            val r = 0.30 * min(c.w, c.h)
            var clash = false
            for (a in out) {
                if (hypot(a.cx - c.cx, a.cy - c.cy) < r) {
                    clash = true
                    break
                }
            }
            if (!clash) out.add(c)
        }
        return out
    }

    /** 같은 종끼리 가까운 것부터 짝을 지어 합칠 목록을 만든다. */
    fun makePairs(dets: List<Det>, maxPairs: Int): List<MergePair> {
        val cand = ArrayList<MergePair>()
        for ((_, list) in dets.groupBy { it.tpl }) {
            if (list.size < 2) continue
            val used = BooleanArray(list.size)
            val order = list.indices.sortedByDescending { list[it].score }
            for (i in order) {
                if (used[i]) continue
                var best = -1
                var bd = Double.MAX_VALUE
                for (j in list.indices) {
                    if (j == i || used[j]) continue
                    val d = hypot(list[i].cx - list[j].cx, list[i].cy - list[j].cy)
                    if (d < bd) {
                        bd = d
                        best = j
                    }
                }
                if (best >= 0) {
                    used[i] = true
                    used[best] = true
                    // 더 선명하게 보이는(= 위에 있을 가능성이 큰) 쪽을 잡아서 끈다
                    val a = if (list[i].score >= list[best].score) list[i] else list[best]
                    val b = if (a === list[i]) list[best] else list[i]
                    cand.add(MergePair(a, b))
                }
            }
        }
        cand.sortBy { it.dist() }
        return cand.take(maxPairs)
    }
}
