package com.github.toruishihara.simple_android_rtsp_viewer

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import android.view.SurfaceHolder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.toruishihara.simple_android_rtsp_viewer.pipeline.HandLandmarkerHelper

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RtspScreen()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RtspScreen(playerViewModel: PlayerViewModel = viewModel()) {
    var surface by remember { mutableStateOf<Surface?>(null) }
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RTSP Viewer") }
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
        ) {

            TextField(
                value = playerViewModel.rtspUrl,
                onValueChange = { playerViewModel.rtspUrl = it },
                label = { Text("RTSP URL") }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row {
                Button(onClick = {
                    playerViewModel.start()
                }) {
                    Text("Start")
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(onClick = {
                    playerViewModel.stop()
                }) {
                    Text("Stop")
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(onClick = {
                    val view = surfaceView ?: return@Button
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    PixelCopy.request(view, bitmap, { result ->
                        if (result == PixelCopy.SUCCESS) {
                            playerViewModel.detectHand(bitmap)
                        }
                    }, Handler(Looper.getMainLooper()))
                }) {
                    Text("Detect Hand")
                }
            }
            Row {
                Button(onClick = {
                    playerViewModel.onvifUp()
                }) {
                    Text("ONVIF UP")
                }
            }
            Row {
                Button(onClick = {
                    playerViewModel.onvifLeft()
                }) {
                    Text("ONVIF LEFT")
                }
                Button(onClick = {
                    playerViewModel.onvifRight()
                }) {
                    Text("ONVIF RIGHT")
                }
            }
            Row {
                Button(onClick = {
                    playerViewModel.onvifDown()
                }) {
                    Text("ONVIF Down")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                factory = { context ->
                    SurfaceView(context).apply {
                        surfaceView = this
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                playerViewModel.setSurface(holder.surface)
                                surface = holder.surface
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int
                            ) {
                                playerViewModel.setSurface(holder.surface)
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                playerViewModel.setSurface(null)
                            }
                        })
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            playerViewModel.capturedBitmap?.let { bitmap ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Captured Frame",
                        modifier = Modifier.fillMaxSize()
                    )
                    HandLandmarkOverlay(
                        resultBundle = playerViewModel.detectionResult,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
fun HandLandmarkOverlay(
    resultBundle: HandLandmarkerHelper.ResultBundle?,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        val TAG = "Canvas"
        Log.d(TAG, "resultBundle.result=${resultBundle?.results}")
        resultBundle?.results?.firstOrNull()?.landmarks()?.forEach { landmarks ->
            // Draw connections
            val connections = listOf(
                // Thumb
                0 to 1, 1 to 2, 2 to 3, 3 to 4,
                // Index
                0 to 5, 5 to 6, 6 to 7, 7 to 8,
                // Middle
                0 to 9, 9 to 10, 10 to 11, 11 to 12,
                // Ring
                0 to 13, 13 to 14, 14 to 15, 15 to 16,
                // Pinky
                0 to 17, 17 to 18, 18 to 19, 19 to 20,
                // Palm/Base
                5 to 9, 9 to 13, 13 to 17
            )

            connections.forEach { (startIdx, endIdx) ->
                val start = landmarks[startIdx]
                val end = landmarks[endIdx]
                drawLine(
                    color = Color.Green,
                    start = Offset(start.x() * width, start.y() * height),
                    end = Offset(end.x() * width, end.y() * height),
                    strokeWidth = 4f
                )
            }

            // Draw landmark points
            landmarks.forEach { landmark ->
                drawCircle(
                    color = Color.Red,
                    radius = 8f,
                    center = Offset(landmark.x() * width, landmark.y() * height)
                )
            }
        }
    }
}
