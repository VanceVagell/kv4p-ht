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
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.vagell.kv4pht.R;
import com.vagell.kv4pht.data.ChannelMemory;
import com.vagell.kv4pht.radio.RadioAudioService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class AddEditMemoryActivity extends AppCompatActivity {
    private boolean isAdd = true; // false means we're editing a memory, not adding
    private List<String> mTones;
    private ThreadPoolExecutor threadPoolExecutor = null;
    private int mMemoryId;
    private ChannelMemory mMemory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_edit_memory);

        threadPoolExecutor = new ThreadPoolExecutor(2,
                2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());

        // Set up the list of supported CTCSS tones.
        mTones = new ArrayList<>();
        mTones.add("None");
        mTones.add("67");
        mTones.add("71.9");
        mTones.add("74.4");
        mTones.add("77");
        mTones.add("79.7");
        mTones.add("82.5");
        mTones.add("85.4");
        mTones.add("88.5");
        mTones.add("91.5");
        mTones.add("94.8");
        mTones.add("97.4");
        mTones.add("100");
        mTones.add("103.5");
        mTones.add("107.2");
        mTones.add("110.9");
        mTones.add("114.8");
        mTones.add("118.8");
        mTones.add("123");
        mTones.add("127.3");
        mTones.add("131.8");
        mTones.add("136.5");
        mTones.add("141.3");
        mTones.add("146.2");
        mTones.add("151.4");
        mTones.add("156.7");
        mTones.add("162.2");
        mTones.add("167.9");
        mTones.add("173.8");
        mTones.add("179.9");
        mTones.add("186.2");
        mTones.add("192.8");
        mTones.add("203.5");
        mTones.add("210.7");
        mTones.add("218.1");
        mTones.add("225.7");
        mTones.add("233.6");
        mTones.add("241.8");
        mTones.add("250.3");

        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            isAdd = (extras.getInt("requestCode") == MainActivity.REQUEST_ADD_MEMORY);
            if (!isAdd) { // Edit
                mMemoryId = extras.getInt("memoryId");
                threadPoolExecutor.execute(new Runnable() {
                       @Override
                       public void run() {
                           mMemory = MainViewModel.appDb.channelMemoryDao().getById(mMemoryId);
                           populateOriginalValues();
                       }
                });
            } else { // Add
                mMemoryId = -1; // This ID is never used, just to help with debugging.

                String activeFrequencyStr = extras.getString("activeFrequencyStr");
                if (activeFrequencyStr != null) {
                    TextInputEditText editFrequencyTextInputEditText = findViewById(R.id.editFrequencyTextInputEditText);
                    editFrequencyTextInputEditText.setText(activeFrequencyStr);
                }

                String selectedMemoryGroup = extras.getString("selectedMemoryGroup");
                if (selectedMemoryGroup != null) {
                    AutoCompleteTextView editMemoryGroupTextInputEditText = findViewById(R.id.editMemoryGroupTextInputEditText);
                    editMemoryGroupTextInputEditText.setText(selectedMemoryGroup, false);
                }
            }
        }

        // Setup the title
        TextView titleTextView = findViewById(R.id.addEditToolbarTitle);
        titleTextView.setText(isAdd ? "Add memory" : "Edit memory");

        // Populate initial UI data
        populateMemoryGroups();
        populateOffsets();
        populateTones();
    }

    @Override
    protected void onResume() {
        super.onResume();

        threadPoolExecutor = new ThreadPoolExecutor(2,
                2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());

        // Put the cursor in the name field by default
        EditText nameEditText = findViewById(R.id.editNameTextInputEditText);
        nameEditText.requestFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();

        threadPoolExecutor.shutdownNow();
        threadPoolExecutor = null;
    }

    private void populateMemoryGroups() {
        final Activity activity = this;
        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                List<String> memoryGroups = MainViewModel.appDb.channelMemoryDao().getGroups();

                // Remove any blank memory groups from the list (shouldn't have been saved, ideally).
                for (int i = 0; i < memoryGroups.size(); i++) {
                    String name = memoryGroups.get(i);
                    if (name == null || name.trim().length() == 0) {
                        memoryGroups.remove(i);
                        i--;
                    }
                }

                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        AutoCompleteTextView editMemoryGroupTextView = findViewById(R.id.editMemoryGroupTextInputEditText);
                        ArrayAdapter arrayAdapter = new ArrayAdapter(activity, R.layout.dropdown_item, memoryGroups);
                        editMemoryGroupTextView.setAdapter(arrayAdapter);
                    }
                });
            }
        });
    }

    private void populateOffsets() {
        AutoCompleteTextView editOffsetTextView = findViewById(R.id.editOffsetTextView);

        List<String> offsets = new ArrayList<String>();
        offsets.add("None");
        offsets.add("Down");
        offsets.add("Up");

        ArrayAdapter arrayAdapter = new ArrayAdapter(this, R.layout.dropdown_item, offsets);
        editOffsetTextView.setAdapter(arrayAdapter);
    }

    private void populateTones() {
        AutoCompleteTextView editToneTextView = findViewById(R.id.editToneTextView);
        ArrayAdapter arrayAdapter = new ArrayAdapter(this, R.layout.dropdown_item, mTones);
        editToneTextView.setAdapter(arrayAdapter);
    }

    private void populateOriginalValues() {
        if (isAdd) {
            return;
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextInputEditText editNameTextInputEditText = findViewById(R.id.editNameTextInputEditText);
                editNameTextInputEditText.setText(mMemory.name);

                AutoCompleteTextView editMemoryGroupTextInputEditText = findViewById(R.id.editMemoryGroupTextInputEditText);
                editMemoryGroupTextInputEditText.setText(mMemory.group, false);

                TextInputEditText editFrequencyTextInputEditText = findViewById(R.id.editFrequencyTextInputEditText);
                editFrequencyTextInputEditText.setText(mMemory.frequency);

                AutoCompleteTextView editOffsetTextView = findViewById(R.id.editOffsetTextView);
                if (mMemory.offset == ChannelMemory.OFFSET_NONE) {
                    editOffsetTextView.setText("None", false);
                } else if (mMemory.offset == ChannelMemory.OFFSET_DOWN) {
                    editOffsetTextView.setText("Down", false);
                } else if (mMemory.offset == ChannelMemory.OFFSET_UP) {
                    editOffsetTextView.setText("Up", false);
                }

                AutoCompleteTextView editToneTextView = findViewById(R.id.editToneTextView);
                editToneTextView.setText(mMemory.tone, false);
            }
        });
    }

    public void cancelButtonClicked(View view) {
        setResult(Activity.RESULT_CANCELED, getIntent());
        finish();
    }

    public void saveButtonClicked(View view) {
        TextInputEditText editNameTextInputEditText = findViewById(R.id.editNameTextInputEditText);
        String name = editNameTextInputEditText.getText().toString().trim();

        AutoCompleteTextView editMemoryGroupTextInputEditText = findViewById(R.id.editMemoryGroupTextInputEditText);
        String group = editMemoryGroupTextInputEditText.getText().toString().trim();

        TextInputEditText editFrequencyTextInputEditText = findViewById(R.id.editFrequencyTextInputEditText);
        String frequency = editFrequencyTextInputEditText.getText().toString().trim();

        AutoCompleteTextView editOffsetTextView = findViewById(R.id.editOffsetTextView);
        String offset = editOffsetTextView.getText().toString().trim();

        AutoCompleteTextView editToneTextView = findViewById(R.id.editToneTextView);
        String tone = editToneTextView.getText().toString().trim();

        // Validate form fields
        if (name.length() == 0) {
            editNameTextInputEditText.setError("Name this memory");
            editNameTextInputEditText.requestFocus();
            return;
        }

        if (frequency.length() == 0) {
            editFrequencyTextInputEditText.setError("Enter a frequency");
            editFrequencyTextInputEditText.requestFocus();
            return;
        } else {
            String formattedFrequency = RadioAudioService.makeSafe2MFreq(frequency);
            if (formattedFrequency == null) {
                editFrequencyTextInputEditText.setError("Enter a frequency like 144.0000");
                editFrequencyTextInputEditText.requestFocus();
                return;
            } else {
                frequency = formattedFrequency;
            }
        }

        ChannelMemory memory = null;
        if (isAdd) {
            memory = new ChannelMemory();
        } else {
            memory = mMemory;
        }

        memory.name = name;
        memory.group = group;
        memory.frequency = frequency;
        if (offset.equals("Down")) {
            memory.offset = ChannelMemory.OFFSET_DOWN;
        } else if (offset.equals("Up")) {
            memory.offset = ChannelMemory.OFFSET_UP;
        } else {
            memory.offset = ChannelMemory.OFFSET_NONE;
        }
        memory.tone = tone;

        final ChannelMemory finalMemory = memory;
        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (isAdd) {
                    MainViewModel.appDb.channelMemoryDao().insertAll(finalMemory);
                } else {
                    MainViewModel.appDb.channelMemoryDao().update(finalMemory);
                }
                setResult(Activity.RESULT_OK, getIntent());
                finish();
            }
        });
    }
}