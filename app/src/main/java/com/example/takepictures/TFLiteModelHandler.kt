package com.example.takepictures

import android.content.Context
import android.graphics.*
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TFLiteModelHandler(private val context: Context) {

    private val interpreter: Interpreter
    private val labels: List<String>

    init {
        val model = FileUtil.loadMappedFile(context, "v201_061224_metadata.tflite")
        interpreter = Interpreter(model)
        labels = FileUtil.loadLabels(context, "labelmap.txt")
    }

    fun runInference(bitmap: Bitmap): Bitmap {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 320, 320, true)
        val inputBuffer = bitmapToByteBuffer(resizedBitmap)

        val detectionBoxes = Array(1) { Array(10) { FloatArray(4) } }
        val detectionClasses = Array(1) { FloatArray(10) }
        val detectionScores = Array(1) { FloatArray(10) }
        val numDetections = FloatArray(1)

        val outputMap = mapOf(
            0 to detectionScores,
            1 to detectionBoxes,
            2 to numDetections,
            3 to detectionClasses
        )

        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)

        logModelOutputs(detectionBoxes, detectionClasses, detectionScores, numDetections)

        return drawBoundingBoxes(bitmap, detectionBoxes, detectionClasses, detectionScores, numDetections)
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(1 * 320 * 320 * 3 * 4)
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(320 * 320)
        bitmap.getPixels(intValues, 0, 320, 0, 0, 320, 320)
        var pixel = 0
        for (i in 0 until 320) {
            for (j in 0 until 320) {
                val value = intValues[pixel++]
                byteBuffer.putFloat((Color.red(value) - 128) / 128.0f)
                byteBuffer.putFloat((Color.green(value) - 128) / 128.0f)
                byteBuffer.putFloat((Color.blue(value) - 128) / 128.0f)
            }
        }
        return byteBuffer
    }

    private fun logModelOutputs(
        detectionBoxes: Array<Array<FloatArray>>,
        detectionClasses: Array<FloatArray>,
        detectionScores: Array<FloatArray>,
        numDetections: FloatArray
    ) {
        Log.d("TFLiteModelHandler", "Detection Boxes: ${detectionBoxes.contentDeepToString()}")
        Log.d("TFLiteModelHandler", "Detection Classes: ${detectionClasses.contentDeepToString()}")
        Log.d("TFLiteModelHandler", "Detection Scores: ${detectionScores.contentDeepToString()}")
        Log.d("TFLiteModelHandler", "Number of Detections: ${numDetections.contentToString()}")
    }

    private fun drawBoundingBoxes(
        originalBitmap: Bitmap,
        detectionBoxes: Array<Array<FloatArray>>,
        detectionClasses: Array<FloatArray>,
        detectionScores: Array<FloatArray>,
        numDetections: FloatArray
    ): Bitmap {
        val outputBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(outputBitmap)
        val paint = Paint()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8f

        val textPaint = Paint()
        textPaint.textSize = 48f
        textPaint.style = Paint.Style.FILL

        val classColors = listOf(
            Color.RED,
            Color.GREEN,
            Color.BLUE,
            Color.YELLOW,
            Color.CYAN,
            Color.MAGENTA,
            Color.BLACK,
            Color.GRAY,
            Color.DKGRAY,
            Color.LTGRAY
        )

        for (i in 0 until numDetections[0].toInt()) {
            val score = detectionScores[0][i]
            if (score < 0.9) continue

            val box = detectionBoxes[0][i]
            val left = box[1] * originalBitmap.width
            val top = box[0] * originalBitmap.height
            val right = box[3] * originalBitmap.width
            val bottom = box[2] * originalBitmap.height

            val classIndex = detectionClasses[0][i].toInt()
            val label = if (classIndex < labels.size) labels[classIndex] else "Unknown"
            paint.color = classColors[classIndex % classColors.size]
            textPaint.color = paint.color

            canvas.drawRect(left, top, right, bottom, paint)
            canvas.drawText(label, left, top - 40, textPaint)
            canvas.drawText(String.format("%.2f", score), left, top - 80, textPaint)
        }

        return outputBitmap
    }
}
