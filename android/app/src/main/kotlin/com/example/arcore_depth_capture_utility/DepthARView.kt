package com.example.arcore_depth_capture_utility

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper
import com.google.ar.core.examples.java.common.rendering.BackgroundRenderer
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.beyka.tiffbitmapfactory.TiffSaver
import org.beyka.tiffbitmapfactory.CompressionScheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteOrder

class DepthView(
    private val activity: Activity,
    private val context: Context,
    messenger: BinaryMessenger,
    id: Int,
    private val lifecycle: Lifecycle
) : PlatformView, DefaultLifecycleObserver, MethodChannel.MethodCallHandler, GLSurfaceView.Renderer {

    private val glSurfaceView: GLSurfaceView
    private val displayRotationHelper: DisplayRotationHelper
    private val backgroundRenderer = BackgroundRenderer()
    private var session: Session? = null
    private val mainScope = CoroutineScope(Dispatchers.Main)

    init {
        glSurfaceView = GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            setRenderer(this@DepthView)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        displayRotationHelper = DisplayRotationHelper(context)
        val methodChannel = MethodChannel(messenger, "com.example.arcore_depth_capture_utility/depth_ar_channel")
        methodChannel.setMethodCallHandler(this)
        lifecycle.addObserver(this)
    }

    override fun getView(): View = glSurfaceView

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method == "captureTiff") {
            captureTiffData(result)
        } else {
            result.notImplemented()
        }
    }

    private fun captureTiffData(result: MethodChannel.Result) {
        val currentSession = session ?: return
        
        // We stay on the GL thread just long enough to acquire the images
        glSurfaceView.queueEvent {
            try {
                val frame = currentSession.update()
                
                // Acquire images and extract data immediately on the GL thread
                val depthImage = frame.acquireRawDepthImage16Bits()
                val cameraImage = frame.acquireCameraImage()
                
                // Convert to data structures that persist outside the GL/ARCore lifecycle
                val depthShorts = extract16BitDepth(depthImage)
                val rgbBitmap = imageToBitmap(cameraImage)
                val width = depthImage.width
                val height = depthImage.height
                
                // Get intrinsics
                val intrinsics = frame.camera.textureIntrinsics
                val metadata = "fx:${intrinsics.focalLength[0]},fy:${intrinsics.focalLength[1]},cx:${intrinsics.principalPoint[0]},cy:${intrinsics.principalPoint[1]}"

                // Close native images immediately to free up ARCore buffers 
                depthImage.close()
                cameraImage.close()

                // Switch to a background thread for heavy I/O and TIFF encoding
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.tiff")
                        val options = TiffSaver.SaveOptions().apply {
                            author = "ARCore Utility"
                            imageDescription = metadata
                            compressionScheme = CompressionScheme.LZW 
                        }

                        // Perform the heavy saving operations on the IO thread
                        TiffSaver.saveBitmap(file.absolutePath, rgbBitmap, options)
                        
                        val depthBitmap = packDepthIntoBitmap(depthShorts, width, height)
                        val appendSuccess = TiffSaver.appendBitmap(file.absolutePath, depthBitmap, options)

                        val finalSize = file.length()
                        
                        // Return result to Flutter on the Main thread
                        mainScope.launch {
                            if (appendSuccess && finalSize > 0) {
                                result.success(file.absolutePath) 
                            } else {
                                result.error("SAVE_FAILED", "File is empty or append failed", null) 
                            }
                        }
                    } catch (e: Exception) {
                        mainScope.launch { result.error("IO_ERROR", e.message, null) }
                    }
                }
            } catch (e: Exception) {
                mainScope.launch { result.error("CAPTURE_FAILED", e.message, null)  }
            }
        }
    }

    private fun extract16BitDepth(image: android.media.Image): ShortArray {
        // Plane 0 contains the depth data in millimeters as 16-bit integers
        val buffer = image.planes[0].buffer.order(ByteOrder.LITTLE_ENDIAN)
        val shortArray = ShortArray(buffer.remaining() / 2)
        buffer.asShortBuffer().get(shortArray)
        return shortArray
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap {
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // Copy Y plane
        yBuffer.get(nv21, 0, ySize)

        // Interleave U and V planes for NV21 format (V, U, V, U...)
        // This format is required by YuvImage to prevent the green tint
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        
        // Compress to JPEG to handle the YUV to RGB conversion natively
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
        
        // Use toByteArray() to get the exact compressed data size
        val compressedBytes = out.toByteArray()
        
        // Pass the exact length of the compressed array
        return BitmapFactory.decodeByteArray(compressedBytes, 0, compressedBytes.size)
    }

    private fun packDepthIntoBitmap(shorts: ShortArray, w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        for (i in shorts.indices) {
            val depth = shorts[i].toInt() and 0xFFFF
            val r = (depth shr 8) and 0xFF
            val g = depth and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8)
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        backgroundRenderer.createOnGlThread(context)
        
        // Safety check for camera permission on the native side
        try {
            session = Session(context).apply {
                val arConfig = Config(this)

                arConfig.focusMode = Config.FocusMode.AUTO

                if (isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    arConfig.depthMode = Config.DepthMode.AUTOMATIC 
                }
                configure(arConfig)
                resume() 
            }
        } catch (e: Exception) {
            Log.e("DepthView", "ARCore session failed to initialize: ${e.message}")
            // Inform Flutter via a separate MethodChannel if needed
        }
    }

    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        session?.let {
            displayRotationHelper.updateSessionIfNeeded(it)
            it.setCameraTextureName(backgroundRenderer.textureId)
            backgroundRenderer.draw(it.update())
        }
    }

    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun dispose() {
        session?.close()
        session = null
    }
}