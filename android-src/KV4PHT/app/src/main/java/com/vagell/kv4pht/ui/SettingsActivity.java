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
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.vagell.kv4pht.R;
import com.vagell.kv4pht.data.AppSetting;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class SettingsActivity extends AppCompatActivity {
    private ThreadPoolExecutor threadPoolExecutor = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        threadPoolExecutor = new ThreadPoolExecutor(2,
                2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());

        populateOriginalValues();
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
                    }
                });
            }});
    }

    public void closedCaptionsButtonClicked(View view) {
        startActivity(new Intent("com.android.settings.action.live_caption"));
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