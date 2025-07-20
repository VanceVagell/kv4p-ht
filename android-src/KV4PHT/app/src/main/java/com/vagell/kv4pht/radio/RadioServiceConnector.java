package com.vagell.kv4pht.radio;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class RadioServiceConnector {
    private RadioAudioService service;
    private boolean bound = false;
    private final Context context;
    private final List<Consumer<RadioAudioService>> callbacks = new ArrayList<>();

    public RadioServiceConnector(Context context) {
        this.context = context.getApplicationContext();
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            RadioAudioService.RadioBinder radioBinder = (RadioAudioService.RadioBinder) binder;
            service = radioBinder.getService();
            bound = true;
            for (Consumer<RadioAudioService> callback : callbacks) {
                callback.accept(service);
            }
            callbacks.clear();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    public void bind(Consumer<RadioAudioService> onConnected) {
        bind(new Intent(context, RadioAudioService.class), onConnected);
    }

    public void bind(Intent intent, Consumer<RadioAudioService> onConnected) {
        if (bound && service != null) {
            onConnected.accept(service);
        } else {
            callbacks.add(onConnected);
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        }
    }

    public void unbind() {
        if (bound) {
            context.unbindService(connection);
            bound = false;
        }
    }
}

