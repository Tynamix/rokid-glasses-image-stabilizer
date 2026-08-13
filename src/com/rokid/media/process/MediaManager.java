package com.rokid.media.process;

import android.util.Log;
import java.lang.ref.WeakReference;

/** Minimal Java bridge matching the Rokid MediaManager JNI class. */
public final class MediaManager {
    private static final String TAG = "RokidArcsoft";
    private long mNativeContext;
    private long mNativeHandle;
    private Listener listener;

    public interface Listener {
        void onProgress(int percent);
        void onComplete();
        void onError(int code);
    }

    private native int nativeInit(WeakReference<MediaManager> self, String path1, String path2, int type);
    private native int nativeRelease();
    private native int nativeSetGlassDeviceType(int type);
    private native int nativeSetGlassVersion(int version);
    private native int nativeSetVideoStabilizer(boolean enabled);
    private native int nativeVideoStart(String video, String output, String sensor, int process);
    private native int nativeVideoStop();

    static {
        System.loadLibrary("media_process");
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public int start(String video, String output, String sensor) {
        int result = nativeInit(new WeakReference<>(this), video, video, 2);
        if (result != 0) return result;
        nativeSetGlassDeviceType(3); // ROKID_AI_GLASSES value + 1, as in the app
        nativeSetGlassVersion(0);
        nativeSetVideoStabilizer(true);
        return nativeVideoStart(video, output, sensor, 3); // ARC_ALL
    }

    public void stopAndRelease() {
        try {
            nativeVideoStop();
        } finally {
            nativeRelease();
        }
    }

    @SuppressWarnings("unchecked")
    private static void postEventFromNative(Object object, int what, int arg1, int arg2, Object payload) {
        MediaManager manager = null;
        if (object instanceof WeakReference) {
            manager = ((WeakReference<MediaManager>) object).get();
        }
        if (manager == null || manager.listener == null) return;
        Log.d(TAG, "native event=" + what + " value=" + arg1);
        if (what == 3) {
            manager.listener.onProgress(Math.max(0, Math.min(100, arg1)));
        } else if (what == 2) {
            manager.listener.onComplete();
        } else if (what == 1) {
            manager.listener.onError(arg1);
        }
    }
}
