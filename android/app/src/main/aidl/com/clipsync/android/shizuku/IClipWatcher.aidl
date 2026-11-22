package com.clipsync.android.shizuku;

import com.clipsync.android.shizuku.IClipCallback;

// Runs inside Shizuku's user-service process (shell uid), which may read the clipboard in the background.
interface IClipWatcher {
    // Required by Shizuku: called when the service is unbound/destroyed.
    void destroy() = 16777114;

    // Starts watching; every clipboard change is reported to the callback.
    void start(IClipCallback callback) = 1;

    void stop() = 2;
}
