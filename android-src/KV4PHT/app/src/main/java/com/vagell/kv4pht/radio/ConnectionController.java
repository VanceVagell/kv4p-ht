package com.vagell.kv4pht.radio;

import android.os.Handler;

final class ConnectionController {
    private final Handler handler;
    private final long periodMs;
    private final Runnable reconcileConnections;
    private boolean running = false;

    private final Runnable periodicRunnable = new Runnable() {
        @Override
        // False positive: reconciliation can call stop() and flip running to false.
        @SuppressWarnings("java:S2589")
        public void run() {
            if (!running) {
                return;
            }
            reconcileConnections.run();
            if (running) {
                handler.postDelayed(this, periodMs);
            }
        }
    };

    ConnectionController(
        Handler handler,
        long periodMs,
        Runnable reconcileConnections
    ) {
        this.handler = handler;
        this.periodMs = periodMs;
        this.reconcileConnections = reconcileConnections;
    }

    void start() {
        stop();
        running = true;
        handler.post(periodicRunnable);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(periodicRunnable);
    }
}
