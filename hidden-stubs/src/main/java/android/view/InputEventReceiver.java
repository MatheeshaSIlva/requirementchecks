package android.view;

import android.os.Looper;

/**
 * Compile-time stub of the hidden framework class. Never packaged (compileOnly).
 * Signatures match the real class from Android 10 onward.
 */
public abstract class InputEventReceiver {
    public InputEventReceiver(InputChannel inputChannel, Looper looper) {
        throw new RuntimeException("Stub!");
    }

    public void onInputEvent(InputEvent event) {
        throw new RuntimeException("Stub!");
    }

    public final void finishInputEvent(InputEvent event, boolean handled) {
        throw new RuntimeException("Stub!");
    }

    public void dispose() {
        throw new RuntimeException("Stub!");
    }
}
