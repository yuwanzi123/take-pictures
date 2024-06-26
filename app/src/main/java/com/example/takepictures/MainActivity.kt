package com.example.takepictures

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.takepictures.ui.theme.TakePicturesTheme
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TakePicturesTheme {
                CameraPreviewWithCaptureButton()
            }
        }
    }
}

@Composable
fun CameraPreviewWithCaptureButton() {
    val context = LocalContext.current
    var hasCameraPermission by remember { mutableStateOf(false) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var capturedImageUri by remember { mutableStateOf<Uri?>(null) }
    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var detectedClasses by remember { mutableStateOf(0) }
    var isLandscape by remember { mutableStateOf(context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted: Boolean ->
            hasCameraPermission = isGranted
        }
    )

    LaunchedEffect(Unit) {
        launcher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(context.resources.configuration.orientation) {
        isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    if (hasCameraPermission) {
        val cameraProvider = cameraProviderFuture.get()
        Box(modifier = Modifier.fillMaxSize()) {
            if (processedBitmap == null) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        imageCapture = ImageCapture.Builder().build()

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                ctx as LifecycleOwner,
                                cameraSelector,
                                preview,
                                imageCapture
                            )
                        } catch (exc: Exception) {
                            Log.e("CameraPreview", "Use case binding failed", exc)
                        }

                        previewView
                    },
                    modifier = if (isLandscape) {
                        Modifier
                            .size(300.dp, 400.dp)
                            .align(Alignment.Center)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f)
                            .align(Alignment.Center)
                    }
                )

                if (isLandscape) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Button(
                                onClick = {
                                    val outputOptions = ImageCapture.OutputFileOptions.Builder(
                                        context.contentResolver,
                                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                        ContentValues().apply {
                                            put(
                                                MediaStore.Images.Media.DISPLAY_NAME,
                                                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(
                                                    System.currentTimeMillis()
                                                ) + ".jpg"
                                            )
                                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                        }
                                    ).build()

                                    imageCapture?.takePicture(
                                        outputOptions,
                                        ContextCompat.getMainExecutor(context),
                                        object : ImageCapture.OnImageSavedCallback {
                                            override fun onError(exception: ImageCaptureException) {
                                                Log.e("CameraPreview", "Image capture failed: ${exception.message}", exception)
                                            }

                                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                                Log.d("CameraPreview", "Image saved successfully: ${outputFileResults.savedUri}")
                                                capturedImageUri = outputFileResults.savedUri
                                                capturedImageUri?.let {
                                                    val bitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, it)
                                                    val rotatedBitmap = rotateBitmapIfNeeded(context, it, bitmap)
                                                    val tfliteModelHandler = TFLiteModelHandler(context)
                                                    val inferenceResult = tfliteModelHandler.runInference(rotatedBitmap)
                                                    processedBitmap = inferenceResult.bitmap
                                                    detectedClasses = inferenceResult.detectedClasses
                                                }
                                            }
                                        }
                                    )
                                },
                                modifier = Modifier.padding(bottom = 16.dp)
                            ) {
                                Text("Capture Image")
                            }
                            Text(
                                text = "I detected $detectedClasses classes",
                                color = Color.Blue,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Button(
                            onClick = {
                                val outputOptions = ImageCapture.OutputFileOptions.Builder(
                                    context.contentResolver,
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                    ContentValues().apply {
                                        put(
                                            MediaStore.Images.Media.DISPLAY_NAME,
                                            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(
                                                System.currentTimeMillis()
                                            ) + ".jpg"
                                        )
                                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                    }
                                ).build()

                                imageCapture?.takePicture(
                                    outputOptions,
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageSavedCallback {
                                        override fun onError(exception: ImageCaptureException) {
                                            Log.e("CameraPreview", "Image capture failed: ${exception.message}", exception)
                                        }

                                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                            Log.d("CameraPreview", "Image saved successfully: ${outputFileResults.savedUri}")
                                            capturedImageUri = outputFileResults.savedUri
                                            capturedImageUri?.let {
                                                val bitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, it)
                                                val rotatedBitmap = rotateBitmapIfNeeded(context, it, bitmap)
                                                val tfliteModelHandler = TFLiteModelHandler(context)
                                                val inferenceResult = tfliteModelHandler.runInference(rotatedBitmap)
                                                processedBitmap = inferenceResult.bitmap
                                                detectedClasses = inferenceResult.detectedClasses
                                            }
                                        }
                                    }
                                )
                            },
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            Text("Capture Image")
                        }
                    }
                }
            } else {
                if (isLandscape) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        processedBitmap?.let { bitmap ->
                            Box(modifier = Modifier.weight(1f)) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Captured Image",
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Button(
                                onClick = {
                                    processedBitmap = null
                                },
                                modifier = Modifier.padding(bottom = 16.dp)
                            ) {
                                Text("Take New Picture")
                            }
                            Text(
                                text = "I detected $detectedClasses classes",
                                color = Color.Blue,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        processedBitmap?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Captured Image",
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Button(
                            onClick = {
                                processedBitmap = null
                            },
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            Text("Take New Picture")
                        }
                        Text(
                            text = "I detected $detectedClasses classes",
                            color = Color.Blue,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(16.dp)
                        )
                    }
                }
            }
        }
    } else {
        Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
            Text("Request Camera Permission")
        }
    }
}

fun rotateBitmapIfNeeded(context: Context, imageUri: Uri, bitmap: Bitmap): Bitmap {
    val orientationColumn = arrayOf(MediaStore.Images.Media.ORIENTATION)
    val cur = context.contentResolver.query(imageUri, orientationColumn, null, null, null)
    var orientation = 0
    if (cur != null && cur.moveToFirst()) {
        orientation = cur.getInt(cur.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION))
    }
    cur?.close()

    val matrix = Matrix()
    when (orientation) {
        90 -> matrix.postRotate(90f)
        180 -> matrix.postRotate(180f)
        270 -> matrix.postRotate(270f)
    }

    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
