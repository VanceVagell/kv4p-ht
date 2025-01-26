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

import android.annotation.SuppressLint;
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
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.vagell.kv4pht.R;
import com.vagell.kv4pht.data.AppSetting;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class SettingsActivity extends AppCompatActivity {
    private ThreadPoolExecutor threadPoolExecutor = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        threadPoolExecutor = new ThreadPoolExecutor(2,
                2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());

        populateOriginalValues();
        populateRadioModuleTypes();
        populateBandwidths();
        populateMinFrequencies();
        populateMaxFrequencies();
        populateMicGainOptions();
        populateAprsOptions();
        attachListeners();
    }

    @Override
    protected void onPause() {
        super.onPause();
        threadPoolExecutor.shutdownNow();
        threadPoolExecutor = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        threadPoolExecutor = new ThreadPoolExecutor(2,
                2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
    }

    private void populateBandwidths() {
        AutoCompleteTextView bandwidthTextView = findViewById(R.id.bandwidthTextView);

        List<String> bandwidths = new ArrayList<String>();
        bandwidths.add("Wide");
        bandwidths.add("Narrow");

        ArrayAdapter arrayAdapter = new ArrayAdapter(this, R.layout.dropdown_item, bandwidths);
        bandwidthTextView.setAdapter(arrayAdapter);
    }

    private void populateRadioModuleTypes() {
        AutoCompleteTextView radioModuleTypeTextView = findViewById(R.id.radioModuleTypeTextView);

        List<String> bandwidths = new ArrayList<String>();
        bandwidths.add("VHF");
        bandwidths.add("UHF");

        ArrayAdapter arrayAdapter = new ArrayAdapter(this, R.layout.dropdown_item, bandwidths);
        radioModuleTypeTextView.setAdapter(arrayAdapter);
    }

    private void populateMinFrequencies() {
        AutoCompleteTextView min2mFreqTextView = findViewById(R.id.min2mFreqTextView);

        List<String> min2mFreqs = new ArrayList<String>();
        min2mFreqs.add("144MHz");

        ArrayAdapter arrayAdapter1 = new ArrayAdapter(this, R.layout.dropdown_item, min2mFreqs);
        min2mFreqTextView.setAdapter(arrayAdapter1);

        AutoCompleteTextView min70cmFreqTextView = findViewById(R.id.min70cmFreqTextView);

        List<String> min70cmFreqs = new ArrayList<String>();
        min70cmFreqs.add("420MHz");
        min70cmFreqs.add("430MHz");

        ArrayAdapter arrayAdapter2 = new ArrayAdapter(this, R.layout.dropdown_item, min70cmFreqs);
        min70cmFreqTextView.setAdapter(arrayAdapter2);
    }

    private void populateMaxFrequencies() {
        AutoCompleteTextView max2mFreqTextView = findViewById(R.id.max2mFreqTextView);

        List<String> max2mFreqs = new ArrayList<String>();
        max2mFreqs.add("148MHz");
        max2mFreqs.add("146MHz");

        ArrayAdapter arrayAdapter1 = new ArrayAdapter(this, R.layout.dropdown_item, max2mFreqs);
        max2mFreqTextView.setAdapter(arrayAdapter1);

        AutoCompleteTextView max70cmFreqTextView = findViewById(R.id.max70cmFreqTextView);

        List<String> max70cmFreqs = new ArrayList<String>();
        max70cmFreqs.add("450MHz");
        max70cmFreqs.add("440MHz");
        max70cmFreqs.add("480MHz");

        ArrayAdapter arrayAdapter2 = new ArrayAdapter(this, R.layout.dropdown_item, max70cmFreqs);
        max70cmFreqTextView.setAdapter(arrayAdapter2);
    }

    private void populateMicGainOptions() {
        AutoCompleteTextView micGainBoostTextView = findViewById(R.id.micGainBoostTextView);

        List<String> micGainOptions = new ArrayList<String>();
        micGainOptions.add("None");
        micGainOptions.add("Low");
        micGainOptions.add("Med");
        micGainOptions.add("High");

        ArrayAdapter arrayAdapter = new ArrayAdapter(this, R.layout.dropdown_item, micGainOptions);
        micGainBoostTextView.setAdapter(arrayAdapter);
    }

    private void populateAprsOptions() {
        AutoCompleteTextView aprsPositionAccuracyTextView = findViewById(R.id.aprsPositionAccuracyTextView);

        List<String> positionAccuracyOptions = new ArrayList<String>();
        positionAccuracyOptions.add("Exact");
        positionAccuracyOptions.add("Approx");

        ArrayAdapter arrayAdapter = new ArrayAdapter(this, R.layout.dropdown_item, positionAccuracyOptions);
        aprsPositionAccuracyTextView.setAdapter(arrayAdapter);
    }

    private void populateOriginalValues() {
        if (threadPoolExecutor == null) {
            return;
        }

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting callsignSetting = MainViewModel.appDb.appSettingDao().getByName("callsign");
                AppSetting squelchSetting = MainViewModel.appDb.appSettingDao().getByName("squelch");
                AppSetting moduleTypeSetting = MainViewModel.appDb.appSettingDao().getByName("moduleType");
                AppSetting emphasisSetting = MainViewModel.appDb.appSettingDao().getByName("emphasis");
                AppSetting highpassSetting = MainViewModel.appDb.appSettingDao().getByName("highpass");
                AppSetting lowpassSetting = MainViewModel.appDb.appSettingDao().getByName("lowpass");
                AppSetting stickyPTTSetting = MainViewModel.appDb.appSettingDao().getByName("stickyPTT");
                AppSetting disableAnimationsSetting = MainViewModel.appDb.appSettingDao().getByName("disableAnimations");
                AppSetting aprsBeaconPosition = MainViewModel.appDb.appSettingDao().getByName("aprsBeaconPosition");
                AppSetting aprsPositionAccuracy = MainViewModel.appDb.appSettingDao().getByName("aprsPositionAccuracy");
                AppSetting bandwidthSetting = MainViewModel.appDb.appSettingDao().getByName("bandwidth");
                AppSetting min2mTxFreqSetting = MainViewModel.appDb.appSettingDao().getByName("min2mTxFreq");
                AppSetting max2mTxFreqSetting = MainViewModel.appDb.appSettingDao().getByName("max2mTxFreq");
                AppSetting min70cmTxFreqSetting = MainViewModel.appDb.appSettingDao().getByName("min70cmTxFreq");
                AppSetting max70cmTxFreqSetting = MainViewModel.appDb.appSettingDao().getByName("max70cmTxFreq");
                AppSetting micGainBoostSetting = MainViewModel.appDb.appSettingDao().getByName("micGainBoost");

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (callsignSetting != null) {
                            TextInputEditText callsignEditText = (TextInputEditText) (findViewById(R.id.callsignTextInputEditText));
                            callsignEditText.setText(callsignSetting.value);
                        }

                        if (squelchSetting != null) {
                            Slider squelchSlider = (Slider) (findViewById(R.id.squelchSlider));
                            squelchSlider.setValue(Float.parseFloat(squelchSetting.value));
                        }

                        if (moduleTypeSetting != null) {
                            AutoCompleteTextView radioModuleTypeTextView = (AutoCompleteTextView) findViewById(R.id.radioModuleTypeTextView);
                            radioModuleTypeTextView.setText(moduleTypeSetting.value, false);
                        }

                        if (emphasisSetting != null) {
                            Switch emphasisSwitch = (Switch) (findViewById(R.id.emphasisSwitch));
                            emphasisSwitch.setChecked(Boolean.parseBoolean(emphasisSetting.value));
                        }

                        if (highpassSetting != null) {
                            Switch highpassSwitch = (Switch) (findViewById(R.id.highpassSwitch));
                            highpassSwitch.setChecked(Boolean.parseBoolean(highpassSetting.value));
                        }

                        if (lowpassSetting != null) {
                            Switch lowpassSwitch = (Switch) (findViewById(R.id.lowpassSwitch));
                            lowpassSwitch.setChecked(Boolean.parseBoolean(lowpassSetting.value));
                        }

                        if (stickyPTTSetting != null) {
                            Switch stickyPTTSwitch = (Switch) (findViewById(R.id.stickyPTTSwitch));
                            stickyPTTSwitch.setChecked(Boolean.parseBoolean(stickyPTTSetting.value));
                        }

                        if (disableAnimationsSetting != null) {
                            Switch noAnimationsSwitch = (Switch) (findViewById(R.id.noAnimationsSwitch));
                            noAnimationsSwitch.setChecked(Boolean.parseBoolean(disableAnimationsSetting.value));
                        }

                        if (aprsBeaconPosition != null) {
                            Switch aprsPositionSwitch = (Switch) (findViewById(R.id.aprsPositionSwitch));
                            aprsPositionSwitch.setChecked(Boolean.parseBoolean(aprsBeaconPosition.value));
                        }

                        if (aprsPositionAccuracy != null) {
                            AutoCompleteTextView aprsPositionAccuracyTextView = (AutoCompleteTextView) findViewById(R.id.aprsPositionAccuracyTextView);
                            aprsPositionAccuracyTextView.setText(aprsPositionAccuracy.value, false);
                        }

                        if (bandwidthSetting != null) {
                            AutoCompleteTextView bandwidthTextView = (AutoCompleteTextView) findViewById(R.id.bandwidthTextView);
                            bandwidthTextView.setText(bandwidthSetting.value, false);
                        }

                        if (min2mTxFreqSetting != null) {
                            AutoCompleteTextView min2mFreqTextView = (AutoCompleteTextView) findViewById(R.id.min2mFreqTextView);
                            min2mFreqTextView.setText(min2mTxFreqSetting.value + "MHz", false);
                        }

                        if (max2mTxFreqSetting != null) {
                            AutoCompleteTextView max2mFreqTextView = (AutoCompleteTextView) findViewById(R.id.max2mFreqTextView);
                            max2mFreqTextView.setText(max2mTxFreqSetting.value + "MHz", false);
                        }

                        if (min70cmTxFreqSetting != null) {
                            AutoCompleteTextView min70cmFreqTextView = (AutoCompleteTextView) findViewById(R.id.min70cmFreqTextView);
                            min70cmFreqTextView.setText(min70cmTxFreqSetting.value + "MHz", false);
                        }

                        if (max70cmTxFreqSetting != null) {
                            AutoCompleteTextView max70cmFreqTextView = (AutoCompleteTextView) findViewById(R.id.max70cmFreqTextView);
                            max70cmFreqTextView.setText(max70cmTxFreqSetting.value + "MHz", false);
                        }

                        if (micGainBoostSetting != null) {
                            AutoCompleteTextView micGainBoostTextView = (AutoCompleteTextView) findViewById(R.id.micGainBoostTextView);
                            micGainBoostTextView.setText(micGainBoostSetting.value, false);
                        }
                    }
                });
            }});
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

    private void attachListeners() {
        TextInputEditText callsignEditText = findViewById(R.id.callsignTextInputEditText);
        callsignEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                EditText callsignEditText = (EditText)(findViewById(R.id.callsignTextInputEditText));
                setCallsign(callsignEditText.getText().toString().toUpperCase().trim());
            }
        });

        Slider squelchSlider = findViewById(R.id.squelchSlider);
        squelchSlider.addOnChangeListener(new Slider.OnChangeListener() {
            @Override
            @SuppressLint("RestrictedApi")
            public void onValueChange(@NonNull Slider slider, float v, boolean b) {
                setSquelch((int)slider.getValue());
            }
        });

        TextView radioModuleTypeTextView = findViewById(R.id.radioModuleTypeTextView);
        radioModuleTypeTextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String newText = ((TextView) findViewById(R.id.radioModuleTypeTextView)).getText().toString().trim();
                setRadioModuleType(newText);
            }
        });

        Switch emphasisSwitch = findViewById(R.id.emphasisSwitch);
        emphasisSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                setEmphasisFilter(isChecked);
            }
        });

        Switch highpassSwitch = findViewById(R.id.highpassSwitch);
        highpassSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                setHighpassFilter(isChecked);
            }
        });

        Switch lowpassSwitch = findViewById(R.id.lowpassSwitch);
        lowpassSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                setLowpassFilter(isChecked);
            }
        });

        Switch stickyPTTSwitch = findViewById(R.id.stickyPTTSwitch);
        stickyPTTSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                setStickyPTT(isChecked);
            }
        });

        Switch noAnimationsSwitch = findViewById(R.id.noAnimationsSwitch);
        noAnimationsSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                setNoAnimations(isChecked);
            }
        });

        Switch aprsPositionSwitch = findViewById(R.id.aprsPositionSwitch);
        aprsPositionSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                setAprsBeaconPosition(isChecked);
            }
        });

        TextView aprsPositionAccuracyTextView = findViewById(R.id.aprsPositionAccuracyTextView);
        aprsPositionAccuracyTextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String newText = ((TextView) findViewById(R.id.aprsPositionAccuracyTextView)).getText().toString().trim();
                setAprsPositionAccuracy(newText);
            }
        });

        TextView bandwidthTextView = findViewById(R.id.bandwidthTextView);
        bandwidthTextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String newText = ((TextView) findViewById(R.id.bandwidthTextView)).getText().toString().trim();
                setBandwidth(newText);
            }
        });

        TextView min2mFreqTextView = findViewById(R.id.min2mFreqTextView);
        min2mFreqTextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String newText = ((TextView) findViewById(R.id.min2mFreqTextView)).getText().toString().trim();
                setMin2mTxFreq(newText.substring(0, 3));
            }
        });

        TextView max2mFreqTextView = findViewById(R.id.max2mFreqTextView);
        max2mFreqTextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String newText = ((TextView) findViewById(R.id.max2mFreqTextView)).getText().toString().trim();
                setMax2mTxFreq(newText.substring(0, 3));
            }
        });

        TextView min70cmFreqTextView = findViewById(R.id.min70cmFreqTextView);
        min70cmFreqTextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String newText = ((TextView) findViewById(R.id.min70cmFreqTextView)).getText().toString().trim();
                setMin70cmTxFreq(newText.substring(0, 3));
            }
        });

        TextView max70cmFreqTextView = findViewById(R.id.max70cmFreqTextView);
        max70cmFreqTextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String newText = ((TextView) findViewById(R.id.max70cmFreqTextView)).getText().toString().trim();
                setMax70cmTxFreq(newText.substring(0, 3));
            }
        });

        TextView micGainBoostTextView = findViewById(R.id.micGainBoostTextView);
        micGainBoostTextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String newText = ((TextView) findViewById(R.id.micGainBoostTextView)).getText().toString().trim();
                setMicGainBoost(newText);
            }
        });
    }

    private void setAprsBeaconPosition(boolean enabled) {
        if (threadPoolExecutor == null) {
            return;
        }

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting setting = MainViewModel.appDb.appSettingDao().getByName("aprsBeaconPosition");

                if (setting == null) {
                    setting = new AppSetting("aprsBeaconPosition", "" + enabled);
                    MainViewModel.appDb.appSettingDao().insertAll(setting);
                } else {
                    setting.value = "" + enabled;
                    MainViewModel.appDb.appSettingDao().update(setting);
                }
            }
        });
    }

    /**
     * @param aprsPositionAccuracy "Exact" or "Approx"
     */
    private void setAprsPositionAccuracy(String aprsPositionAccuracy) {
        if (threadPoolExecutor == null) {
            return;
        }

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting setting = MainViewModel.appDb.appSettingDao().getByName("aprsPositionAccuracy");

                if (setting == null) {
                    setting = new AppSetting("aprsPositionAccuracy", aprsPositionAccuracy);
                    MainViewModel.appDb.appSettingDao().insertAll(setting);
                } else {
                    setting.value = aprsPositionAccuracy;
                    MainViewModel.appDb.appSettingDao().update(setting);
                }
            }
        });
    }

    /**
     * @param radioModuleType "VHF" or "UHF"
     */
    private void setRadioModuleType(String radioModuleType) {
        if (threadPoolExecutor == null) {
            return;
        }

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting setting = MainViewModel.appDb.appSettingDao().getByName("moduleType");

                if (setting == null) {
                    setting = new AppSetting("moduleType", radioModuleType);
                    MainViewModel.appDb.appSettingDao().insertAll(setting);
                } else {
                    setting.value = radioModuleType;
                    MainViewModel.appDb.appSettingDao().update(setting);
                }
            }
        });
    }

    /**
     * @param bandwidth Change the bandwidth to use, either "Wide" or "Narrow". (25kHz or 12.5kHz)
     */
    private void setBandwidth(String bandwidth) {
        if (threadPoolExecutor == null) {
            return;
        }

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting setting = MainViewModel.appDb.appSettingDao().getByName("bandwidth");

                if (setting == null) {
                    setting = new AppSetting("bandwidth", bandwidth);
                    MainViewModel.appDb.appSettingDao().insertAll(setting);
                } else {
                    setting.value = bandwidth;
                    MainViewModel.appDb.appSettingDao().update(setting);
                }
            }
        });
    }

    /**
     * @param minFreq Megahertz as a string, e.g. "148".
     */
    private void setMin2mTxFreq(String minFreq) {
        if (threadPoolExecutor == null) {
            return;
        }

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting setting = MainViewModel.appDb.appSettingDao().getByName("min2mTxFreq");

                if (setting == null) {
                    setting = new AppSetting("min2mTxFreq", minFreq);
                    MainViewModel.appDb.appSettingDao().insertAll(setting);
                } else {
                    setting.value = minFreq;
                    MainViewModel.appDb.appSettingDao().update(setting);
                }
            }
        });
    }

    /**
     * @param maxFreq Megahertz as a string, e.g. "148".
     */
    private void setMax2mTxFreq(String maxFreq) {
        if (threadPoolExecutor == null) {
            return;
        }

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting setting = MainViewModel.appDb.appSettingDao().getByName("max2mTxFreq");

                if (setting == null) {
                    setting = new AppSetting("max2mTxFreq", maxFreq);
                    MainViewModel.appDb.appSettingDao().insertAll(setting);
                } else {
                    setting.value = maxFreq;
                    MainViewModel.appDb.appSettingDao().update(setting);
                }
            }
        });
    }

    /**
     * @param minFreq Megahertz as a string, e.g. "420".
     */
    private void setMin70cmTxFreq(String minFreq) {
        if (threadPoolExecutor == null) {
            return;
        }

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting setting = MainViewModel.appDb.appSettingDao().getByName("min70cmTxFreq");

                if (setting == null) {
                    setting = new AppSetting("min70cmTxFreq", minFreq);
                    MainViewModel.appDb.appSettingDao().insertAll(setting);
                } else {
                    setting.value = minFreq;
                    MainViewModel.appDb.appSettingDao().update(setting);
                }
            }
        });
    }

    /**
     * @param maxFreq Megahertz as a string, e.g. "450".
     */
    private void setMax70cmTxFreq(String maxFreq) {
        if (threadPoolExecutor == null) {
            return;
        }

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting setting = MainViewModel.appDb.appSettingDao().getByName("max70cmTxFreq");

                if (setting == null) {
                    setting = new AppSetting("max70cmTxFreq", maxFreq);
                    MainViewModel.appDb.appSettingDao().insertAll(setting);
                } else {
                    setting.value = maxFreq;
                    MainViewModel.appDb.appSettingDao().update(setting);
                }
            }
        });
    }

    /**
     * @param micGainBoost The level of software gain that should be added to mic audio.
     *                     Should be one of "None", "Low", "Med", or "High".
     */
    private void setMicGainBoost(String micGainBoost) {
        if (threadPoolExecutor == null) {
            return;
        }

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting setting = MainViewModel.appDb.appSettingDao().getByName("micGainBoost");

                if (setting == null) {
                    setting = new AppSetting("micGainBoost", micGainBoost);
                    MainViewModel.appDb.appSettingDao().insertAll(setting);
                } else {
                    setting.value = micGainBoost;
                    MainViewModel.appDb.appSettingDao().update(setting);
                }
            }
        });
    }

    private void setCallsign(String callsign) {
        if (callsign == null) {
            return;
        }

        if (threadPoolExecutor == null) {
            return;
        }

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting setting = MainViewModel.appDb.appSettingDao().getByName("callsign");

                if (setting == null) {
                    setting = new AppSetting("callsign", callsign);
                    MainViewModel.appDb.appSettingDao().insertAll(setting);
                } else {
                    setting.value = callsign;
                    MainViewModel.appDb.appSettingDao().update(setting);
                }
            }
        });
    }

    private void setSquelch(int squelch) {
        if (threadPoolExecutor == null) {
            return;
        }

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting setting = MainViewModel.appDb.appSettingDao().getByName("squelch");

                if (setting == null) {
                    setting = new AppSetting("squelch", "" + squelch);
                    MainViewModel.appDb.appSettingDao().insertAll(setting);
                } else {
                    setting.value = "" + squelch;
                    MainViewModel.appDb.appSettingDao().update(setting);
                }
            }
        });
    }

    private void setEmphasisFilter(boolean enabled) {
        if (threadPoolExecutor == null) {
            return;
        }

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting setting = MainViewModel.appDb.appSettingDao().getByName("emphasis");

                if (setting == null) {
                    setting = new AppSetting("emphasis", "" + enabled);
                    MainViewModel.appDb.appSettingDao().insertAll(setting);
                } else {
                    setting.value = "" + enabled;
                    MainViewModel.appDb.appSettingDao().update(setting);
                }
            }
        });
    }

    private void setHighpassFilter(boolean enabled) {
        if (threadPoolExecutor == null) {
            return;
        }

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting setting = MainViewModel.appDb.appSettingDao().getByName("highpass");

                if (setting == null) {
                    setting = new AppSetting("highpass", "" + enabled);
                    MainViewModel.appDb.appSettingDao().insertAll(setting);
                } else {
                    setting.value = "" + enabled;
                    MainViewModel.appDb.appSettingDao().update(setting);
                }
            }
        });
    }

    private void setLowpassFilter(boolean enabled) {
        if (threadPoolExecutor == null) {
            return;
        }

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting setting = MainViewModel.appDb.appSettingDao().getByName("lowpass");

                if (setting == null) {
                    setting = new AppSetting("lowpass", "" + enabled);
                    MainViewModel.appDb.appSettingDao().insertAll(setting);
                } else {
                    setting.value = "" + enabled;
                    MainViewModel.appDb.appSettingDao().update(setting);
                }
            }
        });
    }

    private void setStickyPTT(boolean enabled) {
        if (threadPoolExecutor == null) {
            return;
        }

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting setting = MainViewModel.appDb.appSettingDao().getByName("stickyPTT");

                if (setting == null) {
                    setting = new AppSetting("stickyPTT", "" + enabled);
                    MainViewModel.appDb.appSettingDao().insertAll(setting);
                } else {
                    setting.value = "" + enabled;
                    MainViewModel.appDb.appSettingDao().update(setting);
                }
            }
        });
    }

    private void setNoAnimations(boolean enabled) {
        if (threadPoolExecutor == null) {
            return;
        }

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting setting = MainViewModel.appDb.appSettingDao().getByName("disableAnimations");

                if (setting == null) {
                    setting = new AppSetting("disableAnimations", "" + enabled);
                    MainViewModel.appDb.appSettingDao().insertAll(setting);
                } else {
                    setting.value = "" + enabled;
                    MainViewModel.appDb.appSettingDao().update(setting);
                }
            }
        });
    }
}