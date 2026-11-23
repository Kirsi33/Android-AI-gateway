package dev.aigw.app.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import dev.aigw.app.MainActivity
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 「悬浮窗保活」：在屏幕上留一个极小的悬浮标签。
 *
 * 原理：带可见悬浮窗（TYPE_APPLICATION_OVERLAY）的应用会被系统视为前台可见进程，
 * 后台回收优先级低于纯后台服务；它只影响系统回收，不改变任何上游行为。
 *
 * 全部方法必须在主线程调用；窗口用 try/catch 包住——权限被收回或系统拒绝时只返回 false，
 * 不影响网关本身运行。
 */
object FloatingKeepAlive {

    /** 悬浮窗字号下限（sp）。 */
    const val MIN_SCALE = 8f

    /** 悬浮窗字号上限（sp）。 */
    const val MAX_SCALE = 24f

    /** 默认字号：与改造前一致，升级后视觉不变。 */
    const val DEFAULT_SCALE = 11f

    /** 默认自动换位间隔（分钟）；0 = 关闭。 */
    const val DEFAULT_INTERVAL_MIN = 5

    /** 换位间隔上限（分钟）。 */
    const val MAX_INTERVAL_MIN = 60


    private const val PREF_X = "overlay_x"
    private const val PREF_Y = "overlay_y"
    private const val PREF_SCALE = "overlay_scale"
    private const val PREF_INTERVAL = "overlay_interval_min"

    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    /** 定时换位用的主线程 Handler；与 view 同生共死。 */
    private val handler = Handler(Looper.getMainLooper())

    /** 换位时优先跳到「离当前位置足够远」的角落，避免细碎挪动起不到防烧屏作用。 */
    private val autoMove = object : Runnable {
        override fun run() {
            val target = view ?: return
            val layout = params ?: return
            val ctx = target.context
            val interval = autoMoveIntervalMin(ctx)
            if (interval <= 0) return
            runCatching { jumpToRandomCorner(ctx, target, layout) }
            handler.postDelayed(this, interval * 60_000L)
        }
    }

    /** 是否已获得「显示在其他应用上层」权限。 */
    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isShowing(): Boolean = view != null

    // ---------------------------------------------------------------- 尺寸与换位偏好

    /** 读字号（sp）；越界值夹回 [MIN_SCALE, MAX_SCALE]。 */
    fun scaleSp(context: Context): Float = prefs(context)
        .getFloat(PREF_SCALE, DEFAULT_SCALE)
        .coerceIn(MIN_SCALE, MAX_SCALE)

    /** 写字号；悬浮窗在显示中时立即重排（不需要重启服务）。 */
    fun setScaleSp(context: Context, sp: Float) {
        val app = context.applicationContext
        val value = sp.coerceIn(MIN_SCALE, MAX_SCALE)
        prefs(app).edit().putFloat(PREF_SCALE, value).apply()
        val target = view ?: return
        val layout = params ?: return
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        styleView(target, app, value)
        runCatching {
            // 字号变了尺寸也变了：先让系统重新测量再夹回屏内
            wm.updateViewLayout(target, layout)
            clampToScreen(app, layout, target.width, target.height)
            wm.updateViewLayout(target, layout)
        }
    }

    /** 读自动换位间隔（分钟）；0 表示关闭。 */
    fun autoMoveIntervalMin(context: Context): Int =
        prefs(context).getInt(PREF_INTERVAL, DEFAULT_INTERVAL_MIN).coerceIn(0, MAX_INTERVAL_MIN)

    /** 写自动换位间隔（分钟）；显示中会立即按新间隔重排定时器。 */
    fun setAutoMoveIntervalMin(context: Context, minutes: Int) {
        val app = context.applicationContext
        prefs(app).edit().putInt(PREF_INTERVAL, minutes.coerceIn(0, MAX_INTERVAL_MIN)).apply()
        if (isShowing()) restartAutoMove()
    }

    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            .setData(android.net.Uri.parse("package:${context.packageName}"))

    /**
     * 显示悬浮窗；未授权或添加失败时返回 false（调用方据此提示用户）。
     * [label] 展示在悬浮窗上的文字（如「网关 8790」）。
     */
    fun show(context: Context, label: String): Boolean {
        if (view != null) {
            view?.text = label
            return true
        }
        if (!canDraw(context)) return false
        val app = context.applicationContext
        val windowManager = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        return try {
            val params = buildParams(app)
            val text = TextView(app).apply {
                this.text = label
                setTextColor(0xFFFFFFFF.toInt())
                styleView(this, app, scaleSp(app))
                setOnClickListener {
                    runCatching {
                        app.startActivity(
                            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
                attachDrag(this, windowManager, params, app)
            }
            windowManager.addView(text, params)
            view = text
            this.params = params
            // 首帧布局完成后按真实尺寸再夹一次：字号大时可能首帧越界
            text.post {
                clampToScreen(app, params, text.width, text.height)
                runCatching { windowManager.updateViewLayout(text, params) }
            }
            restartAutoMove()
            true
        } catch (_: Exception) {
            view = null
            params = null
            false
        }
    }

    fun hide(context: Context) {
        stopAutoMove()
        val current = view ?: return
        val windowManager = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        runCatching { windowManager?.removeView(current) }
        view = null
        params = null
    }

    /** 按字号重排内边距、圆角与背景：字号变了胶囊也要跟着变，否则小字配大壳很难看。 */
    private fun styleView(target: TextView, app: Context, sp: Float) {
        target.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        val padH = (sp * 0.9f).roundToInt().coerceAtLeast(4)
        val padV = (sp * 0.55f).roundToInt().coerceAtLeast(3)
        target.setPadding(dp(app, padH), dp(app, padV), dp(app, padH), dp(app, padV))
        target.background = GradientDrawable().apply {
            // 胶囊半径 = 高度的一半，随字号一起缩放
            cornerRadius = dp(app, (sp * 1.1f).roundToInt()).toFloat()
            setColor(0xB3222222.toInt())
        }
    }

    /**
     * 自动换位：在屏幕四角 + 四边中点里挑一个「离当前位置足够远」的落点，**瞬移**过去。
     *
     * 不做过场动画是刻意的：防烧屏要的是像素快速离开原位，动画反而把整条路径都点亮。
     */
    private fun jumpToRandomCorner(
        context: Context,
        target: TextView,
        params: WindowManager.LayoutParams,
    ) {
        val metrics = context.resources.displayMetrics
        val w = target.width.takeIf { it > 0 } ?: 0
        val h = target.height.takeIf { it > 0 } ?: 0
        val margin = dp(context, 12)
        val maxX = (metrics.widthPixels - w - margin).coerceAtLeast(margin)
        val maxY = (metrics.heightPixels - h - margin).coerceAtLeast(margin)
        val midX = ((metrics.widthPixels - w) / 2).coerceAtLeast(margin)
        val midY = ((metrics.heightPixels - h) / 2).coerceAtLeast(margin)

        val candidates = listOf(
            margin to margin,
            maxX to margin,
            margin to maxY,
            maxX to maxY,
            midX to margin,
            midX to maxY,
            margin to midY,
            maxX to midY,
        )
        // 距离门槛取屏幕短边的 1/3：只接受「跳得够远」的候选，防止在角落附近小挪动
        val minGap = minOf(metrics.widthPixels, metrics.heightPixels) / 3
        val farEnough = candidates.filter { (x, y) ->
            abs(x - params.x) >= minGap || abs(y - params.y) >= minGap
        }
        val pool = farEnough.ifEmpty { candidates.filter { (x, y) -> x != params.x || y != params.y } }
        val pick = pool.random(Random(System.currentTimeMillis().toInt()))
        params.x = pick.first
        params.y = pick.second
        clampToScreen(context, params, w, h)
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        runCatching { wm.updateViewLayout(target, params) }
        prefs(context).edit().putInt(PREF_X, params.x).putInt(PREF_Y, params.y).apply()
    }

    /** 按当前设置重新挂定时器（间隔 0 或未显示时什么都不做）。 */
    private fun restartAutoMove() {
        stopAutoMove()
        val target = view ?: return
        val interval = autoMoveIntervalMin(target.context)
        if (interval <= 0) return
        handler.postDelayed(autoMove, interval * 60_000L)
    }

    private fun stopAutoMove() {
        handler.removeCallbacks(autoMove)
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun buildParams(app: Context): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val stored = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // 不可获焦：不影响任何输入
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = stored.getInt(PREF_X, dp(app, 12))
            y = stored.getInt(PREF_Y, dp(app, 160))
        }
        // 存的位置可能是上个分辨率/旋转方向下的：先夹回当前屏幕内，避免启动后“看不到”
        clampToScreen(app, params, 0, 0)
        return params
    }

    /**
     * 把悬浮窗位置夹在屏幕内。
     *
     * 保活依赖「窗口真的可见」：拖到屏幕外会既找不回来、又可能不被系统计为可见进程，
     * 所以这里不允许拖出屏幕；尺寸未知（首帧布局前）时按 0 处理，至少保证左上角在屏内。
     */
    private fun clampToScreen(context: Context, params: WindowManager.LayoutParams, viewW: Int, viewH: Int) {
        val metrics = context.resources.displayMetrics
        val maxX = (metrics.widthPixels - viewW).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - viewH).coerceAtLeast(0)
        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
    }

    /** 拖动移动悬浮窗，松手落盘位置（下次启动回到原处）。 */
    private fun attachDrag(
        target: TextView,
        windowManager: WindowManager,
        params: WindowManager.LayoutParams,
        app: Context,
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false
        target.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (abs(dx) > dp(app, 4) || abs(dy) > dp(app, 4)) dragging = true
                    if (dragging) {
                        params.x = startX + dx.roundToInt()
                        params.y = startY + dy.roundToInt()
                        clampToScreen(app, params, target.width, target.height)
                        runCatching { windowManager.updateViewLayout(target, params) }
                    }
                    dragging
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putInt(PREF_X, params.x)
                            .putInt(PREF_Y, params.y)
                            .apply()
                    }
                    // 拖动后抬手不触发点击（返回 true 消费事件），未拖动则交给点击回调
                    dragging
                }
                else -> false
            }
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private const val PREFS = "aigw_overlay"
}
