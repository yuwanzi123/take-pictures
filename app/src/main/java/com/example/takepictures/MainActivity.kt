package com.example.takepictures

import android.Manifest
import android.content.ContentValues
import android.content.Context
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
import androidx.compose.ui.Modifier
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

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted: Boolean ->
            hasCameraPermission = isGranted
        }
    )

    LaunchedEffect(Unit) {
        launcher.launch(Manifest.permission.CAMERA)
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
                    modifier = Modifier.fillMaxSize()
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
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
                                            processedBitmap = tfliteModelHandler.runInference(rotatedBitmap)
                                        }
                                    }
                                }
                            )
                        }
                    ) {
                        Text("Capture Image")
                    }
                }
            } else {
                processedBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Captured Image",
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick = {
                            processedBitmap = null
                        }
                    ) {
                        Text("Take New Picture")
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
