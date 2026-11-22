package com.clipsync.android.shizuku;

oneway interface IClipCallback {
    void onClip(String text, boolean sensitive, String label);
    void onStatus(String message);
}
