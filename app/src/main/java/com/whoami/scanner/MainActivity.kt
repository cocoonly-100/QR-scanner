package com.whoami.scanner


import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File

class MainActivity : AppCompatActivity() {
    var scanned=0
    private lateinit var previewView: PreviewView
    private lateinit var btnStart: Button
    private lateinit var btnEnd: Button
    private val beep = ToneGenerator(AudioManager.STREAM_MUSIC, 1000)
    var currenttoast: Toast?=null
    private lateinit var tvCounter: TextView
    var lastscan=0L
    val scaninterval=1000L
    val beeptime=120
    private var cameraProvider: ProcessCameraProvider? = null
    private var isScanning = false
    private val sessionResults = mutableListOf<String>()
    private val seenThisSession = mutableSetOf<String>() // dedupe
    val camerax=600
    val cameray=600
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )
    private lateinit var scanGuide: ScanGuideView
    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else toast("Camera permission needed")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )
            insets
        }
        tvCounter=findViewById(R.id.tvCounter)
        previewView = findViewById(R.id.previewView)
        btnStart = findViewById(R.id.btnstart)
        btnEnd = findViewById(R.id.btnend)
        scanGuide = findViewById(R.id.scanGuide)

        // Push the size into the view
        scanGuide.boxWidthPx = camerax
        scanGuide.boxHeightPx = cameray
        btnStart.setOnClickListener {
            sessionResults.clear()
            seenThisSession.clear()
            isScanning = true
            btnStart.isEnabled = false
            btnEnd.isEnabled = true
            if (allPermissionsGranted()) startCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }

        btnEnd.setOnClickListener {
            isScanning = false
            cameraProvider?.unbindAll()
            btnStart.isEnabled = true
            btnEnd.isEnabled = false
            saveSessionToFile()
        }
    }

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        scanned=0
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setTargetResolution(Size(camerax, cameray))
                .build()

            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                processImage(imageProxy)
            }

            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
        if (!isScanning) { imageProxy.close(); return }
        val now= SystemClock.elapsedRealtime()
        if(now-lastscan<scaninterval) { imageProxy.close(); return }
        val fullBitmap = imageProxy.toBitmap()

        // ── Map screen pixels → bitmap pixels ──────────────────────
        // The preview shows the full bitmap scaled to fit previewView.
        // We use the ratio of previewView's dimensions to bitmap's dimensions.
        val previewW = previewView.width.toFloat()
        val previewH = previewView.height.toFloat()
        val scaleX = fullBitmap.width / previewW
        val scaleY = fullBitmap.height / previewH

        // Convert cameraX/cameraY (screen px) to bitmap px
        val boxW = (camerax * scaleX).toInt()
        val boxH = (cameray * scaleY).toInt()

        val cropX = (fullBitmap.width  - boxW) / 2
        val cropY = (fullBitmap.height - boxH) / 2

        val croppedBitmap = Bitmap.createBitmap(fullBitmap, cropX, cropY, boxW, boxH)

        val input = InputImage.fromBitmap(croppedBitmap, 0)

        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                barcodes.firstOrNull()?.rawValue?.let { value ->
                    if (seenThisSession.add(value)) {
                        sessionResults.add(value)
                        runOnUiThread {
                            toast("Scanned: $value")
                            scanned++
                            tvCounter.text="Scanned: $scanned"
                            beep.startTone(ToneGenerator.TONE_PROP_BEEP, beeptime)
                        }
                    }else{
                        runOnUiThread {
                            toast("Double paket , pisah dulu")
                            beep.startTone(ToneGenerator.TONE_CDMA_REORDER, beeptime)
                        }
                    }
                    lastscan=now
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
                fullBitmap.recycle()
                croppedBitmap.recycle()
            }
    }

    private fun saveSessionToFile() {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "QRScanner")
        dir.mkdirs()
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val file = File(dir, "scan_session_$timestamp.txt")
        file.writeText(sessionResults.joinToString("\n"))
        toast("Saved ${sessionResults.size} items to ${file.name}")
        Log.d("QR", "File path: ${file.absolutePath}")
    }

    private fun toast(msg: String) {
        currenttoast?.cancel()
        currenttoast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).apply { show() }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner.close()
    }
}