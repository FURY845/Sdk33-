// MainActivity.kt - النشاط الرئيسي
package com.fastdetector

import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.display.*
import android.media.ImageReader
import android.media.projection.*
import android.os.*
import android.view.*
import android.widget.*
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.*
import java.nio.channels.FileChannel
import kotlin.math.*

class MainActivity : Activity() {
    
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var overlayView: OverlayView? = null
    private var windowManager: WindowManager? = null
    private var interpreter: Interpreter? = null
    private var isDetecting = false
    
    companion object {
        private const val REQUEST_CODE = 1001
        private const val INPUT_SIZE = 320  // حجم صغير للسرعة
        private const val PERSON_CLASS = 0  // فئة الشخص
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // تحميل النموذج مرة واحدة
        loadModel()
        
        // زر بدء الكشف
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (isDetecting) {
                stopDetection()
            } else {
                requestScreenCapture()
            }
        }
    }
    
    private fun loadModel() {
        try {
            val modelFile = assets.openFd("yolov8n_lite.tflite")
            val inputStream = FileInputStream(modelFile.fileDescriptor)
            val fileChannel = inputStream.channel
            val modelBuffer = fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                modelFile.startOffset,
                modelFile.declaredLength
            )
            
            interpreter = Interpreter(modelBuffer)
            inputStream.close()
            modelFile.close()
        } catch (e: Exception) {
            Toast.makeText(this, "فشل تحميل النموذج", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun requestScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK) {
            mediaProjection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                .getMediaProjection(resultCode, data!!)
            startCapture()
            showOverlay()
        }
    }
    
    private fun startCapture() {
        imageReader = ImageReader.newInstance(
            INPUT_SIZE, INPUT_SIZE,
            PixelFormat.RGBA_8888, 2
        )
        
        mediaProjection?.createVirtualDisplay(
            "Detection",
            INPUT_SIZE, INPUT_SIZE,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
        
        isDetecting = true
        
        // بدء الكشف المستمر
        CoroutineScope(Dispatchers.Default).launch {
            while (isDetecting) {
                processFrame()
                delay(50) // 20 FPS
            }
        }
    }
    
    private fun processFrame() {
        val image = imageReader?.acquireLatestImage() ?: return
        
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            
            // تحويل الصورة لـ Bitmap
            val bitmap = Bitmap.createBitmap(
                image.width, image.height, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            
            // كشف اللاعبين
            val detections = detectPersons(bitmap)
            
            // تحديث المربعات فوراً
            runOnUiThread {
                overlayView?.updateBoxes(detections)
            }
            
            bitmap.recycle()
        } finally {
            image.close()
        }
    }
    
    private fun detectPersons(bitmap: Bitmap): List<RectF> {
        val boxes = mutableListOf<RectF>()
        
        // تحضير الإدخال
        val inputBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())
        
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        
        val floatBuffer = inputBuffer.asFloatBuffer()
        for (pixel in pixels) {
            floatBuffer.put(((pixel shr 16) and 0xFF) / 255f)
            floatBuffer.put(((pixel shr 8) and 0xFF) / 255f)
            floatBuffer.put((pixel and 0xFF) / 255f)
        }
        
        // تشغيل النموذج
        val outputBuffer = ByteBuffer.allocateDirect(8400 * 85 * 4)
        outputBuffer.order(ByteOrder.nativeOrder())
        
        interpreter?.run(inputBuffer, outputBuffer)
        
        // استخراج النتائج - الأشخاص فقط
        val output = outputBuffer.asFloatBuffer()
        val outputArray = FloatArray(8400 * 85)
        output.get(outputArray)
        
        for (i in 0 until 8400) {
            val base = i * 85
            
            // فقط فئة الشخص (class 0)
            val confidence = outputArray[base + 4 + PERSON_CLASS]
            
            if (confidence > 0.4f) {  // عتبة الثقة
                val cx = outputArray[base] * INPUT_SIZE
                val cy = outputArray[base + 1] * INPUT_SIZE
                val w = outputArray[base + 2] * INPUT_SIZE
                val h = outputArray[base + 3] * INPUT_SIZE
                
                val x1 = cx - w / 2
                val y1 = cy - h / 2
                val x2 = cx + w / 2
                val y2 = cy + h / 2
                
                boxes.add(RectF(x1, y1, x2, y2))
            }
        }
        
        return applyNMS(boxes, confidence = 0.4f)
    }
    
    private fun applyNMS(boxes: List<RectF>, confidence: Float): List<RectF> {
        // NMS بسيط وسريع
        if (boxes.size <= 1) return boxes
        
        val sorted = boxes.sortedByDescending { it.width() * it.height() }
        val keep = mutableListOf<RectF>()
        
        for (box in sorted) {
            var overlap = false
            for (kept in keep) {
                val intersection = RectF(box).apply { intersect(kept) }
                if (intersection.width() * intersection.height() > 
                    box.width() * box.height() * 0.5f) {
                    overlap = true
                    break
                }
            }
            if (!overlap) keep.add(box)
        }
        
        return keep
    }
    
    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        overlayView = OverlayView(this)
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        
        windowManager?.addView(overlayView, params)
    }
    
    private fun stopDetection() {
        isDetecting = false
        imageReader?.close()
        mediaProjection?.stop()
        windowManager?.removeView(overlayView)
    }
}
