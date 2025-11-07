package io.legado.app.ui.book.read.page.delegate

import android.graphics.*
import android.view.MotionEvent
import androidx.core.graphics.withTranslation
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.utils.screenshot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * 仿 Google Play Books 3D 卷曲翻页效果
 * 继承自 HorizontalPageDelegate 以便使用 curRecorder 和 nextRecorder
 */
class CurlPageDelegate(readView: ReadView) : HorizontalPageDelegate(readView) {

    private var mCornerX = 0
    private var mCornerY = 0
    private val mPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mCamera = Camera()
    private val mMatrix = Matrix()

    init {
        mPaint.style = Paint.Style.FILL
        mShadowPaint.shader = LinearGradient(
            0f, 0f, 100f, 0f,
            intArrayOf(0x55000000, 0x00000000),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                abortAnim()
                val dragToLeft = event.x > viewWidth / 2
                
                // 设置方向
                if (dragToLeft) {
                    if (!hasNext()) return
                    setDirection(PageDirection.NEXT)
                    mCornerX = viewWidth
                } else {
                    if (!hasPrev()) return
                    setDirection(PageDirection.PREV)
                    mCornerX = 0
                }
                
                mCornerY = viewHeight
                readView.setStartPoint(event.x, event.y, false)
                isRunning = true
            }
            
            MotionEvent.ACTION_MOVE -> {
                if (isRunning) {
                    readView.setTouchPoint(event.x, event.y)
                }
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isRunning) {
                    val dx = mCornerX - touchX
                    val dy = mCornerY - touchY
                    val dist = sqrt(dx * dx + dy * dy)
                    val dragRatio = dist / viewWidth
                    
                    // 如果拖动超过一半，完成翻页；否则回弹
                    isCancel = dragRatio < 0.5f
                    onAnimStart(readView.defaultAnimationSpeed)
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!isRunning) {
            // 绘制静态页面
            curRecorder.draw(canvas)
            return
        }

        // 计算拖动距离和比例
        val dx = mCornerX - touchX
        val dy = mCornerY - touchY
        val dist = sqrt(dx * dx + dy * dy)
        val dragRatio = min(1f, dist / viewWidth)
        val angle = 45f * dragRatio

        // 计算波浪形弯曲
        val waveHeight = max(0f, min(dragRatio, 1f)) * viewHeight / 3
        val waveOffset = 2f * PI * dragRatio

        // 设置3D变换矩阵
        mCamera.save()
        if (mDirection == PageDirection.NEXT) {
            mCamera.rotateY(-angle)
        } else {
            mCamera.rotateY(angle)
        }
        mCamera.getMatrix(mMatrix)
        mCamera.restore()

        // 应用变换
        mMatrix.preTranslate(-touchX, -touchY)
        mMatrix.postTranslate(touchX, touchY)

        // 绘制背景页（目标页）
        when (mDirection) {
            PageDirection.NEXT -> {
                canvas.withTranslation(-viewWidth + touchX, 0f) {
                    nextRecorder.draw(this)
                }
            }
            PageDirection.PREV -> {
                canvas.withTranslation(viewWidth + touchX, 0f) {
                    prevRecorder.draw(this)
                }
            }
            else -> return
        }

        // 绘制当前页（带3D变换）
        canvas.save()
        canvas.concat(mMatrix)
        curRecorder.draw(canvas)
        canvas.restore()

        // 添加渐变阴影
        val shadowWidth = 80 * dragRatio
        val shadowRect = if (mDirection == PageDirection.NEXT) {
            RectF(touchX, 0f, touchX + shadowWidth, viewHeight.toFloat())
        } else {
            RectF(touchX - shadowWidth, 0f, touchX, viewHeight.toFloat())
        }
        canvas.drawRect(shadowRect, mShadowPaint)
    }

    override fun setBitmap() {
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
    }

    override fun setViewSize(width: Int, height: Int) {
        super.setViewSize(width, height)
        mCornerY = height
    }

    override fun onAnimStop() {
        if (!isCancel) {
            readView.fillPage(mDirection)
        }
    }

    override fun onAnimStart(animationSpeed: Int) {
        val distanceX: Float
        when (mDirection) {
            PageDirection.NEXT -> {
                distanceX = if (isCancel) {
                    // 回弹：回到起始位置
                    viewWidth - touchX
                } else {
                    // 完成翻页：继续移动到下一页
                    -(touchX + (viewWidth - startX))
                }
            }
            else -> {
                distanceX = if (isCancel) {
                    // 回弹
                    -(touchX - startX)
                } else {
                    // 完成翻页
                    viewWidth - (touchX - startX)
                }
            }
        }
        startScroll(touchX.toInt(), 0, distanceX.toInt(), 0, animationSpeed)
    }

    override fun abortAnim() {
        isStarted = false
        isMoved = false
        isRunning = false
        if (!scroller.isFinished) {
            readView.isAbortAnim = true
            scroller.abortAnimation()
            if (!isCancel) {
                readView.fillPage(mDirection)
                readView.invalidate()
            }
        } else {
            readView.isAbortAnim = false
        }
    }

    override fun nextPageByAnim(animationSpeed: Int) {
        abortAnim()
        if (!hasNext()) return
        setDirection(PageDirection.NEXT)
        mCornerX = viewWidth
        mCornerY = viewHeight
        readView.setStartPoint(viewWidth.toFloat() * 0.9f, viewHeight.toFloat() * 0.5f, false)
        onAnimStart(animationSpeed)
    }

    override fun prevPageByAnim(animationSpeed: Int) {
        abortAnim()
        if (!hasPrev()) return
        setDirection(PageDirection.PREV)
        mCornerX = 0
        mCornerY = viewHeight
        readView.setStartPoint(viewWidth.toFloat() * 0.1f, viewHeight.toFloat() * 0.5f, false)
        onAnimStart(animationSpeed)
    }
}
