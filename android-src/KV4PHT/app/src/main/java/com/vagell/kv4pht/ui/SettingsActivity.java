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
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.vagell.kv4pht.R;
import com.vagell.kv4pht.data.AppSetting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class SettingsActivity extends AppCompatActivity {
    private final ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    private MainViewModel viewModel = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);
        setContentView(R.layout.activity_settings);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        populateOriginalValues(this::attachListeners);
        populateBandwidths();
        populateMinFrequencies();
        populateMaxFrequencies();
        populateMicGainOptions();
        populateAprsOptions();
        populateRadioOptions();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        threadPoolExecutor.shutdownNow();
    }

    private void populateBandwidths() {
        AutoCompleteTextView bandwidthTextView = findViewById(R.id.bandwidthTextView);
        List<String> bandwidths = new ArrayList<>();
        bandwidths.add(getResources().getString(R.string.wide));
        bandwidths.add(getResources().getString(R.string.narrow));
        ArrayAdapter<String> arrayAdapter = new ArrayAdapter<>(this, R.layout.dropdown_item, bandwidths);
        bandwidthTextView.setAdapter(arrayAdapter);
    }

    private void populateMinFrequencies() {
        AutoCompleteTextView min2mFreqTextView = findViewById(R.id.min2mFreqTextView);
        List<String> min2mFreqs = new ArrayList<String>();
        min2mFreqs.add("144MHz");
        ArrayAdapter<String> arrayAdapter1 = new ArrayAdapter<>(this, R.layout.dropdown_item, min2mFreqs);
        min2mFreqTextView.setAdapter(arrayAdapter1);
        AutoCompleteTextView min70cmFreqTextView = findViewById(R.id.min70cmFreqTextView);
        List<String> min70cmFreqs = new ArrayList<String>();
        min70cmFreqs.add("420MHz");
        min70cmFreqs.add("430MHz");
        ArrayAdapter<String> arrayAdapter2 = new ArrayAdapter<>(this, R.layout.dropdown_item, min70cmFreqs);
        min70cmFreqTextView.setAdapter(arrayAdapter2);
    }

    private void populateMaxFrequencies() {
        AutoCompleteTextView max2mFreqTextView = findViewById(R.id.max2mFreqTextView);
        List<String> max2mFreqs = new ArrayList<String>();
        max2mFreqs.add("148MHz");
        max2mFreqs.add("146MHz");
        ArrayAdapter<String> arrayAdapter1 = new ArrayAdapter<>(this, R.layout.dropdown_item, max2mFreqs);
        max2mFreqTextView.setAdapter(arrayAdapter1);
        AutoCompleteTextView max70cmFreqTextView = findViewById(R.id.max70cmFreqTextView);
        List<String> max70cmFreqs = new ArrayList<String>();
        max70cmFreqs.add("450MHz");
        max70cmFreqs.add("440MHz");
        ArrayAdapter<String> arrayAdapter2 = new ArrayAdapter<>(this, R.layout.dropdown_item, max70cmFreqs);
        max70cmFreqTextView.setAdapter(arrayAdapter2);
    }

    private void populateMicGainOptions() {
        AutoCompleteTextView micGainBoostTextView = findViewById(R.id.micGainBoostTextView);
        List<String> micGainOptions = new ArrayList<String>();
        micGainOptions.add("None");
        micGainOptions.add("Low");
        micGainOptions.add("Med");
        micGainOptions.add("High");
        ArrayAdapter<String> arrayAdapter = new ArrayAdapter<>(this, R.layout.dropdown_item, micGainOptions);
        micGainBoostTextView.setAdapter(arrayAdapter);
    }

    private void populateAprsOptions() {
        AutoCompleteTextView aprsPositionAccuracyTextView = findViewById(R.id.aprsPositionAccuracyTextView);
        List<String> positionAccuracyOptions = new ArrayList<>();
        positionAccuracyOptions.add("Exact");
        positionAccuracyOptions.add("Approx");
        ArrayAdapter<String> arrayAdapter = new ArrayAdapter<>(this, R.layout.dropdown_item, positionAccuracyOptions);
        aprsPositionAccuracyTextView.setAdapter(arrayAdapter);
    }

    private void populateRadioOptions() {
        AutoCompleteTextView rfPowerTextView = findViewById(R.id.rfPowerTextView);
        String[] rfPowerOptions = getResources().getStringArray(R.array.rf_power_options);
        ArrayAdapter<String> arrayAdapter = new ArrayAdapter<>(this, R.layout.dropdown_item, rfPowerOptions);
        rfPowerTextView.setAdapter(arrayAdapter);
        rfPowerTextView.setThreshold(1);
        // Set default text to first item
        rfPowerTextView.setText(rfPowerOptions[0], false);
    }

    private void setTextIfPresent(Map<String, String> settings, String key, int viewId) {
        if (settings.containsKey(key)) {
            TextInputEditText view = findViewById(viewId);
            view.setText(settings.get(key));
        }
    }

    private void setSwitchIfPresent(Map<String, String> settings, String key, int viewId) {
        if (settings.containsKey(key)) {
            Switch view = findViewById(viewId);
            view.setChecked(Boolean.parseBoolean(settings.get(key)));
        }
    }

    private void setSliderIfPresent(Map<String, String> settings, String key, int viewId) {
        if (settings.containsKey(key)) {
            Slider view = findViewById(viewId);
            view.setValue(Float.parseFloat(settings.get(key)));
        }
    }

    private void setDropdownIfPresent(Map<String, String> settings, String key, int viewId) {
        setDropdownIfPresent(settings, key, viewId, "");
    }

    private void setDropdownIfPresent(Map<String, String> settings, String key, int viewId, String suffix) {
        if (settings.containsKey(key)) {
            AutoCompleteTextView view = findViewById(viewId);
            view.setText(settings.get(key) + suffix, false);
        }
    }

    private void populateOriginalValues(Runnable callback) {
        threadPoolExecutor.execute(() -> {
            final Map<String, String> settings = viewModel.getAppDb().appSettingDao().getAll().stream()
                .collect(Collectors.toMap(AppSetting::getName, AppSetting::getValue));
            runOnUiThread(() -> {
                setTextIfPresent(settings, "callsign", R.id.callsignTextInputEditText);
                setSliderIfPresent(settings, "squelch", R.id.squelchSlider);
                setSwitchIfPresent(settings, "emphasis", R.id.emphasisSwitch);
                setSwitchIfPresent(settings, "highpass", R.id.highpassSwitch);
                setSwitchIfPresent(settings, "lowpass", R.id.lowpassSwitch);
                setSwitchIfPresent(settings, "stickyPTT", R.id.stickyPTTSwitch);
                setSwitchIfPresent(settings, "disableAnimations", R.id.noAnimationsSwitch);
                setSwitchIfPresent(settings, "aprsBeaconPosition", R.id.aprsPositionSwitch);
                setDropdownIfPresent(settings, "aprsPositionAccuracy", R.id.aprsPositionAccuracyTextView);
                setDropdownIfPresent(settings, "bandwidth", R.id.bandwidthTextView);
                setDropdownIfPresent(settings, "min2mTxFreq", R.id.min2mFreqTextView, "MHz");
                setDropdownIfPresent(settings, "max2mTxFreq", R.id.max2mFreqTextView, "MHz");
                setDropdownIfPresent(settings, "min70cmTxFreq", R.id.min70cmFreqTextView, "MHz");
                setDropdownIfPresent(settings, "max70cmTxFreq", R.id.max70cmFreqTextView, "MHz");
                setDropdownIfPresent(settings, "micGainBoost", R.id.micGainBoostTextView);
                setDropdownIfPresent(settings, "rfPower", R.id.rfPowerTextView);
                callback.run();
            });
        });
    }

    public void closedCaptionsButtonClicked(View view) {
        try {
            startActivity(new Intent("com.android.settings.action.live_caption"));
        } catch (ActivityNotFoundException anfe) {
            CharSequence snackbarMsg = "This phone model doesn't support closed captions";
            Snackbar ccSnackbar = Snackbar.make(findViewById(R.id.settingsTopLevelView), snackbarMsg, Snackbar.LENGTH_LONG)
                    .setBackgroundTint(Color.rgb(140, 20, 0)).setActionTextColor(Color.WHITE).setTextColor(Color.WHITE);

            // Make the text of the snackbar larger.
            TextView snackbarActionTextView = (TextView) ccSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
            snackbarActionTextView.setTextSize(20);
            TextView snackbarTextView = (TextView) ccSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
            snackbarTextView.setTextSize(20);

            ccSnackbar.show();
        }
    }

    public void doneButtonClicked(View view) {
        setResult(Activity.RESULT_OK, getIntent());
        finish();
    }

    private void attachTextView(int viewId, Consumer<String> onTextChanged) {
        TextView view = findViewById(viewId);
        view.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                onTextChanged.accept(s.toString().trim());
            }
        });
    }

    private String extractPrefix(String text, int length) {
        return (text != null && text.length() >= length) ? text.substring(0, length) : text;
    }

    private void attachSwitch(int id, Consumer<Boolean> onChange) {
        ((Switch) findViewById(id)).setOnCheckedChangeListener((buttonView, isChecked) -> onChange.accept(isChecked));
    }

    private void attachSlider(int viewId, Consumer<Integer> onValueChanged) {
        Slider slider = findViewById(viewId);
        slider.addOnChangeListener((s, value, fromUser) -> onValueChanged.accept((int) value));
    }

    private void attachListeners() {
        attachTextView(R.id.callsignTextInputEditText, text -> setCallsign(text.toUpperCase()));
        attachTextView(R.id.aprsPositionAccuracyTextView, this::setAprsPositionAccuracy);
        attachTextView(R.id.bandwidthTextView, this::setBandwidth);
        attachTextView(R.id.min2mFreqTextView, text -> setMin2mTxFreq(extractPrefix(text, 3)));
        attachTextView(R.id.max2mFreqTextView, text -> setMax2mTxFreq(extractPrefix(text, 3)));
        attachTextView(R.id.min70cmFreqTextView, text -> setMin70cmTxFreq(extractPrefix(text, 3)));
        attachTextView(R.id.max70cmFreqTextView, text -> setMax70cmTxFreq(extractPrefix(text, 3)));
        attachTextView(R.id.micGainBoostTextView, this::setMicGainBoost);
        attachTextView(R.id.rfPowerTextView, this::setRfPower);
        attachSlider(R.id.squelchSlider, this::setSquelch);
        attachSwitch(R.id.emphasisSwitch, this::setEmphasisFilter);
        attachSwitch(R.id.highpassSwitch, this::setHighpassFilter);
        attachSwitch(R.id.lowpassSwitch, this::setLowpassFilter);
        attachSwitch(R.id.stickyPTTSwitch, this::setStickyPTT);
        attachSwitch(R.id.noAnimationsSwitch, this::setNoAnimations);
        attachSwitch(R.id.aprsPositionSwitch, this::setAprsBeaconPosition);
    }

    private void saveAppSettingAsync(String key, String value) {
        threadPoolExecutor.execute(() -> {
            viewModel.getAppDb().saveAppSetting(key, value);
        });
    }

    private void setAprsBeaconPosition(boolean enabled) {
        saveAppSettingAsync("aprsBeaconPosition", Boolean.toString(enabled));
    }

    private void setAprsPositionAccuracy(String accuracy) {
        saveAppSettingAsync("aprsPositionAccuracy", accuracy);
    }

    private void setBandwidth(String bandwidth) {
        saveAppSettingAsync("bandwidth", bandwidth);
    }

    private void setMin2mTxFreq(String freq) {
        saveAppSettingAsync("min2mTxFreq", freq);
    }

    private void setMax2mTxFreq(String freq) {
        saveAppSettingAsync("max2mTxFreq", freq);
    }

    private void setMin70cmTxFreq(String freq) {
        saveAppSettingAsync("min70cmTxFreq", freq);
    }

    private void setMax70cmTxFreq(String freq) {
        saveAppSettingAsync("max70cmTxFreq", freq);
    }

    private void setMicGainBoost(String level) {
        saveAppSettingAsync("micGainBoost", level);
    }

    private void setRfPower(String rfPower) {
        saveAppSettingAsync("rfPower", rfPower);
    }

    private void setCallsign(String callsign) {
        saveAppSettingAsync("callsign", callsign);
    }

    private void setSquelch(int squelch) {
        saveAppSettingAsync("squelch", Integer.toString(squelch));
    }

    private void setEmphasisFilter(boolean enabled) {
        saveAppSettingAsync("emphasis", Boolean.toString(enabled));
    }

    private void setHighpassFilter(boolean enabled) {
        saveAppSettingAsync("highpass", Boolean.toString(enabled));
    }

    private void setLowpassFilter(boolean enabled) {
        saveAppSettingAsync("lowpass", Boolean.toString(enabled));
    }

    private void setStickyPTT(boolean enabled) {
        saveAppSettingAsync("stickyPTT", Boolean.toString(enabled));
    }

    private void setNoAnimations(boolean enabled) {
        saveAppSettingAsync("disableAnimations", Boolean.toString(enabled));
    }
}