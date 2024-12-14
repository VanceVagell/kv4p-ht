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
import android.view.KeyEvent;
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
import com.vagell.kv4pht.radio.RadioAudioService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class SettingsActivity extends AppCompatActivity {
    private static final String DEFAULT_RX_SAMPLE_RATE_MULT = "1.00470";

    private ThreadPoolExecutor threadPoolExecutor = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        threadPoolExecutor = new ThreadPoolExecutor(2,
                2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());

        populateOriginalValues();
        populateMaxFrequencies();
        populateMicGainOptions();
        populateSampleRateOptions();
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

    private void populateMaxFrequencies() {
        AutoCompleteTextView maxFreqTextView = findViewById(R.id.maxFreqTextView);

        List<String> maxFreqs = new ArrayList<String>();
        maxFreqs.add("148MHz");
        maxFreqs.add("146MHz");

        ArrayAdapter arrayAdapter = new ArrayAdapter(this, R.layout.dropdown_item, maxFreqs);
        maxFreqTextView.setAdapter(arrayAdapter);
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

    private void populateSampleRateOptions() {
        AutoCompleteTextView rxSampleRateTextView = findViewById(R.id.rxSampleRateTextView);
        AutoCompleteTextView txSampleRateTextView = findViewById(R.id.txSampleRateTextView);

        List<String> sampleRateOptions = new ArrayList<String>();
        sampleRateOptions.add("44.1kHz");
        sampleRateOptions.add("22kHz");
        sampleRateOptions.add("16kHz");

        ArrayAdapter arrayAdapter = new ArrayAdapter(this, R.layout.dropdown_item, sampleRateOptions);
        rxSampleRateTextView.setAdapter(arrayAdapter);
        txSampleRateTextView.setAdapter(arrayAdapter);
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
                AppSetting emphasisSetting = MainViewModel.appDb.appSettingDao().getByName("emphasis");
                AppSetting highpassSetting = MainViewModel.appDb.appSettingDao().getByName("highpass");
                AppSetting lowpassSetting = MainViewModel.appDb.appSettingDao().getByName("lowpass");
                AppSetting stickyPTTSetting = MainViewModel.appDb.appSettingDao().getByName("stickyPTT");
                AppSetting disableAnimationsSetting = MainViewModel.appDb.appSettingDao().getByName("disableAnimations");
                AppSetting maxFreqSetting = MainViewModel.appDb.appSettingDao().getByName("maxFreq");
                AppSetting micGainBoostSetting = MainViewModel.appDb.appSettingDao().getByName("micGainBoost");
                AppSetting rxSampleRateSetting = MainViewModel.appDb.appSettingDao().getByName("rxSampleRate");
                AppSetting txSampleRateSetting = MainViewModel.appDb.appSettingDao().getByName("txSampleRate");
                AppSetting rxSampleRateMultSetting = MainViewModel.appDb.appSettingDao().getByName("rxSampleRateMult");

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

                        if (maxFreqSetting != null) {
                            AutoCompleteTextView maxFreqTextView = (AutoCompleteTextView) findViewById(R.id.maxFreqTextView);
                            maxFreqTextView.setText(maxFreqSetting.value + "MHz", false);
                        }

                        if (micGainBoostSetting != null) {
                            AutoCompleteTextView micGainBoostTextView = (AutoCompleteTextView) findViewById(R.id.micGainBoostTextView);
                            micGainBoostTextView.setText(micGainBoostSetting.value, false);
                        }

                        if (rxSampleRateSetting != null) {
                            AutoCompleteTextView rxSampleRateTextView = (AutoCompleteTextView) findViewById(R.id.rxSampleRateTextView);
                            rxSampleRateTextView.setText(rxSampleRateSetting.value, false);
                        }

                        if (txSampleRateSetting != null) {
                            AutoCompleteTextView txSampleRateTextView = (AutoCompleteTextView) findViewById(R.id.txSampleRateTextView);
                            txSampleRateTextView.setText(txSampleRateSetting.value, false);
                        }

                        if (rxSampleRateMultSetting != null) {
                            TextView rxSampleRateMultTextView = (TextView) findViewById(R.id.rxSampleRateMultTextView);
                            rxSampleRateMultTextView.setText(rxSampleRateMultSetting.value);
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

        TextView maxFreqTextView = findViewById(R.id.maxFreqTextView);
        maxFreqTextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String newText = ((TextView) findViewById(R.id.maxFreqTextView)).getText().toString().trim();
                setMaxFreq(newText.substring(0, 3));
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

        TextView rxSampleRateTextView = findViewById(R.id.rxSampleRateTextView);
        rxSampleRateTextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String newText = ((TextView) findViewById(R.id.rxSampleRateTextView)).getText().toString().trim();
                setRxSampleRate(newText);
            }
        });

        TextView txSampleRateTextView = findViewById(R.id.txSampleRateTextView);
        txSampleRateTextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String newText = ((TextView) findViewById(R.id.txSampleRateTextView)).getText().toString().trim();
                setTxSampleRate(newText);
            }
        });

        TextView rxSampleRateMultTextView = findViewById(R.id.rxSampleRateMultTextView);
        rxSampleRateMultTextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String newText = ((TextView) findViewById(R.id.rxSampleRateMultTextView)).getText().toString().trim();
                setRxSampleRateMult(newText);
            }
        });
    }

    /**
     * @param maxFreq Megahertz as a string, e.g. "148".
     */
    private void setMaxFreq(String maxFreq) {
        if (threadPoolExecutor == null) {
            return;
        }

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting setting = MainViewModel.appDb.appSettingDao().getByName("maxFreq");

                if (setting == null) {
                    setting = new AppSetting("maxFreq", maxFreq);
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

    /**
     * @param rxSampleRate The rate at which rx audio should be sampled by the ESP32 firmware, e.g. "44.1kHz", "16kHz"
     */
    private void setRxSampleRate(String rxSampleRate) {
        if (threadPoolExecutor == null) {
            return;
        }

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting setting = MainViewModel.appDb.appSettingDao().getByName("rxSampleRate");

                if (setting == null) {
                    setting = new AppSetting("rxSampleRate", rxSampleRate);
                    MainViewModel.appDb.appSettingDao().insertAll(setting);
                } else {
                    setting.value = rxSampleRate;
                    MainViewModel.appDb.appSettingDao().update(setting);
                }
            }
        });
    }

    /**
     * @param txSampleRate The rate at which tx audio should be sampled by the ESP32 firmware, e.g. "44.1kHz", "16kHz"
     */
    private void setTxSampleRate(String txSampleRate) {
        if (threadPoolExecutor == null) {
            return;
        }

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting setting = MainViewModel.appDb.appSettingDao().getByName("txSampleRate");

                if (setting == null) {
                    setting = new AppSetting("txSampleRate", txSampleRate);
                    MainViewModel.appDb.appSettingDao().insertAll(setting);
                } else {
                    setting.value = txSampleRate;
                    MainViewModel.appDb.appSettingDao().update(setting);
                }
            }
        });
    }

    /**
     * @param rxSampleRateMult A multipler (with baseline of 1.0) to apply to the rx sample rate.
     *                         This can be used to adjust for ESP32 dev boards that aren't sampling
     *                         at exactly the right rate.
     */
    private void setRxSampleRateMult(String rxSampleRateMult) {
        if (threadPoolExecutor == null) {
            return;
        }

        // If the given value has any issues, revert to default.
        if (null == rxSampleRateMult || rxSampleRateMult.trim().length() == 0) {
            rxSampleRateMult = DEFAULT_RX_SAMPLE_RATE_MULT;
            ((TextView) findViewById(R.id.rxSampleRateMultTextView)).setText(rxSampleRateMult);
        } else {
            try {
                float multFloat = Float.parseFloat(rxSampleRateMult);
                if (multFloat < 0.5 || multFloat > 1.5) {
                    rxSampleRateMult = DEFAULT_RX_SAMPLE_RATE_MULT;
                    ((TextView) findViewById(R.id.rxSampleRateMultTextView)).setText(rxSampleRateMult);
                }
            } catch (Exception e) {
                rxSampleRateMult = DEFAULT_RX_SAMPLE_RATE_MULT;
                ((TextView) findViewById(R.id.rxSampleRateMultTextView)).setText(rxSampleRateMult);
            }
        }

        final String fixedMult = rxSampleRateMult;

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting setting = MainViewModel.appDb.appSettingDao().getByName("rxSampleRateMult");

                if (setting == null) {
                    setting = new AppSetting("rxSampleRateMult", fixedMult);
                    MainViewModel.appDb.appSettingDao().insertAll(setting);
                } else {
                    setting.value = fixedMult;
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