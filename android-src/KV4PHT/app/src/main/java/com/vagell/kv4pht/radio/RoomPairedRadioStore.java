package com.vagell.kv4pht.radio;

import android.util.Log;

import com.vagell.kv4pht.data.AppDatabase;
import com.vagell.kv4pht.data.AppSetting;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stores remembered Bluetooth radios as a JSON list in the existing app_settings table. Using the
 * key/value settings table avoids a Room schema migration for a small, non-relational list.
 */
public final class RoomPairedRadioStore implements PairedRadioStore {
    static final String SETTING_PAIRED_BLE_RADIOS = "pairedBleRadios";
    private static final String TAG = RoomPairedRadioStore.class.getSimpleName();

    private final AppDatabase database;

    public RoomPairedRadioStore(AppDatabase database) {
        this.database = database;
    }

    @Override
    public List<PairedRadio> getAll() {
        AppSetting setting = database.appSettingDao().getByName(SETTING_PAIRED_BLE_RADIOS);
        if (setting == null || setting.getValue() == null || setting.getValue().isEmpty()) {
            return Collections.emptyList();
        }
        List<PairedRadio> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(setting.getValue());
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String address = item.optString("address", "");
                if (address.isEmpty()) {
                    continue;
                }
                result.add(new PairedRadio(address, item.optString("name", address)));
            }
        } catch (JSONException error) {
            Log.w(TAG, "Ignoring malformed paired radio list", error);
            return Collections.emptyList();
        }
        return result;
    }

    @Override
    public void saveAll(List<PairedRadio> radios) {
        JSONArray array = new JSONArray();
        for (PairedRadio radio : radios) {
            JSONObject item = new JSONObject();
            try {
                item.put("address", radio.getAddress());
                item.put("name", radio.getName());
            } catch (JSONException error) {
                Log.w(TAG, "Unable to serialize paired radio " + radio.getAddress(), error);
                continue;
            }
            array.put(item);
        }
        database.saveAppSetting(SETTING_PAIRED_BLE_RADIOS, array.toString());
    }
}