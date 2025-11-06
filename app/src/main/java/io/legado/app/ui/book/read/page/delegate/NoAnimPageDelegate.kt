package io.legado.app.ui.book.read.page.delegate

import android.graphics.*
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.withTranslation
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.utils.screenshot
import kotlin.math.*

class NoAnimPageDelegate(readView: ReadView) : HorizontalPageDelegate(readView) {

    private val paint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }
    
    private val shadowPaint = Paint().apply {
        isAntiAlias = true
        color = 0x80000000
    }
    
    private var currentBitmap: Bitmap? = null
    private var nextBitmap: Bitmap? = null
    
    // 触摸点位置
    private var touchX = 0f
    private var touchY = 0f
    
    // 翻页控制点
    private var cornerX = 0f
    private var cornerY = 0f
    
    // 翻页状态
    private var isAnimating = false
    private var flipProgress = 0f

    override fun onAnimStart(animationSpeed: Int) {
        if (!isCancel) {
            readView.fillPage(mDirection)
            startFlipAnimation()
        }
        stopScroll()
    }

    override fun setBitmap() {
        // 捕获当前页和下一页的截图
        currentBitmap = readView.curPage?.screenshot()
        nextBitmap = readView.nextPage?.screenshot()
    }

    override fun onDraw(canvas: Canvas) {
        if (!isAnimating) {
            // 没有动画时直接绘制当前页
            currentBitmap?.let { bitmap ->
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
            }
            return
        }
        
        drawFlipAnimation(canvas)
    }

    override fun onAnimStop() {
        isAnimating = false
        flipProgress = 0f
        currentBitmap?.recycle()
        nextBitmap?.recycle()
        currentBitmap = null
        nextBitmap = null
    }

    private fun startFlipAnimation() {
        isAnimating = true
        flipProgress = 0f
        touchX = readView.width * 0.8f
        touchY = readView.height * 0.5f
        updateCornerPoint()
    }

    private fun updateCornerPoint() {
        cornerX = if (mDirection) {
            // 向右翻页
            touchX.coerceIn(0f, readView.width.toFloat())
        } else {
            // 向左翻页
            touchX.coerceIn(0f, readView.width.toFloat())
        }
        cornerY = touchY.coerceIn(0f, readView.height.toFloat())
    }

    private fun drawFlipAnimation(canvas: Canvas) {
        val width = readView.width.toFloat()
        val height = readView.height.toFloat()
        
        if (mDirection) {
            // 向右翻页效果
            drawRightFlip(canvas, width, height)
        } else {
            // 向左翻页效果
            drawLeftFlip(canvas, width, height)
        }
        
        // 更新动画进度
        flipProgress += 0.05f
        if (flipProgress >= 1f) {
            isAnimating = false
            readView.pageAnimEnd()
        }
    }

    private fun drawRightFlip(canvas: Canvas, width: Float, height: Float) {
        val progress = flipProgress
        val flipX = width * progress
        
        // 绘制下一页
        nextBitmap?.let { bitmap ->
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
        }
        
        // 绘制当前页的翻起部分
        currentBitmap?.let { bitmap ->
            val path = Path().apply {
                moveTo(flipX, 0f)
                lineTo(width, 0f)
                lineTo(width, height)
                lineTo(flipX, height)
                close()
            }
            
            canvas.withTranslation(-flipX * (1 - progress) * 0.3f, 0f) {
                canvas.save()
                canvas.clipPath(path)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
                canvas.restore()
            }
            
            // 绘制翻页阴影
            drawPageShadow(canvas, flipX, width, height, progress, true)
        }
    }

    private fun drawLeftFlip(canvas: Canvas, width: Float, height: Float) {
        val progress = flipProgress
        val flipX = width * (1 - progress)
        
        // 绘制下一页
        nextBitmap?.let { bitmap ->
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
        }
        
        // 绘制当前页的翻起部分
        currentBitmap?.let { bitmap ->
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(flipX, 0f)
                lineTo(flipX, height)
                lineTo(0f, height)
                close()
            }
            
            canvas.withTranslation(flipX * (1 - progress) * 0.3f, 0f) {
                canvas.save()
                canvas.clipPath(path)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
                canvas.restore()
            }
            
            // 绘制翻页阴影
            drawPageShadow(canvas, 0f, flipX, height, progress, false)
        }
    }

    private fun drawPageShadow(
        canvas: Canvas,
        startX: Float,
        endX: Float,
        height: Float,
        progress: Float,
        isRightFlip: Boolean
    ) {
        val shadowWidth = (endX - startX) * 0.3f
        val alpha = (255 * (1 - progress) * 0.6f).toInt()
        
        shadowPaint.alpha = alpha
        
        if (isRightFlip) {
            // 右侧翻页阴影
            val shadowRect = RectF(
                endX - shadowWidth,
                0f,
                endX,
                height
            )
            
            val gradient = LinearGradient(
                shadowRect.left, 0f,
                shadowRect.right, 0f,
                intArrayOf(Color.TRANSPARENT, Color.BLACK),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            shadowPaint.shader = gradient
            
            canvas.drawRect(shadowRect, shadowPaint)
        } else {
            // 左侧翻页阴影
            val shadowRect = RectF(
                startX,
                0f,
                startX + shadowWidth,
                height
            )
            
            val gradient = LinearGradient(
                shadowRect.right, 0f,
                shadowRect.left, 0f,
                intArrayOf(Color.TRANSPARENT, Color.BLACK),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            shadowPaint.shader = gradient
            
            canvas.drawRect(shadowRect, shadowPaint)
        }
        
        shadowPaint.shader = null
    }

    // 处理触摸事件来模拟真实的翻页交互
    fun onTouchEvent(x: Float, y: Float, isMoving: Boolean) {
        if (!isAnimating) return
        
        touchX = x
        touchY = y
        updateCornerPoint()
        
        // 根据触摸位置更新动画进度
        flipProgress = if (mDirection) {
            (x / readView.width).coerceIn(0f, 1f)
        } else {
            1f - (x / readView.width).coerceIn(0f, 1f)
        }
        
        readView.invalidate()
        
        if (!isMoving && flipProgress > 0.3f) {
            // 如果释放触摸且进度足够，完成翻页
            flipProgress = 1f
            isAnimating = false
            readView.pageAnimEnd()
        }
    }

    override fun onDestroy() {
        onAnimStop()
    }
}
