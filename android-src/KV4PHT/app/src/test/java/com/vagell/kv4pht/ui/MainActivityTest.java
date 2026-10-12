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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.snackbar.Snackbar;
import com.vagell.kv4pht.R;
import com.vagell.kv4pht.radio.RadioAudioService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MainActivityTest {
    @Test public void initialScrollFinishesOnlyAtActualBottom() {
        assertFalse(MainActivity.isAprsBottomVisible(20, 16, 400, 500));
        assertFalse(MainActivity.isAprsBottomVisible(20, 19, 600, 500));
        assertTrue(MainActivity.isAprsBottomVisible(20, 19, 500, 500));
        assertFalse(MainActivity.isAprsBottomVisible(0, RecyclerView.NO_POSITION, 0, 500));
    }

    @Test public void aprsListAtBottomAutoFollows() {
        assertTrue(MainActivity.isAprsBottomVisible(10, 9, 500, 500));
        assertTrue(MainActivity.isAprsBottomVisible(10, 9, 490, 500));
    }

    @Test public void aprsListAwayFromBottomKeepsReadingPosition() {
        assertFalse(MainActivity.isAprsBottomVisible(10, 6, 500, 500));
        assertFalse(MainActivity.isAprsBottomVisible(10, 7, 500, 500));
        assertFalse(MainActivity.isAprsBottomVisible(10, 8, 500, 500));
        assertFalse(MainActivity.isAprsBottomVisible(10, 9, 501, 500));
        assertFalse(MainActivity.isAprsBottomVisible(10, RecyclerView.NO_POSITION, 500, 500));
        assertFalse(MainActivity.isAprsBottomVisible(1, RecyclerView.NO_POSITION, 500, 500));
    }

    @Test public void manualBeaconShowsFrequencyErrorBeforeLocationLookup() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setContentView(R.layout.activity_main);
        Field callsign = MainActivity.class.getDeclaredField("callsign");
        callsign.setAccessible(true);
        callsign.set(activity, "VK3TEST");
        Field service = MainActivity.class.getDeclaredField("radioAudioService");
        service.setAccessible(true);
        service.set(activity, new RadioAudioService() {
            @Override public boolean canTransmitPositionBeacon() { return false; }
        });

        activity.singleBeaconButtonClicked(null);
        ShadowLooper.idleMainLooper();
        android.widget.TextView message = activity.findViewById(com.google.android.material.R.id.snackbar_text);
        assertEquals(activity.getString(R.string.can_t_tx_outside_ham_band), message.getText().toString());
    }

    @Test public void missingRadioSnackbarTracksBottomNavigation() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setContentView(R.layout.activity_main);
        Method show = MainActivity.class.getDeclaredMethod("showUSBSnackbar");
        show.setAccessible(true);
        show.invoke(activity);
        Field field = MainActivity.class.getDeclaredField("usbSnackbar");
        field.setAccessible(true);
        Snackbar snackbar = (Snackbar) field.get(activity);
        assertTrue(snackbar.isAnchorViewLayoutListenerEnabled());
    }

    @Test public void manualBeaconWithAllowedFrequencyRequestsLocation() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setContentView(R.layout.activity_main);
        Shadows.shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        Field callsign = MainActivity.class.getDeclaredField("callsign");
        callsign.setAccessible(true);
        callsign.set(activity, "VK3TEST");
        boolean[] sent = {false};
        Field service = MainActivity.class.getDeclaredField("radioAudioService");
        service.setAccessible(true);
        service.set(activity, new RadioAudioService() {
            @Override public boolean canTransmitPositionBeacon() { return true; }
            @Override public void sendPositionBeacon() { sent[0] = true; }
        });

        activity.singleBeaconButtonClicked(null);
        assertTrue(sent[0]);
    }
}
