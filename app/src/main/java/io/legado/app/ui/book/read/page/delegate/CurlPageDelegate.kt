package io.legado.app.ui.book.read.page.delegate

import android.graphics.*
import android.view.MotionEvent
import androidx.core.graphics.withSave
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.utils.screenshot
import kotlin.math.*

/**
 * 高级贝塞尔纸张卷曲翻页（Google Books 风格）
 * 继承 HorizontalPageDelegate（使用 curRecorder/nextRecorder/prevRecorder）
 *
 * 实现要点：
 * - 使用贝塞尔确定折叠轴和折叠区（fold）
 * - frontRegion: 未折叠的部分（裁剪后绘制 curRecorder）
 * - foldRegion: 折叠部分，绘制当前页背面（半透明）并对其进行 Matrix/Camera 变形
 * - 渐变阴影沿折叠边缘增强立体感
 */
class CurlPageDelegate(readView: ReadView) : HorizontalPageDelegate(readView) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val camera = Camera()

    // path 用于裁剪
    private val pathFold = Path()
    private val pathFront = Path()

    // 临时 RectF
    private val tmpRect = RectF()

    // 控制参数
    private var cornerX = 0f
    private var cornerY = 0f
    private val maxShadowWidth = 120f

    init {
        paint.style = Paint.Style.FILL
        shadowPaint.shader = LinearGradient(
            0f, 0f, 200f, 0f,
            intArrayOf(0x99000000.toInt(), 0x22000000.toInt(), 0x00000000),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        highlightPaint.color = 0x22FFFFFF
    }

    // 触摸处理：按下决定方向，move 更新 touch，up 发起动画
    override fun onTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                abortAnim()
                // 决定方向：右半屏向左翻，左半屏向右翻
                val dragToLeft = event.x > viewWidth / 2
                if (dragToLeft) {
                    if (!hasNext()) return
                    setDirection(PageDirection.NEXT)
                    cornerX = viewWidth.toFloat()
                } else {
                    if (!hasPrev()) return
                    setDirection(PageDirection.PREV)
                    cornerX = 0f
                }
                cornerY = viewHeight.toFloat()
                readView.setStartPoint(event.x, event.y, false)
                isRunning = true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isRunning) {
                    readView.setTouchPoint(event.x, event.y)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isRunning) return
                // 计算拖动是否足以翻页
                val dx = cornerX - touchX
                val dy = cornerY - touchY
                val dist = sqrt(dx * dx + dy * dy)
                val ratio = dist / viewWidth
                // 若拖动小于阈值则回弹（isCancel = true）
                isCancel = ratio < 0.45f
                onAnimStart(readView.defaultAnimationSpeed)
            }
        }
    }

    /**
     * 绘制过程分三步：
     * 1) 背景页（next/prev） -> 在未被折叠处可见
     * 2) 当前页 frontRegion（未折叠部分） -> 裁剪后绘制 curRecorder
     * 3) 折叠区域 foldRegion -> 在 layer 上对 curRecorder 做变换并绘制背面与阴影
     */
    override fun onDraw(canvas: Canvas) {
        // 如果没有运行动画/交互 -> 直接绘制当前页
        if (!isRunning) {
            curRecorder.draw(canvas)
            return
        }

        // 1) 绘制背景（下一页或上一页）
        when (mDirection) {
            PageDirection.NEXT -> nextRecorder.draw(canvas)
            PageDirection.PREV -> prevRecorder.draw(canvas)
            else -> curRecorder.draw(canvas)
        }

        // 计算 fold 参数
        val fx = touchX.coerceIn(0f, viewWidth.toFloat())
        val fy = touchY.coerceIn(0f, viewHeight.toFloat())
        // 折叠中心点（接近手指）
        val cx = fx
        val cy = fy

        // 折叠轴：从 top 到 bottom，近似一条垂直于书脊的贝塞尔线（可改为更复杂）
        // 为更自然：使用控制点使轴线从右向左弯曲
        // controlOffset 控制“鼓起”高度（与拖动距离成正比）
        val baseDist = abs(cornerX - cx)
        val controlOffset = (baseDist / viewWidth) * (viewHeight / 2f) // 控制弧高
        val ctrlX = (cornerX + cx) / 2f + if (mDirection == PageDirection.NEXT) -controlOffset else controlOffset
        val ctrlY = viewHeight / 2f

        // 构造折叠 Path（foldRegion）
        pathFold.reset()
        // 折叠区域用一条二次贝塞尔线描述上边界和下边界
        // 上边界
        pathFold.moveTo(cx, 0f)
        pathFold.quadTo(ctrlX, ctrlY - controlOffset * 0.2f, cornerX, 0f)
        // 右/左侧到下边界
        pathFold.lineTo(cornerX, viewHeight.toFloat())
        // 下边界（反向贝塞尔）
        pathFold.quadTo(ctrlX, ctrlY + controlOffset * 0.2f, cx, viewHeight.toFloat())
        pathFold.close()

        // frontRegion = 全页 - foldRegion
        pathFront.reset()
        pathFront.addRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat(), Path.Direction.CW)
        pathFront.op(pathFold, Path.Op.DIFFERENCE)

        // 2) 绘制 frontRegion（未折叠）
        canvas.withSave {
            clipPath(pathFront)
            curRecorder.draw(this)
        }

        // 3) 绘制 foldRegion（折叠区域：背面 + 变形 + 阴影）
        // 在一个 layer 上绘制 fold 区域，以便做变换和混合
        val layer = canvas.saveLayer(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat(), null)

        // 裁剪到 foldRegion，然后把当前页内容绘制到该区域（作为“纸张被折叠的部分”原图）
        canvas.withSave {
            clipPath(pathFold)
            curRecorder.draw(this)
        }

        // 取出 fold 区域中心 x，用于矩阵变换的 pivot
        val foldCenterX = (cx + cornerX) / 2f
        val foldCenterY = viewHeight / 2f

        // 使用 Camera 旋转来模拟“翻起”的 3D 效果
        val dragRatio = ((viewWidth - baseDist) / viewWidth).coerceIn(0f, 1f)
        val rotateY = (if (mDirection == PageDirection.NEXT) -1 else 1) * (45f * (0.6f + 0.4f * dragRatio))

        camera.save()
        camera.rotateY(rotateY)
        camera.getMatrix(matrix)
        camera.restore()

        // 以 fold 中心做 pre/post translate
        matrix.preTranslate(-foldCenterX, -foldCenterY)
        matrix.postTranslate(foldCenterX, foldCenterY)

        // 将 layer 中裁剪出来的“折叠内容”进行变换（即把折叠的那部分旋起来）
        canvas.concat(matrix)

        // 为背面加上半透明填充（模拟纸背），并稍微调暗以表现背面材质
        canvas.withSave {
            // 背面绘制：把 curRecorder 内容再绘一次（被翻过来的背面）
            // 由于 curRecorder.draw() 会绘制整页，我们需要只绘制 foldRegion 的部分，矩阵已限制
            // 使用一个透明度使其看起来像纸张背面
            paint.alpha = 200
            // draw the flipped content - using curRecorder (it will be clipped by foldRegion/matrix)
            curRecorder.draw(this)
            // 叠加半透明蒙版以区分正面/背面
            drawRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat(), paint.apply { color = 0x66FFFFFF.toInt() })
        }

        // 恢复到 layer（matrix 上的绘制已经完成）
        canvas.restoreToCount(layer)

        // 4) 阴影 - 在折叠边缘添加渐变阴影
        val shadowWidth = (maxShadowWidth * (0.3f + 0.7f * dragRatio)).coerceAtMost(maxShadowWidth)
        val shadowRect = if (mDirection == PageDirection.NEXT) {
            // 阴影位于 fold 的左侧（揭开的下一页与折叠边之间）
            tmpRect.set(cx, 0f, cx + shadowWidth, viewHeight.toFloat())
            tmpRect
        } else {
            tmpRect.set(cx - shadowWidth, 0f, cx, viewHeight.toFloat())
            tmpRect
        }
        canvas.drawRect(shadowRect, shadowPaint)

        // 5) 高光（沿曲率顶部）增强真实感（轻微）
        val highlightWidth = 30f * dragRatio
        if (highlightWidth > 1f) {
            canvas.drawRect(
                if (mDirection == PageDirection.NEXT) cx else cx - highlightWidth,
                0f,
                if (mDirection == PageDirection.NEXT) cx + highlightWidth else cx,
                viewHeight.toFloat(),
                highlightPaint
            )
        }
    }

    // 将页面内容截图到 recorder（在动画开始前或切页前调用）
    override fun setBitmap() {
        when (mDirection) {
            PageDirection.NEXT -> {
                // current -> curRecorder, next -> nextRecorder
                curPage.screenshot(curRecorder)
                nextPage.screenshot(nextRecorder)
            }
            PageDirection.PREV -> {
                curPage.screenshot(curRecorder)
                prevPage.screenshot(prevRecorder)
            }
            else -> Unit
        }
    }

    override fun setViewSize(width: Int, height: Int) {
        super.setViewSize(width, height)
        cornerY = height.toFloat()
    }

    // 动画启动：根据 isCancel 决定回弹或完成翻页，使用 startScroll 发起平滑过渡
    override fun onAnimStart(animationSpeed: Int) {
        // 计算目标移动量（水平）
        val baseDist = abs(cornerX - touchX)
        val dragRatio = (baseDist / viewWidth).coerceIn(0f, 1f)

        val distanceX = if (isCancel) {
            // 回弹：移动回起点
            if (mDirection == PageDirection.NEXT) (viewWidth - touchX).toInt() else -(touchX).toInt()
        } else {
            // 完成翻页：把折叠继续推完
            if (mDirection == PageDirection.NEXT) -((viewWidth + touchX)).toInt() else ((viewWidth - touchX)).toInt()
        }

        // 使用 startScroll 来驱动 scroller，基类会在 computeScroll 调用 setTouchPoint
        startScroll(touchX.toInt(), 0, distanceX, 0, animationSpeed)
    }

    override fun onAnimStop() {
        // 动画完成：如果不是取消则切页
        if (!isCancel) {
            readView.fillPage(mDirection)
        } else {
            // 回弹时仅重绘回原位
            readView.invalidate()
        }
        // 重置状态
        isRunning = false
        isStarted = false
    }

    override fun abortAnim() {
        // 中断 scroller，重置状态
        if (!scroller.isFinished) {
            readView.isAbortAnim = true
            scroller.abortAnimation()
        }
        isRunning = false
        isStarted = false
        isMoved = false
    }

    override fun nextPageByAnim(animationSpeed: Int) {
        abortAnim()
        if (!hasNext()) return
        setDirection(PageDirection.NEXT)
        readView.setStartPoint(viewWidth * 0.9f, viewHeight / 2f, false)
        setBitmap()
        onAnimStart(animationSpeed)
    }

    override fun prevPageByAnim(animationSpeed: Int) {
        abortAnim()
        if (!hasPrev()) return
        setDirection(PageDirection.PREV)
        readView.setStartPoint(viewWidth * 0.1f, viewHeight / 2f, false)
        setBitmap()
        onAnimStart(animationSpeed)
    }
}
