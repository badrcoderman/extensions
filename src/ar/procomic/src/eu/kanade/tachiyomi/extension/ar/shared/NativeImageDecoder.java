package eu.kanade.tachiyomi.extension.ar.shared;

import android.graphics.Bitmap;

/** Java bridge preserves the pinned upstream decoder fallbacks without Kotlin metadata coupling. */
public final class NativeImageDecoder {
    private NativeImageDecoder() {}
    public static Bitmap decode(byte[] bytes) {
        return keiyoushi.a.a.a.a(bytes);
    }
}
