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

package com.vagell.kv4pht.repeaterbook;

import com.vagell.kv4pht.data.AppDatabase;
import com.vagell.kv4pht.data.AppSetting;
import com.vagell.kv4pht.data.AppSettingDao;

/**
 * Stores each user's own RepeaterBook API token in the app's Room database.
 *
 * <p>Only this user's device holds the token. It is never bundled with the app or
 * sent anywhere except RepeaterBook's Export API.</p>
 */
public class RepeaterBookTokenStore {
    private RepeaterBookTokenStore() {
        // Static utility class.
    }

    public static String loadToken(AppDatabase database) {
        AppSetting setting = database.appSettingDao()
                .getByName(AppSetting.SETTING_REPEATERBOOK_API_TOKEN);
        return setting == null || setting.value == null ? null : setting.value.trim();
    }

    public static void saveToken(AppDatabase database, String token) {
        database.saveAppSetting(AppSetting.SETTING_REPEATERBOOK_API_TOKEN, token.trim());
    }

    public static void clearToken(AppDatabase database) {
        AppSettingDao dao = database.appSettingDao();
        AppSetting setting = dao.getByName(AppSetting.SETTING_REPEATERBOOK_API_TOKEN);
        if (setting != null) {
            dao.delete(setting);
        }
    }
}