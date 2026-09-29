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
import android.content.Intent;
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

        initializeMemoryFromIntent(getIntent().getExtras());

        // Setup the title
        TextView titleTextView = findViewById(R.id.addEditToolbarTitle);
        titleTextView.setText(getString(isAdd ? R.string.add_memory_display : R.string.edit_memory));

        // Hide advanced options until user chooses to show them
        setAdvancedOptionsVisible(false);

        // Populate initial UI data
        populateMemoryGroups();
        populateOffsets();
        populateTones();
    }

    private void initializeMemoryFromIntent(Bundle extras) {
        if (extras == null) {
            return;
        }
        isAdd = extras.getInt("requestCode") == MainActivity.REQUEST_ADD_MEMORY;
        isVhfRadio = extras.getBoolean("isVhfRadio");
        if (isAdd) {
            populateAddMemoryFields(extras);
        } else {
            loadMemoryForEditing(extras.getInt("memoryId"));
        }
    }

    private void loadMemoryForEditing(int memoryId) {
        threadPoolExecutor.execute(() -> {
            mMemory = viewModel.getAppDb().channelMemoryDao().getById(memoryId);
            populateOriginalValues();
        });
    }

    private void populateAddMemoryFields(Bundle extras) {
        populateDefaults();
        setTextIfPresent(R.id.editFrequencyTextInputEditText, extras.getString("activeFrequencyStr"));
        setDropdownTextIfPresent(R.id.editMemoryGroupTextInputEditText, extras.getString("selectedMemoryGroup"));
        setDropdownTextIfPresent(R.id.editOffsetTextView, extras.getString("offset"));
        setDropdownTextIfPresent(R.id.editToneTxTextView, extras.getString("tone"));
        setTextIfPresent(R.id.editNameTextInputEditText, extras.getString("name"));
    }

    private void setTextIfPresent(int viewId, String value) {
        if (value != null) {
            ((TextInputEditText) findViewById(viewId)).setText(value);
        }
    }

    private void setDropdownTextIfPresent(int viewId, String value) {
        if (value != null) {
            ((AutoCompleteTextView) findViewById(viewId)).setText(value, false);
        }
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

            memoryGroups.removeIf(name -> name == null || name.trim().isEmpty());

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

        runOnUiThread(() -> {
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
        });
    }

    @SuppressWarnings({"java:S1172", "javasecurity:S6384"}) // Called from XML; this only sets a fixed result code.
    public void cancelButtonClicked(View view) {
        setResult(Activity.RESULT_CANCELED);
        finish();
    }

    @SuppressWarnings({"java:S1172", "javasecurity:S6384"}) // Called from XML; result Intent is created locally.
    public void saveButtonClicked(View view) {
        MemoryForm form = readMemoryForm();
        if (!validateMemoryForm(form)) {
            return;
        }
        ChannelMemory memory = isAdd ? new ChannelMemory() : mMemory;
        applyForm(memory, form);
        saveMemory(memory);
    }

    private MemoryForm readMemoryForm() {
        MemoryForm form = new MemoryForm();
        form.nameView = findViewById(R.id.editNameTextInputEditText);
        form.frequencyView = findViewById(R.id.editFrequencyTextInputEditText);
        form.offsetView = findViewById(R.id.customOffsetTextInputEditText);
        form.name = form.nameView.getText().toString().trim();
        form.group = ((AutoCompleteTextView) findViewById(R.id.editMemoryGroupTextInputEditText)).getText().toString().trim();
        form.frequency = form.frequencyView.getText().toString().trim();
        form.offsetDirection = ((AutoCompleteTextView) findViewById(R.id.editOffsetTextView)).getText().toString().trim();
        form.txTone = ((AutoCompleteTextView) findViewById(R.id.editToneTxTextView)).getText().toString().trim();
        form.rxTone = ((AutoCompleteTextView) findViewById(R.id.editToneRxTextView)).getText().toString().trim();
        form.offsetKhz = form.offsetView.getText().toString().trim();
        form.skipDuringScan = ((Switch) findViewById(R.id.skipDuringScanSwitch)).isChecked();
        return form;
    }

    private boolean validateMemoryForm(MemoryForm form) {
        if (form.name.isEmpty()) {
            showValidationError(form.nameView, "Name this memory");
            return false;
        }
        if (!validateFrequency(form)) {
            return false;
        }
        return validateOffset(form);
    }

    private boolean validateFrequency(MemoryForm form) {
        if (form.frequency.isEmpty()) {
            showValidationError(form.frequencyView, "Enter a frequency");
            return false;
        }
        if (radioAudioService == null) {
            showValidationError(form.frequencyView, "Service not available. Please try again later.");
            return false;
        }
        form.frequency = radioAudioService.makeSafeHamFreq(form.frequency);
        if (form.frequency == null) {
            showValidationError(form.frequencyView, "Enter a frequency like 144.0000");
            return false;
        }
        return true;
    }

    private boolean validateOffset(MemoryForm form) {
        if (form.offsetKhz.isEmpty()) {
            form.offsetView.setError("Enter a custom offset");
            return false;
        }
        try {
            form.offsetKhzValue = Integer.parseInt(form.offsetKhz);
        } catch (NumberFormatException e) {
            form.offsetKhzValue = -1;
        }
        if (form.offsetKhzValue < 0 || form.offsetKhzValue > 30000) {
            form.offsetView.setError("Enter a custom offset like 600");
            return false;
        }
        return true;
    }

    private void showValidationError(TextInputEditText view, String error) {
        view.setError(error);
        view.requestFocus();
    }

    private void applyForm(ChannelMemory memory, MemoryForm form) {
        memory.name = form.name;
        memory.group = form.group;
        memory.frequency = form.frequency;
        memory.offset = offsetForDirection(form.offsetDirection);
        memory.txTone = form.txTone;
        memory.rxTone = form.rxTone;
        memory.offsetKhz = form.offsetKhzValue;
        memory.skipDuringScan = form.skipDuringScan;
    }

    private int offsetForDirection(String direction) {
        if ("Down".equals(direction)) {
            return ChannelMemory.OFFSET_DOWN;
        }
        if ("Up".equals(direction)) {
            return ChannelMemory.OFFSET_UP;
        }
        return ChannelMemory.OFFSET_NONE;
    }

    private void saveMemory(ChannelMemory memory) {
        threadPoolExecutor.execute(() -> {
            if (isAdd) {
                viewModel.getAppDb().channelMemoryDao().insertAll(memory);
            } else {
                viewModel.getAppDb().channelMemoryDao().update(memory);
            }
            Intent result = new Intent();
            if (!isAdd) {
                result.putExtra("memoryId", memory.memoryId);
            }
            setResult(Activity.RESULT_OK, result);
            finish();
        });
    }

    private static class MemoryForm {
        TextInputEditText nameView;
        TextInputEditText frequencyView;
        TextInputEditText offsetView;
        String name;
        String group;
        String frequency;
        String offsetDirection;
        String txTone;
        String rxTone;
        String offsetKhz;
        int offsetKhzValue;
        boolean skipDuringScan;
    }

    @SuppressWarnings("java:S1172") // Called from the layout's android:onClick attribute.
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
