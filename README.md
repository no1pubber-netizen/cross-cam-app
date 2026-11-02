# CrossCam

A simple live viewer for the Crosstour CT7000 (iCatch-based) action camera, for Android phones that
can no longer install iSmart DV.

What it does:
1. Logs in to the camera over PTP/IP (port 15740), the same as iSmart DV does.
2. Plays the camera's live RTSP stream full screen with libVLC:
   `rtsp://192.168.1.1/mjpg` (lower case; found by Aaron S. Jackson, aaronsplace.co.uk)

## Install
Open the latest release on your phone:
https://github.com/no1pubber-netizen/cross-cam-app/releases/latest
and tap the `.apk` file. Allow "install unknown apps" for your browser if asked.

## Use
1. Turn on the camera's Wi-Fi and join the **ActionCam** network (password `1234567890`).
2. Open CrossCam and tap **Connect**. The log shows each step.
3. When the picture appears, tap **Hide**, then start Android's screen recorder.
   Tap the picture to bring the controls back.

If the picture doesn't appear, tick **RTSP over TCP** and tap Connect again.
