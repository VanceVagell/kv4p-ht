package com.vagell.kv4pht.ui;

import android.app.Activity;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.room.Room;

import com.vagell.kv4pht.data.AppDatabase;
import com.vagell.kv4pht.data.ChannelMemory;

import java.nio.channels.Channel;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainViewModel extends ViewModel {
    // Database holding various user-defined app parameters
    public static AppDatabase appDb = null;
    private Activity activity = null;

    // LiveData holding the list of ChannelMemory objects
    private final MutableLiveData<List<ChannelMemory>> channelMemories = new MutableLiveData<>();

    private MainViewModelCallback callback;

    public static class MainViewModelCallback {
        public void onLoadDataDone() { };
    }

    public void setCallback(MainViewModelCallback callback) {
        this.callback = callback;
    }

    public void setActivity(Activity activity) {
        this.activity = activity;
    }

    // Example method to populate the list (replace with your actual data source logic)
    public void loadData() {
        appDb = Room.databaseBuilder(activity.getApplicationContext(),
                AppDatabase.class, "kv4pht-db").build();

        // Channel memories
        Executor executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            List<ChannelMemory> data = appDb.channelMemoryDao().getAll();
            channelMemories.postValue(data);
            if (callback != null) {
                callback.onLoadDataDone();
            }
        });
    }

    // Getter for the LiveData
    public LiveData<List<ChannelMemory>> getChannelMemories() {
        return channelMemories;
    }

    public void highlightMemory(ChannelMemory memory) {
        List<ChannelMemory> memories = channelMemories.getValue();
        if (memories == null) { return; }
        for (int i = 0; i < memories.size(); i++) {
            memories.get(i).setHighlighted(false);
        }
        if (memory != null) {
            memory.setHighlighted(true);
        }
    }

    public void deleteMemory(ChannelMemory memory) {
        Executor executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            appDb.channelMemoryDao().delete(memory);
        });
    }

    // Only displays memories that have the given group, or if group
    // is null displays all memories (no filter).
    public void filterMemories(String group) {
        setCallback(new MainViewModelCallback() {
            @Override
            public void onLoadDataDone() {
                setCallback(null);
                List<ChannelMemory> memories = channelMemories.getValue();

                if (group == null) {
                    return;
                }

                for (int i = 0; i < memories.size(); i++) {
                    if (!memories.get(i).group.equals(group)) {
                        memories.remove(i--);
                    }
                }
            }
        });
        loadData(); // Repopulate all memories, then filter (see callback above)
    }
}
