package com.calla.remoteterminal

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 终端区容器（桌面 noVNC 标签的手势分发层）：
 *  - 单指拖拽 = 平移（WebView 内容滚动；noVNC 画布 preventDefault 会吃掉 WebView 原生滚动，
 *    所以在这里手动 scrollBy）
 *  - 双指捏合 = 缩放（放行给 WebView 内建 pinch）
 *  - 干净单击（位移小于 slop）= 放行给 noVNC 当鼠标点击
 * 仅 panEnabled（当前标签是桌面标签）时生效；终端标签完全透明。
 */
class PanInterceptLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var panEnabled = false

    /** 平移目标（当前桌面标签的 WebView）。 */
    var panTarget: View? = null

    /** 平移回调(物理 px 增量;由 Activity 翻译成 CSS translate 注入页面——
     *  noVNC 的 body 是 overflow:hidden,WebView 原生 scrollBy 无效)。 */
    var onPan: ((dx: Float, dy: Float) -> Unit)? = null

    /** 干净单击回调(位移<slop;事件仍照常放行给 WebView)。 */
    var onSingleTap: (() -> Unit)? = null

    /** 双指捏合回调(factor 与焦点,容器坐标物理 px;WebView 内建缩放被 vnc.html
     *  的 viewport meta 禁用,缩放由 Activity 注入 JS 自绘)。 */
    var onPinch: ((factor: Float, focusX: Float, focusY: Float) -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var panning = false
    /** 双指缩放手势进行中:整段手势由 ScaleGestureDetector 消费,不透给 WebView。 */
    private var scaling = false
    /** 本次手势落在视野滑块上:整个手势放行给 SeekBar,不做平移拦截。 */
    private var gestureOnSlider = false

    private val scaleDetector = android.view.ScaleGestureDetector(context,
        object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                onPinch?.invoke(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        })

    /** 视野滑块(tag=desktop_slider)命中检测:按旋转/平移后的真实视觉区域判定
     *  (getHitRect 只给未旋转的布局盒,右侧竖滑块会判偏)。 */
    private fun hitSlider(x: Float, y: Float): Boolean {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.tag == "desktop_slider" && c.visibility == View.VISIBLE && c.isEnabled) {
                val rc = android.graphics.RectF(0f, 0f, c.width.toFloat(), c.height.toFloat())
                val m = android.graphics.Matrix()
                m.setRotate(c.rotation, c.width / 2f, c.height / 2f)
                m.postTranslate(c.left + c.translationX, c.top + c.translationY)
                m.mapRect(rc)
                if (rc.contains(x, y)) return true
            }
        }
        return false
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!panEnabled) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureOnSlider = hitSlider(ev.x, ev.y)
                downX = ev.x; downY = ev.y
                lastX = ev.x; lastY = ev.y
                downTime = ev.eventTime
                panning = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二根手指落下:整段手势拦截做捏合缩放(WebView 收到 CANCEL,
                // noVNC 不会误当拖动/点击;内建缩放已被 viewport meta 禁用)
                scaling = true
                panning = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaling) return true
                if (gestureOnSlider || ev.pointerCount >= 2) {
                    panning = false
                    return false
                }
                if (!panning && (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop)) {
                    panning = true
                    lastX = ev.x; lastY = ev.y
                    return true // 从这里拦截;WebView 收到 CANCEL,noVNC 不会误当成拖动
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (scaling) return true // 缩放段的事件不泄漏给 WebView
            }
            MotionEvent.ACTION_UP -> {
                // 干净单击:放行给 WebView(noVNC 当鼠标),同时回调(弹键盘等)
                if (!panning && !scaling && !gestureOnSlider && ev.eventTime - downTime < 400 &&
                    abs(ev.x - downX) <= slop && abs(ev.y - downY) <= slop
                ) {
                    onSingleTap?.invoke()
                }
                gestureOnSlider = false
            }
        }
        return panning
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!panEnabled) return false
        if (scaling) {
            scaleDetector.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                scaling = false
            }
            return true
        }
        if (ev.pointerCount >= 2) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                // 手指拖动方向 = 内容跟随方向
                onPan?.invoke(ev.x - lastX, ev.y - lastY)
                lastX = ev.x; lastY = ev.y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                panning = false
                return true
            }
        }
        return false
    }
}
