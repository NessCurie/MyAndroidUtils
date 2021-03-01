package com.nesscurie.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.nesscurie.androidutils.R
import org.jetbrains.anko.longToast

object ScreenTest {

    var onScreenTestOverListener: (() -> Unit)? = null
    private lateinit var context: Context
    private var touchPassed = false
    private var deadPixelsTime = 5000L
    private val content: View by lazy { TouchView(context) }

    @Suppress("DEPRECATION")
    private val params: WindowManager.LayoutParams by lazy {
        val params = WindowManager.LayoutParams()
        params.type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        params.format = PixelFormat.TRANSLUCENT
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.height = WindowManager.LayoutParams.MATCH_PARENT
        params.x = 0
        params.y = 0
        params.gravity = Gravity.CENTER
        params
    }
    private val windowManager: WindowManager by lazy {
        context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    fun start(context: Context, deadPixelsTime: Long = 5000) {
        ScreenTest.context = context
        ScreenTest.deadPixelsTime = deadPixelsTime
        windowManager.addView(content, params)
    }

    private fun onFinish() {
        params.width = 0
        params.height = 0
        params.x = 2000
        params.y = 0
        windowManager.updateViewLayout(content, params)
        onScreenTestOverListener?.invoke()
    }

    class TouchView(context: Context) : View(context) {

        companion object {
            private const val COL_WIDTH_HEIGHT = 31

            /*private const val WIDTH_BASIS = 19
            private const val HEIGHT_BASIS = 28

            private const val WIDTH_BASIS_768X1024 = 19
            private const val HEIGHT_BASIS_768X1024 = 28

            private const val WIDTH_BASIS_1024X600 = 33
            private const val HEIGHT_BASIS_1024X600 = 19
            private const val WIDTH_BASIS_CROSS = 2 * (WIDTH_BASIS - 2)
            private const val HEIGHT_BASIS_CROSS = WIDTH_BASIS_CROSS*/
        }

        private val screenDisplay: Point by lazy {
            val point = Point()
            windowManager.defaultDisplay.getRealSize(point)
            point
        }

        private val screenWidth by lazy { screenDisplay.x }
        private val screenHeight by lazy { screenDisplay.y }

        private val matrixBitmap: Bitmap by lazy {
            Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.RGB_565)
        }
        private val matrixCanvas: Canvas by lazy {
            val canvas = Canvas(matrixBitmap)
            canvas.drawColor(-1)
            canvas
        }

        private val widthBasis: Int by lazy { screenWidth / COL_WIDTH_HEIGHT }
        private val heightBasis: Int by lazy { screenHeight / COL_WIDTH_HEIGHT }
        private val widthBasisCross: Int by lazy { 2 * (widthBasis - 2) }
        private val heightBasisCross: Int by lazy { widthBasisCross }

        private val colWidth: Float by lazy { screenWidth.toFloat() / widthBasis }
        private val colHeight: Float by lazy { screenHeight.toFloat() / heightBasis }

        private val widthCross: Float by lazy { colWidth / 2.0f }
        private val heightCross: Float by lazy { (screenHeight - colHeight) / (4 + (widthBasisCross - 1)) }

        private val draw: Array<IntArray> by lazy { Array(heightBasis) { IntArray(widthBasis) } }
        private val isDrawArea: Array<IntArray> by lazy { Array(heightBasis) { IntArray(widthBasis) } }
        private val drawCross = IntArray(2 * heightBasisCross)

        private var touchedX = 0.0f
        private var touchedY = 0.0f
        private var preTouchedX = 0.0f
        private var preTouchedY = 0.0f
        private var isTouchDown = false

        private val linePaint: Paint by lazy {
            val linePaint = Paint()
            linePaint.isAntiAlias = true
            linePaint.isDither = true
            linePaint.style = Paint.Style.STROKE
            linePaint.strokeJoin = Paint.Join.ROUND
            linePaint.strokeCap = Paint.Cap.SQUARE
            linePaint.strokeWidth = 5.0f
            val localDashPathEffect = DashPathEffect(floatArrayOf(5.0f, 5.0f), 1.0f)
            linePaint.pathEffect = localDashPathEffect
            linePaint.color = -16777216
            linePaint
        }
        private val clickPaint: Paint by lazy {
            val clickPaint = Paint()
            clickPaint.isAntiAlias = false
            clickPaint.style = Paint.Style.FILL
            clickPaint.color = -16711936
            clickPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            clickPaint
        }
        private val emptyPaint: Paint by lazy {
            val emptyPaint = Paint()
            emptyPaint.isAntiAlias = false
            emptyPaint.color = -1
            emptyPaint
        }
        private val deadPixelCheckColor: IntArray by lazy {
            intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.BLACK, Color.WHITE)
        }
        private var deadPixelCheckIndex = 0
        private val deadPixelCheck = object : Runnable {
            override fun run() {
                if (deadPixelCheckIndex < deadPixelCheckColor.size) {
                    matrixCanvas.drawColor(deadPixelCheckColor[deadPixelCheckIndex])
                    invalidate()
                    deadPixelCheckIndex++
                    postDelayed(this, deadPixelsTime)
                } else {
                    onFinish()
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.getToolType(0) == 2 || touchPassed) {
                return if (touchPassed) {
                    super.onTouchEvent(event)
                } else {
                    true
                }
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> drawDown(event)
                MotionEvent.ACTION_UP -> drawUp(event)
                MotionEvent.ACTION_MOVE -> drawMove(event)
                else -> {
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawBitmap(matrixBitmap, 0.0f, 0.0f, null)
        }

        init {
            context.longToast(R.string.screen_test_hint)
            val paint1 = Paint()
            val paint2 = Paint()
            paint1.color = -16777216
            paint2.color = -16777216
            paint2.style = Paint.Style.STROKE
            //先全部画格子
            for (i in 0..heightBasis) {
                val y = (colHeight * i)
                matrixCanvas.drawLine(0.0f, y, screenWidth.toFloat(), y, paint1)
            }
            for (i in 0..widthBasis) {
                val x = (colWidth * i)
                matrixCanvas.drawLine(x, 0.0f, x, screenHeight.toFloat(), paint1)
            }
            //把中间涂白  480时的涂白高度范围如果不上下缩小1会少点线,但是600的不会,应该是精度问题,其他也会,全部都这样好了
            matrixCanvas.drawRect(1 + colWidth, 1 + colHeight,
                    colWidth * (widthBasis - 1) - 1, colHeight * (heightBasis - 1) - 1, emptyPaint)

            //记录进数组
            for (i in 0 until heightBasis) {
                for (j in 0 until widthBasis) {
                    draw[i][j] = 0
                    if (i == 0 || i == heightBasis - 1 || j == 0 || j == widthBasis - 1) {
                        isDrawArea[i][j] = 1
                    }
                }
            }

            //再画斜向和记录进数组
            for (i in 0 until heightBasisCross) {
                val x1 = (widthCross * (i + 2))
                val y1 = heightCross * (i + 2)
                matrixCanvas.drawRect(x1, y1, x1 + colWidth / 2, y1 + colHeight, paint2)
                drawCross[i] = 0

                val x3 = (widthCross * (i + 2))
                val y3 = (screenHeight - (colHeight + heightCross * (i + 2)))
                matrixCanvas.drawRect(x3, y3, x3 + colWidth / 2, y3 + colHeight, paint2)
                drawCross[i + heightBasisCross] = 0
            }
        }

        private fun isPass(): Boolean {
            for (i in 0 until heightBasis) {
                for (j in 0 until widthBasis) {
                    if (isDrawArea[i][j] == 1) {
                        if (draw[i][j] == 0) {
                            return false
                        }
                    }
                }
            }
            return true
        }

        private fun isPassCross(): Boolean {
            for (i in 0 until 2 * heightBasisCross) {
                if (drawCross[i] == 0) {
                    return false
                }
            }
            return true
        }

        private fun drawDown(event: MotionEvent) {
            touchedX = event.x
            touchedY = event.y
            isTouchDown = drawRect(touchedX, touchedY, clickPaint)
        }

        private fun drawRect(x: Float, y: Float, paint: Paint): Boolean {
            val xIndex = (x / colWidth).toInt()
            val yIndex = (y / colHeight).toInt()
            if (xIndex >= 0 && yIndex >= 0 && xIndex < widthBasis && yIndex < heightBasis) {
                if (draw[yIndex][xIndex] == 0) {
                    draw[yIndex][xIndex] = 1
                    if (isDrawArea[yIndex][xIndex] != 0) {
                        val rectX = colWidth * xIndex
                        val rectY = colHeight * yIndex
                        matrixCanvas.drawRect(rectX, colHeight * yIndex, rectX + colWidth, rectY + colHeight, paint)
                        invalidate()
                    }
                }
                if (xIndex > 0 && xIndex < widthBasis - 1) {
                    checkCrossRectRegion(x, y, paint)
                }
                if (isPass() && isPassCross()) {
                    context.longToast(context.getString(R.string.screen_test_passed_hint)
                            + (deadPixelsTime / 1000)
                            + context.getString(R.string.screen_test_passed_hint2))
                    post(deadPixelCheck)
                    touchPassed = true
                    setOnClickListener {
                        removeCallbacks(deadPixelCheck)
                        if (deadPixelCheckIndex < deadPixelCheckColor.size) {
                            post(deadPixelCheck)
                        } else {
                            onFinish()
                        }
                    }
                    return false
                }
            }
            return true
        }

        private fun checkCrossRectRegion(x: Float, y: Float, paint: Paint) {
            val crossIndex = ((x - colWidth) / widthCross).toInt()
            val crossX = (widthCross * (crossIndex + 2))
            val crossY = (heightCross * (crossIndex + 2))

            if (y > crossY && y < crossY + colHeight) {
                if (drawCross[crossIndex] == 0) {
                    matrixCanvas.drawRect(crossX, crossY, crossX + widthCross, crossY + colHeight, paint)
                    invalidate()
                    drawCross[crossIndex] = 1
                }
            }
            val crossY2 = screenHeight - colHeight - crossY
            if (y > crossY2 && y < crossY2 + colHeight) {
                val index = 2 * widthBasisCross - 1 - crossIndex
                if (drawCross[index] == 0) {
                    matrixCanvas.drawRect(crossX, crossY2, (crossX + colWidth / 2.0f), (crossY2 + colHeight), paint)
                    invalidate()
                    drawCross[index] = 1
                }
            }
        }

        private fun drawMove(event: MotionEvent) {
            if (isTouchDown) {
                preTouchedX = touchedX
                preTouchedY = touchedY
                touchedX = event.x
                touchedY = event.y
                matrixCanvas.drawLine(preTouchedX, preTouchedY, touchedX, touchedY, linePaint)
                invalidate()
                isTouchDown = drawRect(touchedX, touchedY, clickPaint)
            }
        }

        private fun drawUp(paramMotionEvent: MotionEvent) {
            if (isTouchDown) {
                preTouchedX = touchedX
                preTouchedY = touchedY
                touchedX = paramMotionEvent.x
                touchedY = paramMotionEvent.y
                if (preTouchedX == touchedX && preTouchedY == touchedY) {
                    matrixCanvas.drawPoint(touchedX, touchedY, linePaint)
                    invalidate()
                }
                isTouchDown = false
            }
        }
    }
}