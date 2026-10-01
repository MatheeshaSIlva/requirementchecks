package dev.spike.launcherprobe;

oneway interface IGestureCallback {
    void onGesture(String kind, float x, float y);
}
