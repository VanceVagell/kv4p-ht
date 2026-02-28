package com.vagell.kv4pht.radio;

import android.os.Handler;
import android.os.Looper;
import java.util.function.BooleanSupplier;

final class ConnectionController {
    private final Handler handler;
    private final long periodMs;
    private final BooleanSupplier isConnectionReady;
    private final Runnable attemptConnect;
    private boolean running = false;
    private boolean attemptActive = false;

    private final Runnable periodicRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            if (!isConnectionReady.getAsBoolean() && !attemptActive) {
                attemptActive = true;
                attemptConnect.run();
                if (!running) {
                    return;
                }
            }
            handler.postDelayed(this, periodMs);
        }
    };

    ConnectionController(
        Handler handler,
        long periodMs,
        BooleanSupplier isConnectionReady,
        Runnable attemptConnect
    ) {
        this.handler = handler;
        this.periodMs = periodMs;
        this.isConnectionReady = isConnectionReady;
        this.attemptConnect = attemptConnect;
    }

    void start() {
        stop();
        running = true;
        handler.postDelayed(periodicRunnable, periodMs);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(periodicRunnable);
        markAttemptFinished();
    }

    void markAttemptFinished() {
        if (Looper.myLooper() == handler.getLooper()) {
            attemptActive = false;
        } else {
            handler.post(() -> attemptActive = false);
        }
    }
}
