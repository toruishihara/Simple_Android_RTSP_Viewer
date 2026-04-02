# Simple_Android_RTSP_Viewer
Android RTSP Viewer with MediaCodec, no FFMPEG or VLC.
Implemented real-time hand detection using Google MediaPipe Hand Landmarker, processing live video frames from camera/RTSP streams and extracting hand landmarks for gesture-based interaction.
ONVIF Carema moves to the index finger direction.

Tested IP Camera : Jennov model HS4007

RTSP server : Jennov model HS4007, SD mode 640x360

h.264 MediaCodec HW decoder delay average = 12ms on Pixel 6 

Frame delay 250ms to 350ms on Pixel 6

| Screenshot 1 | Screenshot 2 |
| :---: | :---: |
| ![Alt 1](images/hand_detect1.jpg) | ![Alt 2](images/frame1.jpg) |
| With Pipeline | Frame delay measurement |
