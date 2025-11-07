package io.legado.app.ui.book.read.page.delegate

import android.graphics.*
import android.view.MotionEvent
import androidx.core.graphics.withSave
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.PageDirection
import kotlin.math.*

/**
 * CurlPageDelegate - 基于 Bitmap mesh 的贝塞尔/正弦弯曲纸页翻页（接近 Google Play Books / 静读天下）
 *
 * 设计说明（要点）：
 *  - 使用 curPage.draw(bitmapCanvas) 生成当前页 bitmap（避免依赖不可预知的 recorder bitmap API）
 *  - 将 bitmap 按网格分段，通过 drawBitmapMesh 实现“纸张沿竖直方向弯曲”的视觉（y = H * sin(pi * x / width)）
 *  - 同时对背面区域使用半透明冷色叠加以模拟纸的背面
 *  - 阴影使用 LinearGradient 渐变，位置随折叠线（foldX）移动
 *
 * 注意：此实现力求在 Canvas 环境中达到高逼真度，性能取决于 mesh 划分密度（meshCols）及设备。
 */
class CurlPageDelegate(readView: ReadView) : HorizontalPageDelegate(readView) {

    // 绘制画笔
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // mesh 参数（可调）
    private val meshCols = 40      // 网格列数（越大更平滑，性能越差）
    private val meshRows = 1       // 只在竖直方向变形，用一行网格即可
    private val maxCurve = 0.35f   // 最大弧高（相对于 viewHeight 的比例），可调 0.1 ~ 0.5

    // 临时缓存 bitmap（避免频繁分配）
    private var pageBitmap: Bitmap? = null
    private var pageBitmapCanvas: Canvas? = null
    private var meshVerts: FloatArray? = null

    // 折叠控制
    private var cornerX = 0f
    private var cornerY = 0f

    init {
        paint.style = Paint.Style.FILL
        // 阴影（将按 fold 方向绘制）
        shadowPaint.shader = LinearGradient(
            0f, 0f, 200f, 0f,
            intArrayOf(0x99000000.toInt(), 0x22000000.toInt(), 0x00000000),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        // 背面颜色（冷灰），半透明
        backPaint.colorFilter = null
        backPaint.alpha = 220
    }

    // --- 手势处理 ---
    override fun onTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                abortAnim()
                // 决定翻页方向：右半屏向左（NEXT），左半屏向右（PREV）
                val dragToLeft = event.x > viewWidth / 2f
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
                // 生成或更新 bitmap 缓存
                ensurePageBitmap()
                // 截图当前页到 bitmap
                curPage.draw(pageBitmapCanvas)
            }

            MotionEvent.ACTION_MOVE -> {
                if (isRunning) {
                    readView.setTouchPoint(event.x, event.y)
                    // 每次移动可以更新 bitmap（如果你用 recorder 直接提供 bitmap 可跳过）
                    // curPage.draw(pageBitmapCanvas) // 可选：减小频率以提升性能
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isRunning) return
                // 判断是否完成翻页（水平拖动阈值）
                val dragDist = abs(touchX - startX)
                isCancel = dragDist < viewWidth * 0.25f
                // 启动收尾动画
                onAnimStart(readView.defaultAnimationSpeed)
            }
        }
    }

    // --- 绘制：background -> front (未折叠) -> folded mesh -> backface tint -> shadow ---
    override fun onDraw(canvas: Canvas) {
        // 非交互时直接绘制当前页
        if (!isRunning) {
            curRecorder.draw(canvas)
            return
        }

        // 1. 绘制背景页（先绘制被揭露的目标页）
        when (mDirection) {
            PageDirection.NEXT -> nextRecorder.draw(canvas)
            PageDirection.PREV -> prevRecorder.draw(canvas)
            else -> curRecorder.draw(canvas)
        }

        // 确保 bitmap 可用
        ensurePageBitmap()

        val bmp = pageBitmap ?: return

        // 计算 fold 关键变量
        val fx = touchX.coerceIn(0f, viewWidth.toFloat())   // 手指X
        val fy = touchY.coerceIn(0f, viewHeight.toFloat())  // 手指Y
        val baseDist = abs(cornerX - fx)
        // H 控制弧高，相对 viewHeight
        val H = (min(maxCurve, 0.75f * (1f - baseDist / viewWidth)) * viewHeight).coerceAtLeast(0f)

        // foldX 为“折叠轴”大致位置（以手指靠近处为起点）
        val foldX = fx

        // 2. 绘制 frontRegion（未被折叠的左侧/右侧区域）
        // 构造 front path = 全页 - fold area (右半被拉起为例)
        val foldPath = Path().apply {
            // 使用 sin 曲线近似 fold 上边界： y = H * sin(pi * (x - foldStart) / foldWidth)
            // 为简单稳定，使用一段二次贝塞尔：从 foldX 到 cornerX
            val cx = (foldX + cornerX) / 2f
            reset()
            moveTo(0f, 0f)
            if (mDirection == PageDirection.NEXT) {
                // 对 NEXT（右向左翻），未折叠区为 [0, foldX)
                lineTo(foldX, 0f)
                // 折叠上边界到 corner
                quadTo(cx, H * 0.6f, cornerX, 0f)
                lineTo(cornerX, viewHeight.toFloat())
                quadTo(cx, viewHeight - H * 0.6f, foldX, viewHeight.toFloat())
                close()
            } else {
                // PREV（左向右翻），未折叠区为 (foldX, width]
                moveTo(foldX, 0f)
                lineTo(viewWidth.toFloat(), 0f)
                lineTo(viewWidth.toFloat(), viewHeight.toFloat())
                lineTo(foldX, viewHeight.toFloat())
                close()
            }
        }

        // frontRegion = fullRect - foldPath (we want to draw the non-folded portion of cur page)
        val frontPath = Path()
        frontPath.addRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat(), Path.Direction.CW)
        frontPath.op(foldPath, Path.Op.DIFFERENCE)

        // draw front (clipped)
        canvas.withSave {
            clipPath(frontPath)
            curRecorder.draw(this)
        }

        // 3. 绘制 fold 区域：使用 drawBitmapMesh 做竖向弯曲变形（对 bmp 的右侧或左侧部分）
        // mesh 网格生成（按 fold side 只对被翻起那半进行变形）
        createMeshIfNeeded(bmp.width, bmp.height)

        val verts = meshVerts ?: return

        // 生成顶点变形：对每列 x，计算 y-offset = sin(pi * (x - foldStart) / foldWidth) * H
        val cols = meshCols
        val step = viewWidth.toFloat() / cols

        // 根据翻页方向，fold region 是右侧（NEXT）或左侧（PREV）
        if (mDirection == PageDirection.NEXT) {
            // we move right-side area: for vertices whose x >= foldX do deformation
            var idx = 0
            for (row in 0..meshRows) {
                val ry = row * (viewHeight.toFloat() / meshRows)
                for (c in 0..cols) {
                    val x = c * (viewWidth.toFloat() / cols)
                    val offset = if (x >= foldX) {
                        // normalized t in [0,1] where 0 at foldX and 1 at cornerX
                        val t = ((x - foldX) / max(1f, (viewWidth - foldX))).coerceIn(0f, 1f)
                        // y displacement by sin curve: amplitude H * sin(pi * t)
                        val yOff = (H * sin(PI * t)).toFloat()
                        // apply vertical displacement towards center (positive pushes downward)
                        yOff * (if (row == 0) -1 else 1) * 0.5f // top row negative, bottom positive for curvature
                    } else 0f
                    // store vertex: x, y + offset
                    verts[idx++] = x
                    verts[idx++] = ry + offset
                }
            }
        } else {
            // PREV: left side deforms (x <= foldX)
            var idx = 0
            for (row in 0..meshRows) {
                val ry = row * (viewHeight.toFloat() / meshRows)
                for (c in 0..cols) {
                    val x = c * (viewWidth.toFloat() / cols)
                    val offset = if (x <= foldX) {
                        val t = (1f - (x / max(1f, foldX))).coerceIn(0f, 1f)
                        val yOff = (H * sin(PI * t)).toFloat()
                        yOff * (if (row == 0) -1 else 1) * 0.5f
                    } else 0f
                    verts[idx++] = x
                    verts[idx++] = ry + offset
                }
            }
        }

        // draw bitmap mesh (only draw the full bitmap, but the deformations affect visually)
        canvas.withSave {
            // clip to foldPath so only fold region shows deformed mesh
            clipPath(foldPath)
            // draw the whole bitmap with transformed verts
            canvas.drawBitmapMesh(bmp, cols, meshRows, verts, 0, null, 0, paint)
        }

        // 4. 背面半透明：在 fold region 上绘制半透明冷色矩形（或直接对变形部分再绘一次 bitmap 并调色）
        canvas.withSave {
            clipPath(foldPath)
            // 叠加冷色蒙层，模拟纸背（可改为 drawBitmap with ColorMatrix）
            drawRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat(), backPaint.apply {
                color = if (mDirection == PageDirection.NEXT) 0xCCB0CFE0.toInt() else 0xCCB0CFE0.toInt()
            })
        }

        // 5. 阴影：沿 fold 边绘制窄带渐变
        val shadowW = (max(20f, 80f * (H / viewHeight))).coerceAtMost(120f)
        val shadowRect = if (mDirection == PageDirection.NEXT) {
            RectF(foldX, 0f, foldX + shadowW, viewHeight.toFloat())
        } else {
            RectF(foldX - shadowW, 0f, foldX, viewHeight.toFloat())
        }
        // 更新 shader 位置（简单方法：重-create shader 每帧，或使用 matrix 平移）
        shadowPaint.shader = LinearGradient(
            shadowRect.left, 0f, shadowRect.right, 0f,
            intArrayOf(0x88000000.toInt(), 0x22000000.toInt(), 0x00000000),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(shadowRect, shadowPaint)
    }

    // --- setBitmap: 将渲染准备好的页面截图到 recorder/bitmap（调用前 mDirection 要已经设置） ---
    override fun setBitmap() {
        // 使用 curPage/nextPage/prevPage.draw(canvasBitmap) 将内容放到 pageBitmap
        ensurePageBitmap()
        when (mDirection) {
            PageDirection.NEXT -> {
                // current-> bitmap, next page as background already drawn in onDraw by nextRecorder
                curPage.draw(pageBitmapCanvas)
            }
            PageDirection.PREV -> {
                curPage.draw(pageBitmapCanvas)
            }
            else -> curPage.draw(pageBitmapCanvas)
        }
    }

    override fun setViewSize(width: Int, height: Int) {
        super.setViewSize(width, height)
        // recreate bitmap when size changed
        recyclePageBitmap()
        ensurePageBitmap()
        cornerY = height.toFloat()
    }

    override fun onAnimStart(animationSpeed: Int) {
        // 依据 isCancel 决定目标平移：如果完成翻页，则把 fold 推到页面另一侧，否则回弹到原来位置
        val targetDx = if (isCancel) {
            // 回弹：移动到起点（touch->start）
            (startX - touchX).toInt()
        } else {
            // 完成翻页：将折叠推进到底（大致移动量 viewWidth）
            if (mDirection == PageDirection.NEXT) -((viewWidth + touchX)).toInt() else ((viewWidth - touchX)).toInt()
        }
        // startScroll 的参数：startX, startY, dx, dy, animationSpeed
        startScroll(touchX.toInt(), 0, targetDx, 0, animationSpeed)
    }

    override fun onAnimStop() {
        if (!isCancel) {
            readView.fillPage(mDirection)
        } else {
            // 回弹到原位，清理状态
            readView.invalidate()
        }
        isRunning = false
        isStarted = false
    }

    override fun abortAnim() {
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

    // --- 辅助：创建/回收 bitmap 与 mesh 数组 ---
    private fun ensurePageBitmap() {
        if (pageBitmap == null || pageBitmap?.width != viewWidth || pageBitmap?.height != viewHeight) {
            recyclePageBitmap()
            if (viewWidth > 0 && viewHeight > 0) {
                pageBitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
                pageBitmapCanvas = Canvas(pageBitmap!!)
                // init mesh
                meshVerts = FloatArray((meshCols + 1) * (meshRows + 1) * 2)
            }
        }
    }

    private fun recyclePageBitmap() {
        pageBitmapCanvas = null
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
