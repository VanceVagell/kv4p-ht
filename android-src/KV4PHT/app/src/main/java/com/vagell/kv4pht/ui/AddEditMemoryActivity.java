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
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.textfield.TextInputEditText;
import com.vagell.kv4pht.R;
import com.vagell.kv4pht.data.ChannelMemory;
import com.vagell.kv4pht.radio.RadioAudioService;
import com.vagell.kv4pht.radio.RadioServiceConnector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class AddEditMemoryActivity extends AppCompatActivity {
    private boolean isAdd = true; // false means we're editing a memory, not adding
    private boolean isVhfRadio = true; // false means UHF radio
    private final ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    private ChannelMemory mMemory;
    private MainViewModel viewModel;
    private RadioServiceConnector serviceConnector;
    private RadioAudioService radioAudioService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);
        super.onCreate(savedInstanceState);
        serviceConnector = new RadioServiceConnector(this);
        setContentView(R.layout.activity_add_edit_memory);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            isAdd = (extras.getInt("requestCode") == MainActivity.REQUEST_ADD_MEMORY);
            isVhfRadio = (extras.getBoolean("isVhfRadio"));
            int mMemoryId;
            if (!isAdd) { // Edit
                mMemoryId = extras.getInt("memoryId");
                threadPoolExecutor.execute(() -> {
                    mMemory = viewModel.getAppDb().channelMemoryDao().getById(mMemoryId);
                    populateOriginalValues();
                });
            } else { // Add
                populateDefaults();

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

                String offset = extras.getString("offset");
                if (offset != null) {
                    AutoCompleteTextView editOffset = findViewById(R.id.editOffsetTextView);
                    editOffset.setText(offset, false);
                }

                String tone = extras.getString("tone");
                if (tone != null) {
                    AutoCompleteTextView editTone = findViewById(R.id.editToneTxTextView);
                    editTone.setText(tone, false);
                }

                String name = extras.getString("name");
                if (name != null) {
                    TextInputEditText editNameTextInputEditText = findViewById(R.id.editNameTextInputEditText);
                    editNameTextInputEditText.setText(name);
                }

            }
        }

        // Setup the title
        TextView titleTextView = findViewById(R.id.addEditToolbarTitle);
        titleTextView.setText(isAdd ? getString(R.string.add_memory_display) : getString(R.string.edit_memory));

        // Hide advanced options until user chooses to show them
        setAdvancedOptionsVisible(false);

        // Populate initial UI data
        populateMemoryGroups();
        populateOffsets();
        populateTones();
    }

    @Override
    protected void onStart() {
        super.onStart();
        serviceConnector.bind(rs -> this.radioAudioService = rs);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Put the cursor in the name field by default
        EditText nameEditText = findViewById(R.id.editNameTextInputEditText);
        nameEditText.requestFocus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        threadPoolExecutor.shutdownNow();
        serviceConnector.unbind();
    }

    private void populateMemoryGroups() {
        final Activity activity = this;
        threadPoolExecutor.execute(() -> {
            List<String> memoryGroups = viewModel.getAppDb().channelMemoryDao().getGroups();

            // Remove any blank memory groups from the list (shouldn't have been saved, ideally).
            for (int i = 0; i < memoryGroups.size(); i++) {
                String name = memoryGroups.get(i);
                if (name == null || name.trim().length() == 0) {
                    memoryGroups.remove(i);
                    i--;
                }
            }

            activity.runOnUiThread(() -> {
                AutoCompleteTextView editMemoryGroupTextView = findViewById(R.id.editMemoryGroupTextInputEditText);
                ArrayAdapter arrayAdapter = new ArrayAdapter(activity, R.layout.dropdown_item, memoryGroups);
                editMemoryGroupTextView.setAdapter(arrayAdapter);
            });
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
        AutoCompleteTextView editToneTxTextView = findViewById(R.id.editToneTxTextView);
        ArrayAdapter arrayAdapter1 = new ArrayAdapter(this, R.layout.dropdown_item, ToneHelper.VALID_TONE_STRINGS);
        editToneTxTextView.setAdapter(arrayAdapter1);

        AutoCompleteTextView editToneRxTextView = findViewById(R.id.editToneRxTextView);
        ArrayAdapter arrayAdapter2 = new ArrayAdapter(this, R.layout.dropdown_item, ToneHelper.VALID_TONE_STRINGS);
        editToneRxTextView.setAdapter(arrayAdapter2);
    }

    private void populateDefaults() {
        TextInputEditText customOffsetTextInputEditText = findViewById(R.id.customOffsetTextInputEditText);

        if (isVhfRadio) {
            customOffsetTextInputEditText.setText("600");
        } else {
            customOffsetTextInputEditText.setText("5000");
        }
    }

    private void populateOriginalValues() {
        if (isAdd) {
            return;
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Name
                TextInputEditText editNameTextInputEditText = findViewById(R.id.editNameTextInputEditText);
                editNameTextInputEditText.setText(mMemory.name);

                // Group
                AutoCompleteTextView editMemoryGroupTextInputEditText = findViewById(R.id.editMemoryGroupTextInputEditText);
                editMemoryGroupTextInputEditText.setText(mMemory.group, false);

                // Frequency
                TextInputEditText editFrequencyTextInputEditText = findViewById(R.id.editFrequencyTextInputEditText);
                editFrequencyTextInputEditText.setText(mMemory.frequency);

                // Offset direction
                AutoCompleteTextView editOffsetTextView = findViewById(R.id.editOffsetTextView);
                if (mMemory.offset == ChannelMemory.OFFSET_NONE) {
                    editOffsetTextView.setText("None", false);
                } else if (mMemory.offset == ChannelMemory.OFFSET_DOWN) {
                    editOffsetTextView.setText("Down", false);
                } else if (mMemory.offset == ChannelMemory.OFFSET_UP) {
                    editOffsetTextView.setText("Up", false);
                }

                // Tone (TX)
                AutoCompleteTextView editToneTxTextView = findViewById(R.id.editToneTxTextView);
                editToneTxTextView.setText(mMemory.txTone, false);

                // Tone (RX)
                AutoCompleteTextView editToneRxTextView = findViewById(R.id.editToneRxTextView);
                editToneRxTextView.setText(mMemory.rxTone, false);

                // Custom offset (kHz)
                TextInputEditText customOffsetTextInputEditText = findViewById(R.id.customOffsetTextInputEditText);
                customOffsetTextInputEditText.setText("" + mMemory.offsetKhz);

                // Skip during scan
                Switch skipDuringScanSwitch = findViewById(R.id.skipDuringScanSwitch);
                skipDuringScanSwitch.setChecked(mMemory.skipDuringScan);
            }
        });
    }

    public void cancelButtonClicked(View view) {
        setResult(Activity.RESULT_CANCELED, getIntent());
        finish();
    }

    public void saveButtonClicked(View view) {
        // Name
        TextInputEditText editNameTextInputEditText = findViewById(R.id.editNameTextInputEditText);
        String name = editNameTextInputEditText.getText().toString().trim();

        // Group
        AutoCompleteTextView editMemoryGroupTextInputEditText = findViewById(R.id.editMemoryGroupTextInputEditText);
        String group = editMemoryGroupTextInputEditText.getText().toString().trim();

        // Frequency
        TextInputEditText editFrequencyTextInputEditText = findViewById(R.id.editFrequencyTextInputEditText);
        String frequency = editFrequencyTextInputEditText.getText().toString().trim();

        // Offset direction
        AutoCompleteTextView editOffsetTextView = findViewById(R.id.editOffsetTextView);
        String offset = editOffsetTextView.getText().toString().trim();

        // Tone (TX)
        AutoCompleteTextView editToneTxTextView = findViewById(R.id.editToneTxTextView);
        String txTone = editToneTxTextView.getText().toString().trim();

        // Tone (RX)
        AutoCompleteTextView editToneRxTextView = findViewById(R.id.editToneRxTextView);
        String rxTone = editToneRxTextView.getText().toString().trim();

        // Custom offset (kHz)
        TextInputEditText customOffsetTextInputEditText = findViewById(R.id.customOffsetTextInputEditText);
        String offsetKhz = customOffsetTextInputEditText.getText().toString().trim();

        // Skip during scan
        Switch skipDuringScanSwitch = findViewById(R.id.skipDuringScanSwitch);
        boolean skipDuringScan = skipDuringScanSwitch.isChecked();

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
            if (radioAudioService == null) {
                editFrequencyTextInputEditText.setError("Service not available. Please try again later.");
                editFrequencyTextInputEditText.requestFocus();
                return;
            }
            String formattedFrequency = radioAudioService.makeSafeHamFreq(frequency);
            if (formattedFrequency == null) {
                editFrequencyTextInputEditText.setError("Enter a frequency like 144.0000");
                editFrequencyTextInputEditText.requestFocus();
                return;
            } else {
                frequency = formattedFrequency;
            }
        }

        int offsetKhzInt = -1;
        if (offsetKhz.length() == 0) {
            customOffsetTextInputEditText.setError("Enter a custom offset");
            return;
        } else {
            try {
                offsetKhzInt = Integer.parseInt(offsetKhz);
                if (offsetKhzInt > 30000 || offsetKhzInt < 0) { // Hard to say what a legit offset would look like, but it has to be smaller than 30MHz (width of 70cm band).
                    customOffsetTextInputEditText.setError("Enter a custom offset like 600");
                    return;
                }
            } catch (NumberFormatException nfe) {
                customOffsetTextInputEditText.setError("Enter a custom offset like 600");
                return;
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
        memory.txTone = txTone;
        memory.rxTone = rxTone;
        memory.offsetKhz = offsetKhzInt;
        memory.skipDuringScan = skipDuringScan;

        final ChannelMemory finalMemory = memory;
        threadPoolExecutor.execute(() -> {
            if (isAdd) {
                viewModel.getAppDb().channelMemoryDao().insertAll(finalMemory);
            } else {
                viewModel.getAppDb().channelMemoryDao().update(finalMemory);
            }
            setResult(Activity.RESULT_OK, getIntent());
            finish();
        });
    }

    public void advancedMemoryOptionsButtonClicked(View view) {
        setAdvancedOptionsVisible(true);
    }

    private void setAdvancedOptionsVisible(boolean visible) {
        findViewById(R.id.advancedMemoryOptionsButton).setVisibility(visible ? View.GONE : View.VISIBLE);
        findViewById(R.id.skipDuringScanSwitch).setVisibility(visible ? View.VISIBLE: View.GONE);
        findViewById(R.id.customOffsetTextInputLayout).setVisibility(visible ? View.VISIBLE: View.GONE);
        findViewById(R.id.editToneRxTextInputLayout).setVisibility(visible ? View.VISIBLE: View.GONE);
    }
}