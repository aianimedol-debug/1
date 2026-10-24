package com.fishmerge.macro

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** 등급 하나를 대표하는 타일 바탕색(Lab)과 타일 크기 */
class ColorSample(val l: Double, val a: Double, val b: Double, val w: Int, val h: Int)

/**
 * 총 합치기용: 그림이 아니라 '타일 바탕색(등급 색)'으로 같은 등급을 찾는다.
 * 총 모양이 달라도 같은 등급이면 합쳐지기 때문에 등급마다 타일 1개만 등록하면 된다.
 */
object ColorDetector {

    private fun toLab(bgr: Mat): Mat {
        val lab = Mat()
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
        return lab
    }

    /** 영역 안에서 가장 많이 쓰인 색(= 타일 바탕색)의 Lab 평균 */
    private fun dominant(lab: Mat, r: Rect): DoubleArray? {
        val sub = Mat(lab, r).clone() // 연속 메모리로 복사
        val n = sub.rows() * sub.cols()
        if (n <= 0) {
            sub.release()
            return null
        }
        val data = ByteArray(n * 3)
        sub.get(0, 0, data)
        sub.release()
        val sums = HashMap<Int, DoubleArray>() // key -> [개수, ΣL, Σa, Σb]
        for (i in 0 until n) {
            val l = data[i * 3].toInt() and 0xFF
            val a = data[i * 3 + 1].toInt() and 0xFF
            val b = data[i * 3 + 2].toInt() and 0xFF
            val key = (l / 16) * 10000 + (a / 8) * 100 + (b / 8)
            val s = sums.getOrPut(key) { DoubleArray(4) }
            s[0] += 1.0
            s[1] += l.toDouble()
            s[2] += a.toDouble()
            s[3] += b.toDouble()
        }
        val best = sums.values.maxByOrNull { it[0] } ?: return null
        return doubleArrayOf(best[1] / best[0], best[2] / best[0], best[3] / best[0])
    }

    /** 사용자가 드래그로 저장한 타일 그림 한 장에서 대표색을 뽑는다. */
    fun sample(bgr: Mat): ColorSample? {
        val lab = toLab(bgr)
        val d = dominant(lab, Rect(0, 0, bgr.cols(), bgr.rows()))
        lab.release()
        return d?.let { ColorSample(it[0], it[1], it[2], bgr.cols(), bgr.rows()) }
    }

    private fun dist(a: DoubleArray, s: ColorSample): Double {
        val dl = (a[0] - s.l) * 0.5
        val da = a[1] - s.a
        val db = a[2] - s.b
        return Math.sqrt(dl * dl + da * da + db * db)
    }

    /**
     * roi 안에서 각 등급 색의 타일을 찾는다. 좌표는 전체 화면(원본 해상도) 기준 중심점.
     * tol: 색 허용 범위 (a/b 기준, 밝기(L)는 2.2배 여유). 클수록 관대하다.
     * 비슷한 색의 등급(예: 하늘색 B / 청록 X)은 칸의 실제 색과 가장 가까운 등급 한 곳에만 배정한다.
     */
    fun detect(frame: Mat, roi: Rect, scale: Double, samples: List<ColorSample>, tol: Double): List<Det> {
        val sub = Mat(frame, roi)
        val small = Mat()
        Imgproc.resize(sub, small, Size(), scale, scale, Imgproc.INTER_AREA)
        sub.release()
        val lab = toLab(small)
        small.release()

        val tolL = tol * 2.2
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val mask = Mat()
        val closed = Mat()
        val out = ArrayList<Det>()

        for ((si, s) in samples.withIndex()) {
            Core.inRange(
                lab,
                Scalar(s.l - tolL, s.a - tol, s.b - tol),
                Scalar(s.l + tolL, s.a + tol, s.b + tol),
                mask
            )
            // 타일 안의 총 그림·글자 때문에 생기는 작은 구멍을 메운다
            Imgproc.morphologyEx(mask, closed, Imgproc.MORPH_CLOSE, kernel)
            val contours = ArrayList<MatOfPoint>()
            val hier = Mat()
            Imgproc.findContours(closed, contours, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            hier.release()

            val tw = s.w * scale
            val th = s.h * scale
            for (c in contours) {
                val r = Imgproc.boundingRect(c)
                val area = Imgproc.contourArea(c)
                c.release()
                val okSize = r.width >= 0.6 * tw && r.width <= 1.4 * tw &&
                    r.height >= 0.6 * th && r.height <= 1.4 * th
                if (!okSize || area < 0.5 * r.width * r.height) continue

                val d = dominant(lab, r) ?: continue
                var near = -1
                var bd = Double.MAX_VALUE
                for ((k, o) in samples.withIndex()) {
                    val dd = dist(d, o)
                    if (dd < bd) {
                        bd = dd
                        near = k
                    }
                }
                if (near != si) continue // 더 비슷한 다른 등급이 있으면 그쪽 몫

                out.add(
                    Det(
                        si,
                        roi.x + (r.x + r.width / 2.0) / scale,
                        roi.y + (r.y + r.height / 2.0) / scale,
                        area / (r.width * r.height), // 꽉 찬(가려지지 않은) 칸일수록 높은 점수
                        s.w.toDouble(),
                        s.h.toDouble()
                    )
                )
            }
        }
        mask.release()
        closed.release()
        kernel.release()
        lab.release()
        return out
    }
}
