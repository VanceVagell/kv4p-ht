/*
kv4p HT (see http://kv4p.com)
Copyright (C) 2024 Vance Vagell

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.vagell.kv4pht.ui;

import android.app.Activity;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.room.Room;

import com.vagell.kv4pht.data.APRSMessage;
import com.vagell.kv4pht.data.AppDatabase;
import com.vagell.kv4pht.data.ChannelMemory;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainViewModel extends ViewModel {
    // Database holding various user-defined app parameters
    public static AppDatabase appDb = null;
    private Activity activity = null;

    // LiveData holding the list of ChannelMemory objects
    private final MutableLiveData<List<ChannelMemory>> channelMemories = new MutableLiveData<>();

    // LiveData holding the list of APRSMessage objects
    private final MutableLiveData<List<APRSMessage>> aprsMessages = new MutableLiveData<>();

    private MainViewModelCallback callback;

    public static class MainViewModelCallback {
        public void onLoadDataDone() { };
        public void onDeleteMemoryDone() { };
    }

    public void setCallback(MainViewModelCallback callback) {
        this.callback = callback;
    }

    public void setActivity(Activity activity) {
        this.activity = activity;
    }

    public void loadData() {
        if (appDb == null) {
            appDb = AppDatabase.getInstance(activity.getApplicationContext());
        }

        Executor executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            List<ChannelMemory> memoryData = appDb.channelMemoryDao().getAll();
            channelMemories.postValue(memoryData);

            List<APRSMessage> aprsData = appDb.aprsMessageDao().getAll();
            aprsMessages.postValue(aprsData);

            if (callback != null) {
                callback.onLoadDataDone();
            }
        });
    }

    public LiveData<List<ChannelMemory>> getChannelMemories() {
        return channelMemories;
    }

    public LiveData<List<APRSMessage>> getAPRSMessages() {
        return aprsMessages;
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
            if (callback != null) {
                callback.onDeleteMemoryDone();
            }
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
