package io.legado.app.ui.book.read.page.delegate

import android.graphics.*
import android.os.Build
import android.view.MotionEvent
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.utils.screenshot
import kotlin.math.*

/**
 * CurlPageDelegate - 移植自 Google Play Books 3D 翻页效果
 * 
 * 核心算法：
 *  - 使用贝塞尔曲线计算翻页路径（updatePosSimple/updatePosComplex）
 *  - 通过 drawBitmapMesh 实现 3D 视觉效果
 *  - 保持原有的平滑动画和阴影效果
 * 
 * 注意：此实现将 OpenGL ES 的 3D 坐标转换为 Canvas 2D 坐标
 */
class CurlPageDelegate(readView: ReadView) : HorizontalPageDelegate(readView) {

    // 绘制画笔
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // mesh 参数（来自原代码）
    private val meshCols = 40      // 网格列数
    private val meshRows = 20      // 网格行数（增加行数以支持更复杂的变形）
    
    // 贝塞尔曲线控制点常量（来自原代码）
    private val Q0XSrc = -0.39375f
    private var Q0XTgt = -1.5f
    private val Q1XSrc = -0.45f
    private val Q1ZSrc = -0.02f

    // 临时缓存
    private var pageBitmap: Bitmap? = null
    private var meshVerts: FloatArray? = null
    
    // 贝塞尔控制点
    private val P0 = PointF()
    private val P1 = PointF()
    private val Q0 = PointF()
    private val Q1 = PointF()
    private val Q = PointF()
    private val P0Q = PointF()
    private val QP1 = PointF()
    private val Bezier = PointF()
    
    // 进度和方向
    private var progress = 0f      // 翻页进度 [-1, 1]

    init {
        paint.style = Paint.Style.FILL

        // 阴影渐变
        shadowPaint.shader = LinearGradient(
            0f, 0f, 200f, 0f,
            intArrayOf(0x99000000.toInt(), 0x22000000.toInt(), 0x00000000),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        // 背面颜色
        backPaint.alpha = 220
    }

    override fun onTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                abortAnim()
                
                val dragToLeft = event.x > viewWidth / 2f
                if (dragToLeft) {
                    if (!hasNext()) return
                    setDirection(PageDirection.NEXT)
                } else {
                    if (!hasPrev()) return
                    setDirection(PageDirection.PREV)
                }
                
                readView.setStartPoint(event.x, event.y, false)
                isRunning = true
                progress = 0f
            }

            MotionEvent.ACTION_MOVE -> {
                if (isRunning) {
                    readView.setTouchPoint(event.x, event.y)
                    // 计算进度：基于水平拖动距离（使用 startX 作为基准）
                    val dragDist = if (mDirection == PageDirection.NEXT) {
                        (startX - touchX) / viewWidth
                    } else {
                        (touchX - startX) / viewWidth
                    }
                    progress = dragDist.coerceIn(-1f, 1f)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isRunning) return
                
                // 判断是否完成翻页
                val dragDist = abs(touchX - startX)
                isCancel = dragDist < viewWidth * 0.25f
                
                // 启动收尾动画
                onAnimStart(readView.defaultAnimationSpeed)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!isRunning) {
            curRecorder.draw(canvas)
            return
        }

        // 1. 绘制背景页
        when (mDirection) {
            PageDirection.NEXT -> nextRecorder.draw(canvas)
            PageDirection.PREV -> prevRecorder.draw(canvas)
            else -> curRecorder.draw(canvas)
        }

        ensurePageBitmap()
        val bmp = pageBitmap ?: return

        // 2. 更新顶点位置（使用原算法）
        updateMeshVertices(bmp.width, bmp.height)

        // 3. 绘制折叠区域（使用 mesh）
        val verts = meshVerts ?: return
        canvas.withSave {
            // 计算折叠区域路径
            val foldPath = calculateFoldPath()
            clipPath(foldPath)
            
            // 绘制变形的 bitmap
            canvas.drawBitmapMesh(bmp, meshCols, meshRows, verts, 0, null, 0, paint)
            
            // 绘制背面（半透明冷色）
            backPaint.color = 0xCCB0CFE0.toInt()
            canvas.drawRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat(), backPaint)
        }

        // 4. 绘制未折叠区域
        val frontPath = calculateFrontPath()
        canvas.withSave {
            clipPath(frontPath)
            curRecorder.draw(this)
        }

        // 5. 绘制阴影
        drawShadow(canvas)
    }

    /**
     * 更新 mesh 顶点位置（移植自 updatePosSimple）
     * 原算法使用 OpenGL 坐标系（中心为原点），需要转换为 Canvas 坐标系（左上角为原点）
     */
    private fun updateMeshVertices(bmpW: Int, bmpH: Int) {
        createMeshIfNeeded(bmpW, bmpH)
        val verts = meshVerts ?: return
        
        val width = viewWidth.toFloat()
        val height = viewHeight.toFloat()
        
        // 计算平滑进度（smooth 函数）- 原代码逻辑
        val fSmooth = if (mDirection == PageDirection.PREV) {
            // LEFT: smooth(clamp(f, -1, 0) + 1)
            smooth(clamp(progress, -1f, 0f) + 1f)
        } else {
            // RIGHT: clamp(f, 0, 1) * 1.2
            clamp(progress, 0f, 1f) * 1.2f
        }
        
        // 设置贝塞尔控制点（使用原代码的算法）
        // P0: 起始点（在 OpenGL 坐标系中）
        P0.set(-width / 2f, 0f)
        
        // P1: 结束点（根据进度计算）
        val p1x = (width / 2f) + (((-width) / 2f) - (width / 2f)) * fSmooth
        P1.set(p1x, 0.1f * fSmooth)
        
        // Q0: 第一个控制点
        val sqrtT = sqrt(fSmooth.toDouble())
        Q0.set(
            (Q0XSrc + (Q0XTgt - Q0XSrc) * fSmooth).toFloat() * width,
            (sqrtT * width * 1.2).toFloat()
        )
        
        // Q1: 第二个控制点
        val sqrt2T = sqrt((fSmooth * 2f).toDouble())
        val q1z = if (fSmooth < 0.5) {
            val sqrt2TMinus05 = sqrt2T - 0.5
            ((sqrt2TMinus05 * sqrt2TMinus05 - 0.25) * (Q1ZSrc / -0.25)).toFloat() * width
        } else {
            val d8 = (0.25 - Q1ZSrc) / (Q1ZSrc + 0.05f)
            val d9 = (fSmooth * 2f) - 0.5
            ((d9 * d9 - d8) * (Q1ZSrc / d8)).toFloat() * width
        }
        
        Q1.set(
            (Q1XSrc * width + ((width / 2f) - Q1XSrc * width) * fSmooth),
            q1z
        )
        
        // 限制 Q1 的位置
        if (Q1.x > P1.x) Q1.x = P1.x
        if (fSmooth > 0.5 && Q1.y > P1.y) Q1.y = P1.y
        
        // 如果是 LEFT 方向，需要镜像
        if (mDirection == PageDirection.PREV) {
            P0.x = -P0.x
            P1.x = -P1.x
            Q0.x = -Q0.x
            Q1.x = -Q1.x
        }
        
        // 计算每个顶点的位置
        var idx = 0
        for (row in 0..meshRows) {
            val baseY = row * (height / meshRows)
            for (col in 0..meshCols) {
                // 原始网格位置（OpenGL 坐标系：中心为原点）
                val glX = (col * (width / meshCols)) - width / 2f
                
                // 归一化到 [0, 1]（相对于页面宽度）
                val t = (glX + width / 2f) / width
                
                // 计算二次贝塞尔曲线上的点（P0 -> Q0 -> Q1 -> P1）
                // 先计算 Q0 -> Q1 之间的点 Q
                Q.set(
                    Q0.x + (Q1.x - Q0.x) * t,
                    Q0.y + (Q1.y - Q0.y) * t
                )
                
                // 计算 P0 -> Q 之间的点 P0Q
                P0Q.set(
                    P0.x + (Q.x - P0.x) * t,
                    P0.y + (Q.y - P0.y) * t
                )
                
                // 计算 Q -> P1 之间的点 QP1
                QP1.set(
                    Q.x + (P1.x - Q.x) * t,
                    Q.y + (P1.y - Q.y) * t
                )
                
                // 最终贝塞尔点
                Bezier.set(
                    P0Q.x + (QP1.x - P0Q.x) * t,
                    P0Q.y + (QP1.y - P0Q.y) * t
                )
                
                // 转换为 Canvas 坐标系（左上角为原点）
                val screenX = Bezier.x + width / 2f
                val screenY = baseY + Bezier.y
                
                verts[idx++] = screenX.coerceIn(0f, width)
                verts[idx++] = screenY.coerceIn(0f, height)
            }
        }
    }

    /**
     * 计算折叠区域路径
     */
    private fun calculateFoldPath(): Path {
        val path = Path()
        val width = viewWidth.toFloat()
        val height = viewHeight.toFloat()
        
        // 根据进度计算折叠线位置
        val foldX = if (mDirection == PageDirection.NEXT) {
            width * (1f - progress.coerceIn(0f, 1f))
        } else {
            width * progress.coerceIn(0f, 1f)
        }
        
        if (mDirection == PageDirection.NEXT) {
            path.moveTo(foldX, 0f)
            path.lineTo(width, 0f)
            path.lineTo(width, height)
            path.lineTo(foldX, height)
            path.close()
        } else {
            path.moveTo(0f, 0f)
            path.lineTo(foldX, 0f)
            path.lineTo(foldX, height)
            path.lineTo(0f, height)
            path.close()
        }
        
        return path
    }

    /**
     * 计算未折叠区域路径
     */
    private fun calculateFrontPath(): Path {
        val path = Path()
        val fullPath = Path()
        fullPath.addRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat(), Path.Direction.CW)
        
        val foldPath = calculateFoldPath()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            path.addPath(fullPath)
            path.op(foldPath, Path.Op.DIFFERENCE)
        } else {
            val region = Region()
            val clipRegion = Region()
            region.setPath(fullPath, Region())
            clipRegion.setPath(foldPath, Region())
            region.op(clipRegion, Region.Op.DIFFERENCE)
            path.addPath(region.getBoundaryPath())
        }
        
        return path
    }

    /**
     * 绘制阴影
     */
    private fun drawShadow(canvas: Canvas) {
        val width = viewWidth.toFloat()
        val height = viewHeight.toFloat()
        val foldX = if (mDirection == PageDirection.NEXT) {
            width * (1f - progress.coerceIn(0f, 1f))
        } else {
            width * progress.coerceIn(0f, 1f)
        }
        
        val shadowW = 80f * abs(progress).coerceIn(0f, 1f)
        val shadowRect = if (mDirection == PageDirection.NEXT) {
            RectF(foldX, 0f, foldX + shadowW, height)
        } else {
            RectF(foldX - shadowW, 0f, foldX, height)
        }
        
        shadowPaint.shader = LinearGradient(
            shadowRect.left, 0f, shadowRect.right, 0f,
            intArrayOf(0x88000000.toInt(), 0x22000000.toInt(), 0x00000000),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(shadowRect, shadowPaint)
    }

    /**
     * Smooth 函数（来自原代码）
     */
    private fun smooth(t: Float): Float {
        return t * t * (3f - t * 2f)
    }

    /**
     * Clamp 函数（来自原代码）
     */
    private fun clamp(value: Float, min: Float, max: Float): Float {
        return if (value < min) min else if (value > max) max else value
    }

    override fun setBitmap() {
        ensurePageBitmap()
        when (mDirection) {
            PageDirection.PREV -> {
                prevPage.screenshot(prevRecorder)
                curPage.screenshot(curRecorder)
            }
            PageDirection.NEXT -> {
                nextPage.screenshot(nextRecorder)
                curPage.screenshot(curRecorder)
            }
            else -> Unit
        }
        
        // 将 recorder 内容绘制到 bitmap
        val canvas = Canvas(pageBitmap!!)
        curRecorder.draw(canvas)
    }

    override fun setViewSize(width: Int, height: Int) {
        super.setViewSize(width, height)
        recyclePageBitmap()
        ensurePageBitmap()
    }

    override fun onAnimStart(animationSpeed: Int) {
        // 计算目标位置
        val targetDx = if (isCancel) {
            (startX - touchX).toInt()
        } else {
            if (mDirection == PageDirection.NEXT) {
                -((viewWidth + touchX)).toInt()
            } else {
                ((viewWidth - touchX)).toInt()
            }
        }
        
        startScroll(touchX.toInt(), 0, targetDx, 0, animationSpeed)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            readView.setTouchPoint(scroller.currX.toFloat(), scroller.currY.toFloat())
            // 更新进度（基于 startX）
            val dragDist = if (mDirection == PageDirection.NEXT) {
                (startX - scroller.currX) / viewWidth
            } else {
                (scroller.currX - startX) / viewWidth
            }
            progress = dragDist.coerceIn(-1f, 1f)
        } else if (isStarted) {
            onAnimStop()
            stopScroll()
        }
    }

    override fun onAnimStop() {
        if (!isCancel) {
            readView.fillPage(mDirection)
        }
        isRunning = false
        isStarted = false
        progress = 0f
    }

    override fun abortAnim() {
        if (!scroller.isFinished) {
            readView.isAbortAnim = true
            scroller.abortAnimation()
        }
        isRunning = false
        isStarted = false
        isMoved = false
        progress = 0f
    }

    override fun nextPageByAnim(animationSpeed: Int) {
        abortAnim()
        if (!hasNext()) return
        setDirection(PageDirection.NEXT)
        val startX = viewWidth * 0.9f
        readView.setStartPoint(startX, viewHeight / 2f, false)
        setBitmap()
        onAnimStart(animationSpeed)
    }

    override fun prevPageByAnim(animationSpeed: Int) {
        abortAnim()
        if (!hasPrev()) return
        setDirection(PageDirection.PREV)
        val startX = viewWidth * 0.1f
        readView.setStartPoint(startX, viewHeight / 2f, false)
        setBitmap()
        onAnimStart(animationSpeed)
    }

    private fun ensurePageBitmap() {
        if (pageBitmap == null || pageBitmap?.width != viewWidth || pageBitmap?.height != viewHeight) {
            recyclePageBitmap()
            if (viewWidth > 0 && viewHeight > 0) {
                pageBitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
                meshVerts = FloatArray((meshCols + 1) * (meshRows + 1) * 2)
            }
        }
    }

    private fun recyclePageBitmap() {
        pageBitmap?.recycle()
        pageBitmap = null
        meshVerts = null
    }

    private fun createMeshIfNeeded(bmpW: Int, bmpH: Int) {
        if (meshVerts == null) {
            meshVerts = FloatArray((meshCols + 1) * (meshRows + 1) * 2)
        }
    }
}
