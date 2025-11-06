package io.legado.app.ui.book.read.page.delegate

import android.graphics.Canvas
import io.legado.app.ui.book.read.page.ReadView

class NoAnimPageDelegate(readView: ReadView) : HorizontalPageDelegate(readView) {

    private var flipProgress = 0f
    private var isAnimating = false

    override fun onAnimStart(animationSpeed: Int) {
        if (!isCancel) {
            readView.fillPage(mDirection)
            isAnimating = true
            flipProgress = 0f
        }
        stopScroll()
    }

    override fun setBitmap() {
        super.setBitmap()
    }

    override fun onDraw(canvas: Canvas) {
        if (!isAnimating) {
            super.onDraw(canvas)
            return
        }
        
        // 简单的滑动效果
        val offset = readView.width * flipProgress
        
        if (mDirection) {
            // 向右翻页
            canvas.save()
            canvas.translate(-offset, 0f)
            readView.curPage?.draw(canvas)
            canvas.restore()
            
            canvas.save()
            canvas.translate(readView.width - offset, 0f)
            readView.nextPage?.draw(canvas)
            canvas.restore()
        } else {
            // 向左翻页
            canvas.save()
            canvas.translate(offset, 0f)
            readView.curPage?.draw(canvas)
            canvas.restore()
            
            canvas.save()
            canvas.translate(offset - readView.width, 0f)
            readView.nextPage?.draw(canvas)
            canvas.restore()
        }
        
        flipProgress += 0.05f
        if (flipProgress >= 1f) {
            isAnimating = false
            readView.pageAnimEnd()
        }
        
        readView.invalidate()
    }

    override fun onAnimStop() {
        isAnimating = false
        flipProgress = 0f
        super.onAnimStop()
    }
}
