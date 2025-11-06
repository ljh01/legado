package io.legado.app.ui.book.read.page.delegate

import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.animation.OvershootInterpolator
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.PageDirection
import kotlin.math.*

class NoAnimPageDelegate(readView: ReadView) : HorizontalPageDelegate(readView) {

    private var mTouchX = 0.1f
    private var mTouchY = 0.1f
    private var mCornerX = 0
    private var mCornerY = 0
    private var mLastTouchX = 0f
    private var mLastTouchY = 0f
    
    private val mBezierPath = Path()
    private val mBackContentPath = Path()
    private val mEdgeShadowPath = Path()
    private val mPaint = Paint().apply {
        isAntiAlias = true
        isDither = true
    }
    
    // 贝塞尔曲线控制点
    private val mBezierControl1 = PointF()
    private val mBezierControl2 = PointF()
    private val mBezierStart1 = PointF()
    private val mBezierStart2 = PointF()
    private val mBezierEnd1 = PointF()
    private val mBezierEnd2 = PointF()
    private val mBezierVertex1 = PointF()
    private val mBezierVertex2 = PointF()
    
    // 阴影系统
    private val mBackShadowColors = intArrayOf(0x44333333, 0x08333333)
    private val mBackShadowDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT, 
        mBackShadowColors
    ).apply {
        gradientType = GradientDrawable.LINEAR_GRADIENT
        cornerRadius = 0f
    }
    
    private val mEdgeShadowPaint = Paint().apply {
        color = 0x55333333
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private val mProjectionPaint = Paint().apply {
        shader = RadialGradient(
            0f, 0f, 100f,
            intArrayOf(0x33333333, 0x05333333),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        isAntiAlias = true
    }
    
    // 弹性插值器
    private val mOvershootInterpolator = OvershootInterpolator(0.6f)
    private var mDragDistance = 0f
    private var mMaxDragDistance = 0f
    
    private var curBitmap: Bitmap? = null
    private var nextBitmap: Bitmap? = null
    private val mColorFilter = LightingColorFilter(0xFFFFFFFF, 0xFFAAAAAA.toInt())

    override fun setBitmap() {
        when (mDirection) {
            PageDirection.NEXT -> {
                nextBitmap = nextPage.screenshot(nextBitmap, Canvas())
                curBitmap = curPage.screenshot(curBitmap, Canvas())
                mMaxDragDistance = hypot(viewWidth.toDouble(), viewHeight.toDouble()).toFloat()
            }
            PageDirection.PREV -> {
                // 处理上一页
            }
            else -> Unit
        }
    }

    override fun onTouch(event: MotionEvent) {
        super.onTouch(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                calcCornerXY(event.x, event.y)
                mLastTouchX = event.x
                mLastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - mLastTouchX)
                val dy = abs(event.y - mLastTouchY)
                mDragDistance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                mLastTouchX = event.x
                mLastTouchY = event.y
            }
        }
    }

    override fun onAnimStart(animationSpeed: Int) {
        val dx: Float
        val dy: Float
        
        if (isCancel) {
            dx = if (mDirection == PageDirection.NEXT) (viewWidth - touchX) else -touchX
            dy = if (mCornerY > 0) (viewHeight - touchY) else -touchY
        } else {
            dx = if (mDirection == PageDirection.NEXT) -(viewWidth + touchX) else (viewWidth - touchX)
            dy = if (mCornerY > 0) (viewHeight - touchY) else (1 - touchY)
        }
        
        // 应用弹性效果的速度计算
        val baseSpeed = 400
        val elasticFactor = mOvershootInterpolator.getInterpolation(
            (mDragDistance / mMaxDragDistance).coerceIn(0f, 1f)
        )
        val speed = (baseSpeed * (1 - elasticFactor * 0.3f)).toInt()
        
        startScroll(touchX.toInt(), touchY.toInt(), dx.toInt(), dy.toInt(), speed)
    }

    override fun onDraw(canvas: Canvas) {
        if (!isRunning) return
        
        calcEnhancedFlipPoints()
        
        when (mDirection) {
            PageDirection.NEXT -> {
                drawCurrentPageArea(canvas, curBitmap)
                drawFlippingPageWithQuality(canvas, nextBitmap)
                drawMultiLayerShadows(canvas)
                drawEdgeShadow(canvas)
            }
            else -> return
        }
    }

    private fun calcCornerXY(x: Float, y: Float) {
        mCornerX = if (x <= viewWidth / 2) 0 else viewWidth
        mCornerY = if (y <= viewHeight / 2) 0 else viewHeight
    }

    private fun calcEnhancedFlipPoints() {
        mTouchX = touchX
        mTouchY = touchY

        val middleX = (mTouchX + mCornerX) / 2
        val middleY = (mTouchY + mCornerY) / 2
        
        // 动态弯曲度 - 根据拖拽距离调整
        val curvature = 0.15f + (mDragDistance / mMaxDragDistance) * 0.2f
        
        // 主要控制点 - 更自然的曲线
        mBezierControl1.x = middleX - (mCornerY - middleY) * curvature
        mBezierControl1.y = mCornerY.toFloat()
        
        mBezierControl2.x = mCornerX.toFloat()
        mBezierControl2.y = middleY - (mCornerX - middleX) * curvature
        
        // 起点 - 更平滑的连接
        mBezierStart1.x = mBezierControl1.x - (mCornerX - mBezierControl1.x) * 0.25f
        mBezierStart1.y = mCornerY.toFloat()
        
        mBezierStart2.x = mCornerX.toFloat()
        mBezierStart2.y = mBezierControl2.y - (mCornerY - mBezierControl2.y) * 0.25f
        
        // 计算交点
        mBezierEnd1 = getCross(
            PointF(mTouchX, mTouchY),
            mBezierControl1,
            mBezierStart1,
            mBezierStart2
        )
        
        mBezierEnd2 = getCross(
            PointF(mTouchX, mTouchY),
            mBezierControl2,
            mBezierStart1,
            mBezierStart2
        )
        
        // 计算顶点用于更平滑的曲线
        mBezierVertex1.x = (mBezierStart1.x + 2 * mBezierControl1.x + mBezierEnd1.x) / 4
        mBezierVertex1.y = (2 * mBezierControl1.y + mBezierStart1.y + mBezierEnd1.y) / 4
        mBezierVertex2.x = (mBezierStart2.x + 2 * mBezierControl2.x + mBezierEnd2.x) / 4
        mBezierVertex2.y = (2 * mBezierControl2.y + mBezierStart2.y + mBezierEnd2.y) / 4
    }

    private fun drawCurrentPageArea(canvas: Canvas, bitmap: Bitmap?) {
        bitmap ?: return
        
        // 使用更平滑的贝塞尔曲线
        mBezierPath.reset()
        mBezierPath.moveTo(mBezierStart1.x, mBezierStart1.y)
        mBezierPath.quadTo(mBezierControl1.x, mBezierControl1.y, mBezierEnd1.x, mBezierEnd1.y)
        mBezierPath.lineTo(mTouchX, mTouchY)
        mBezierPath.lineTo(mBezierEnd2.x, mBezierEnd2.y)
        mBezierPath.quadTo(mBezierControl2.x, mBezierControl2.y, mBezierStart2.x, mBezierStart2.y)
        mBezierPath.close()
        
        canvas.save()
        canvas.clipPath(mBezierPath)
        canvas.drawBitmap(bitmap, 0f, 0f, mPaint)
        canvas.restore()
    }

    private fun drawFlippingPageWithQuality(canvas: Canvas, bitmap: Bitmap?) {
        bitmap ?: return
        
        mBackContentPath.reset()
        mBackContentPath.moveTo(mBezierStart1.x, mBezierStart1.y)
        mBackContentPath.lineTo(mBezierEnd1.x, mBezierEnd1.y)
        mBackContentPath.lineTo(mTouchX, mTouchY)
        mBackContentPath.lineTo(mBezierEnd2.x, mBezierEnd2.y)
        mBackContentPath.lineTo(mBezierStart2.x, mBezierStart2.y)
        mBackContentPath.close()
        
        canvas.save()
        canvas.clipPath(mBackContentPath)
        
        // 高质量的透视变换矩阵
        val matrix = Matrix()
        val srcPoints = floatArrayOf(
            0f, 0f,
            viewWidth.toFloat(), 0f,
            viewWidth.toFloat(), viewHeight.toFloat(),
            0f, viewHeight.toFloat()
        )
        
        // 更精确的目标点，考虑弯曲效果
        val curvatureOffset = (mDragDistance / mMaxDragDistance) * 20f
        val dstPoints = floatArrayOf(
            mBezierStart1.x, mBezierStart1.y,
            mBezierEnd1.x - curvatureOffset, mBezierEnd1.y,
            mTouchX - curvatureOffset * 1.5f, mTouchY,
            mBezierEnd2.x, mBezierEnd2.y - curvatureOffset
        )
        
        if (matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)) {
            // 应用颜色滤镜模拟纸张背面
            mPaint.colorFilter = mColorFilter
            canvas.drawBitmap(bitmap, matrix, mPaint)
            mPaint.colorFilter = null
        }
        
        canvas.restore()
    }

    private fun drawMultiLayerShadows(canvas: Canvas) {
        // 背面渐变阴影
        mBackContentPath.reset()
        mBackContentPath.moveTo(mBezierStart1.x, mBezierStart1.y)
        mBackContentPath.lineTo(mBezierEnd1.x, mBezierEnd1.y)
        mBackContentPath.lineTo(mTouchX, mTouchY)
        mBackContentPath.lineTo(mBezierEnd2.x, mBezierEnd2.y)
        mBackContentPath.lineTo(mBezierStart2.x, mBezierStart2.y)
        mBackContentPath.close()
        
        canvas.save()
        canvas.clipPath(mBackContentPath)
        
        // 动态阴影强度
        val shadowIntensity = (mDragDistance / mMaxDragDistance).coerceIn(0.1f, 0.8f)
        val shadowWidth = (50 * shadowIntensity).toInt()
        
        mBackShadowDrawable.setBounds(
            (mBezierStart1.x - shadowWidth).toInt(),
            mBezierStart1.y.toInt(),
            (mBezierStart1.x + shadowWidth).toInt(),
            (viewHeight + 100).toInt()
        )
        mBackShadowDrawable.alpha = (255 * shadowIntensity).toInt()
        mBackShadowDrawable.draw(canvas)
        
        canvas.restore()
        
        // 页面投影
        drawPageProjection(canvas)
    }

    private fun drawEdgeShadow(canvas: Canvas) {
        // 边缘细阴影线
        mEdgeShadowPath.reset()
        mEdgeShadowPath.moveTo(mBezierStart1.x, mBezierStart1.y)
        mEdgeShadowPath.quadTo(mBezierControl1.x, mBezierControl1.y, mBezierEnd1.x, mBezierEnd1.y)
        mEdgeShadowPath.lineTo(mTouchX, mTouchY)
        mEdgeShadowPath.lineTo(mBezierEnd2.x, mBezierEnd2.y)
        mEdgeShadowPath.quadTo(mBezierControl2.x, mBezierControl2.y, mBezierStart2.x, mBezierStart2.y)
        
        canvas.save()
        mEdgeShadowPaint.alpha = (150 * (mDragDistance / mMaxDragDistance).coerceIn(0.2f, 1f)).toInt()
        canvas.drawPath(mEdgeShadowPath, mEdgeShadowPaint)
        canvas.restore()
    }

    private fun drawPageProjection(canvas: Canvas) {
        // 页面在背景上的投影
        val projectionAlpha = (80 * (mDragDistance / mMaxDragDistance).coerceIn(0f, 1f)).toInt()
        if (projectionAlpha > 10) {
            mProjectionPaint.alpha = projectionAlpha
            
            // 投影位置（翻起页面的阴影）
            val centerX = (mBezierStart1.x + mBezierEnd1.x + mTouchX + mBezierEnd2.x) / 4
            val centerY = (mBezierStart1.y + mBezierEnd1.y + mTouchY + mBezierEnd2.y) / 4
            
            canvas.save()
            canvas.translate(centerX, centerY)
            canvas.drawCircle(0f, 0f, 80f, mProjectionPaint)
            canvas.restore()
        }
    }

    private fun getCross(P1: PointF, P2: PointF, P3: PointF, P4: PointF): PointF {
        val crossP = PointF()
        val a1 = (P2.y - P1.y) / (P2.x - P1.x)
        val b1 = (P1.x * P2.y - P2.x * P1.y) / (P1.x - P2.x)
        val a2 = (P4.y - P3.y) / (P4.x - P3.x)
        val b2 = (P3.x * P4.y - P4.x * P3.y) / (P3.x - P4.x)
        
        if (abs(a1 - a2) < 0.001f) {
            // 平行线情况
            crossP.x = (P1.x + P3.x) / 2
            crossP.y = (P1.y + P3.y) / 2
        } else {
            crossP.x = (b2 - b1) / (a1 - a2)
            crossP.y = a1 * crossP.x + b1
        }
        
        return crossP
    }

    override fun onAnimStop() {
        if (!isCancel) {
            readView.fillPage(mDirection)
        }
        // 重置状态
        mDragDistance = 0f
    }
}
