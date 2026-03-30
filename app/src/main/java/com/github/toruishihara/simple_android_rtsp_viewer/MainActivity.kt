package com.github.toruishihara.simple_android_rtsp_viewer

import android.os.Bundle
import android.view.Surface
import android.view.SurfaceView
import android.view.SurfaceHolder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel

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
                //modifier = Modifier
                //    .width(640.dp)
                //    .height(360.dp),
                factory = { context ->
                    SurfaceView(context).apply {
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
        }
    }
}