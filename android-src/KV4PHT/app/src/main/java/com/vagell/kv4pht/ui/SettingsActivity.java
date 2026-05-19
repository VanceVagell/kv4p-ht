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
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;

import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.vagell.kv4pht.BuildConfig;
import com.vagell.kv4pht.R;
import com.vagell.kv4pht.data.AppSetting;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class SettingsActivity extends AppCompatActivity {
    private final ExecutorService threadPoolExecutor = Executors.newSingleThreadExecutor();
    private MainViewModel viewModel = null;
    private boolean hasHighLowPowerSwitch = false;
    private int firmwareVersion = -1;
    public static final String EXTRA_RF_POWER_HIGH = "rfPowerHigh";
    public static final String EXTRA_BANDWIDTH = "bandwidth";
    public static final String EXTRA_SQUELCH = "squelch";
    public static final String EXTRA_FILTER_PRE = "filterPre";
    public static final String EXTRA_FILTER_HIGH = "filterHigh";
    public static final String EXTRA_FILTER_LOW = "filterLow";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);
        hasHighLowPowerSwitch = getIntent().getBooleanExtra("hasHighLowPowerSwitch", false);
        firmwareVersion = getIntent().getIntExtra("firmwareVersion", -1);
        setContentView(R.layout.activity_settings);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        populateOriginalValues(this::attachListeners);
        populateBandwidths();
        populateMinFrequencies();
        populateMaxFrequencies();
        populateMicGainOptions();
        populateAprsOptions();
        populateAprsFrequencies();
        populateRadioOptions();
        populateVersions();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        threadPoolExecutor.shutdown();
    }

    private void setDropdownOptions(int viewId, List<String> options) {
        this.<AutoCompleteTextView>findViewById(viewId)
            .setAdapter(new ArrayAdapter<>(this, R.layout.dropdown_item, options));
    }

    private void populateBandwidths() {
        setDropdownOptions(R.id.bandwidthTextView, List.of(getString(R.string.wide), getString(R.string.narrow)));
    }

    private void populateMinFrequencies() {
        setDropdownOptions(R.id.min2mFreqTextView, List.of("144MHz"));
        setDropdownOptions(R.id.min70cmFreqTextView, List.of("420MHz", "430MHz"));
    }

    private void populateMaxFrequencies() {
        setDropdownOptions(R.id.max2mFreqTextView, List.of("148MHz", "146MHz"));
        setDropdownOptions(R.id.max70cmFreqTextView, List.of("450MHz", "440MHz"));
    }

    private void populateMicGainOptions() {
        setDropdownOptions(R.id.micGainBoostTextView, List.of("None", "Low", "Med", "High"));
    }

    private void populateAprsOptions() {
        setDropdownOptions(R.id.aprsPositionAccuracyTextView, List.of("Exact", "Approx"));
    }

    private void populateAprsFrequencies() {
        setDropdownOptions(R.id.aprsBeaconFreqTextView, List.of(
            getString(R.string.current),
            getString(R.string.freq_144_3900),
            getString(R.string.freq_144_5750),
            getString(R.string.freq_144_6400),
            getString(R.string.freq_144_6600),
            getString(R.string.freq_144_8000),
            getString(R.string.freq_145_1750),
            getString(R.string.freq_145_8250)
        ));
    }

    private void populateRadioOptions() {
        AutoCompleteTextView rfPowerTextView = findViewById(R.id.rfPowerTextView);
        rfPowerTextView.setThreshold(1);
        if (hasHighLowPowerSwitch) {
            rfPowerTextView.setEnabled(true);
            rfPowerTextView.setFocusable(true);
            rfPowerTextView.setClickable(true);
            setDropdownOptions(R.id.rfPowerTextView, Arrays.asList(getResources().getStringArray(R.array.rf_power_options)));
        } else {
            rfPowerTextView.setEnabled(false);
            rfPowerTextView.setFocusable(false);
            rfPowerTextView.setClickable(false);
            rfPowerTextView.setAdapter(null);
            // Set default text to first item
            rfPowerTextView.setText(getResources().getStringArray(R.array.rf_power_options)[0], false);
        }
    }

    private void populateVersions() {
        TextView appVersionTextView = findViewById(R.id.settings_app_version);
        appVersionTextView.setText(appVersionTextView.getText() + " " + BuildConfig.VERSION_NAME);

        TextView firmwareVersionTextView = findViewById(R.id.settings_firmware_version);
        firmwareVersionTextView.setText(firmwareVersionTextView.getText() + " " +
                (firmwareVersion == -1 ? "unknown" : Integer.toString(firmwareVersion)));
    }

    private void setTextIfPresent(Map<String, String> settings, String key, int viewId) {
        if (settings.containsKey(key)) {
            this.<TextInputEditText>findViewById(viewId).setText(settings.get(key));
        }
    }

    private void setSwitchIfPresent(Map<String, String> settings, String key, int viewId) {
        if (settings.containsKey(key)) {
            this.<Switch>findViewById(viewId).setChecked(Boolean.parseBoolean(settings.get(key)));
        }
    }

    private void setDropdownIfPresent(Map<String, String> settings, String key, int viewId) {
        if (settings.containsKey(key)) {
            String value = settings.get(key);
            if ("Current".equals(value)) {
                value = getString(R.string.current);
            }
            this.<AutoCompleteTextView>findViewById(viewId).setText(value, false);
        }
    }

    private void setDropdownIfPresent(Map<String, String> settings, String key, int viewId, String suffix) {
        if (settings.containsKey(key)) {
            this.<AutoCompleteTextView>findViewById(viewId)
                .setText(String.format("%s%s", settings.get(key), suffix), false);
        }
    }

    private void populateOriginalValues(Runnable callback) {
        threadPoolExecutor.execute(() -> {
            final Map<String, String> settings = viewModel.getAppDb().appSettingDao().getAll().stream()
                .collect(Collectors.toMap(AppSetting::getName, AppSetting::getValue));
            runOnUiThread(() -> {
                String mhz = getString(R.string.mhz);
                setTextIfPresent(settings, AppSetting.SETTING_CALLSIGN, R.id.callsignTextInputEditText);
                setSwitchIfPresent(settings, AppSetting.SETTING_STICKY_PTT, R.id.stickyPTTSwitch);
                setSwitchIfPresent(settings, AppSetting.SETTING_DISABLE_ANIMATIONS, R.id.noAnimationsSwitch);
                setSwitchIfPresent(settings, AppSetting.SETTING_APRS_BEACON_POSITION, R.id.aprsPositionSwitch);
                if (settings.containsKey(AppSetting.SETTING_APRS_BEACON_FREQUENCY)) {
                    setDropdownIfPresent(settings, AppSetting.SETTING_APRS_BEACON_FREQUENCY, R.id.aprsBeaconFreqTextView);
                } else {
                    this.<AutoCompleteTextView>findViewById(R.id.aprsBeaconFreqTextView).setText(getString(R.string.current), false);
                }
                setDropdownIfPresent(settings, AppSetting.SETTING_APRS_POSITION_ACCURACY, R.id.aprsPositionAccuracyTextView);
                setRadioSettingsFromIntent();
                setDropdownIfPresent(settings, AppSetting.SETTING_MIN_2_M_TX_FREQ, R.id.min2mFreqTextView, mhz);
                setDropdownIfPresent(settings, AppSetting.SETTING_MAX_2_M_TX_FREQ, R.id.max2mFreqTextView, mhz);
                setDropdownIfPresent(settings, AppSetting.SETTING_MIN_70_CM_TX_FREQ, R.id.min70cmFreqTextView, mhz);
                setDropdownIfPresent(settings, AppSetting.SETTING_MAX_70_CM_TX_FREQ, R.id.max70cmFreqTextView, mhz);
                setDropdownIfPresent(settings, AppSetting.SETTING_MIC_GAIN_BOOST, R.id.micGainBoostTextView);
                callback.run();
            });
        });
    }

    private void setRadioSettingsFromIntent() {
        this.<Slider>findViewById(R.id.squelchSlider).setValue(getIntent().getIntExtra(EXTRA_SQUELCH, 0));
        this.<Switch>findViewById(R.id.emphasisSwitch).setChecked(getIntent().getBooleanExtra(EXTRA_FILTER_PRE, false));
        this.<Switch>findViewById(R.id.highpassSwitch).setChecked(getIntent().getBooleanExtra(EXTRA_FILTER_HIGH, false));
        this.<Switch>findViewById(R.id.lowpassSwitch).setChecked(getIntent().getBooleanExtra(EXTRA_FILTER_LOW, false));
        this.<AutoCompleteTextView>findViewById(R.id.bandwidthTextView)
            .setText(getIntent().getStringExtra(EXTRA_BANDWIDTH) != null ? getIntent().getStringExtra(EXTRA_BANDWIDTH) : getString(R.string.wide), false);

        String[] powerOptions = getResources().getStringArray(R.array.rf_power_options);
        if (powerOptions.length > 0) {
            this.<AutoCompleteTextView>findViewById(R.id.rfPowerTextView)
                .setText(powerOptions[getIntent().getBooleanExtra(EXTRA_RF_POWER_HIGH, true) ? 0 : Math.min(1, powerOptions.length - 1)], false);
        }
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
            TextView snackbarTextView = ccSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
            snackbarTextView.setTextSize(20);

            ccSnackbar.show();
        }
    }

    public void doneButtonClicked(View view) {
        Intent data = new Intent()
            .putExtra(EXTRA_RF_POWER_HIGH, isHighPowerSelected())
            .putExtra(EXTRA_BANDWIDTH, this.<AutoCompleteTextView>findViewById(R.id.bandwidthTextView).getText().toString().trim())
            .putExtra(EXTRA_SQUELCH, (int) this.<Slider>findViewById(R.id.squelchSlider).getValue())
            .putExtra(EXTRA_FILTER_PRE, this.<Switch>findViewById(R.id.emphasisSwitch).isChecked())
            .putExtra(EXTRA_FILTER_HIGH, this.<Switch>findViewById(R.id.highpassSwitch).isChecked())
            .putExtra(EXTRA_FILTER_LOW, this.<Switch>findViewById(R.id.lowpassSwitch).isChecked());
        setResult(Activity.RESULT_OK, data);
        finish();
    }

    private boolean isHighPowerSelected() {
        String[] powerOptions = getResources().getStringArray(R.array.rf_power_options);
        String selected = this.<AutoCompleteTextView>findViewById(R.id.rfPowerTextView).getText().toString().trim();
        return powerOptions.length == 0 || selected.equals(powerOptions[0]);
    }

    private void attachTextView(int viewId, Consumer<String> onTextChanged) {
        TextView view = findViewById(viewId);
        view.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // NOOP
            }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                // NOOP
            }
            @Override public void afterTextChanged(Editable s) {
                onTextChanged.accept(s.toString().trim());
            }
        });
    }

    private String extractPrefix(String text) {
        return (text != null && text.length() >= 3) ? text.substring(0, 3) : text;
    }

    private void attachSwitch(int id, Consumer<Boolean> onChange) {
        ((Switch) findViewById(id)).setOnCheckedChangeListener((buttonView, isChecked) -> onChange.accept(isChecked));
    }

    private void attachListeners() {
        attachTextView(R.id.callsignTextInputEditText, text -> setCallsign(text.toUpperCase()));
        attachTextView(R.id.aprsPositionAccuracyTextView, this::setAprsPositionAccuracy);
        attachTextView(R.id.min2mFreqTextView, text -> setMin2mTxFreq(extractPrefix(text)));
        attachTextView(R.id.max2mFreqTextView, text -> setMax2mTxFreq(extractPrefix(text)));
        attachTextView(R.id.min70cmFreqTextView, text -> setMin70cmTxFreq(extractPrefix(text)));
        attachTextView(R.id.max70cmFreqTextView, text -> setMax70cmTxFreq(extractPrefix(text)));
        attachTextView(R.id.micGainBoostTextView, this::setMicGainBoost);
        attachSwitch(R.id.stickyPTTSwitch, this::setStickyPTT);
        attachSwitch(R.id.noAnimationsSwitch, this::setNoAnimations);
        attachSwitch(R.id.aprsPositionSwitch, this::setAprsBeaconPosition);
        attachTextView(R.id.aprsBeaconFreqTextView, this::setAprsBeaconFrequency);
    }

    private void saveAppSettingAsync(String key, String value) {
        threadPoolExecutor.execute(() -> viewModel.getAppDb().saveAppSetting(key, value));
    }

    private void setAprsBeaconPosition(boolean enabled) {
        saveAppSettingAsync(AppSetting.SETTING_APRS_BEACON_POSITION, Boolean.toString(enabled));
    }

    private void setAprsBeaconFrequency(String frequency) {
        if (frequency.equals(getString(R.string.current))) {
            frequency = "Current";
        }
        saveAppSettingAsync(AppSetting.SETTING_APRS_BEACON_FREQUENCY, frequency);
    }

    private void setAprsPositionAccuracy(String accuracy) {
        saveAppSettingAsync(AppSetting.SETTING_APRS_POSITION_ACCURACY, accuracy);
    }

    private void setMin2mTxFreq(String freq) {
        saveAppSettingAsync(AppSetting.SETTING_MIN_2_M_TX_FREQ, freq);
    }

    private void setMax2mTxFreq(String freq) {
        saveAppSettingAsync(AppSetting.SETTING_MAX_2_M_TX_FREQ, freq);
    }

    private void setMin70cmTxFreq(String freq) {
        saveAppSettingAsync(AppSetting.SETTING_MIN_70_CM_TX_FREQ, freq);
    }

    private void setMax70cmTxFreq(String freq) {
        saveAppSettingAsync(AppSetting.SETTING_MAX_70_CM_TX_FREQ, freq);
    }

    private void setMicGainBoost(String level) {
        saveAppSettingAsync(AppSetting.SETTING_MIC_GAIN_BOOST, level);
    }

    private void setCallsign(String callsign) {
        saveAppSettingAsync(AppSetting.SETTING_CALLSIGN, callsign);
    }

    private void setStickyPTT(boolean enabled) {
        saveAppSettingAsync(AppSetting.SETTING_STICKY_PTT, Boolean.toString(enabled));
    }

    private void setNoAnimations(boolean enabled) {
        saveAppSettingAsync(AppSetting.SETTING_DISABLE_ANIMATIONS, Boolean.toString(enabled));
    }
}
