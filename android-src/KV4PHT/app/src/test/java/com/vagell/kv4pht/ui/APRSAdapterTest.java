/*
kv4p HT (see http://kv4p.com)
Copyright (C) 2026 Vance Vagell

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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.vagell.kv4pht.R;
import io.github.dkaukov.aprs.AprsEvent;
import org.junit.Test;

public class APRSAdapterTest {
    @Test public void mapsEveryReliableDeliveryStateToItsOwnIndicator() {
        assertStyle(AprsEvent.DELIVERY_PENDING, R.drawable.ic_pending,
            R.string.aprs_delivery_pending);
        assertStyle(AprsEvent.DELIVERY_DELIVERED, R.drawable.ic_check,
            R.string.aprs_delivery_delivered);
        assertStyle(AprsEvent.DELIVERY_REJECTED, R.drawable.ic_rejected,
            R.string.aprs_delivery_rejected);
        assertStyle(AprsEvent.DELIVERY_FAILED, R.drawable.ic_failed,
            R.string.aprs_delivery_failed);
        assertNull(APRSAdapter.deliveryStatusStyle(AprsEvent.DELIVERY_NONE));
    }

    @Test public void positionDescriptionsStayOnMapWhileObjectsUseCards() {
        AprsEvent position = AprsEvent.builder()
            .type(AprsEvent.POSITION_TYPE)
            .fromCallsign("VK3ABC-7")
            .comment("Listening on 146.52")
            .build();

        AprsEvent object = AprsEvent.builder()
            .type(AprsEvent.OBJECT_TYPE)
            .fromCallsign("VK3RPT-S")
            .objectName("VK3RPT B")
            .comment("439.150 MHz repeater")
            .build();

        assertFalse(APRSAdapter.showCommentInFeed(position.getType()));
        assertTrue(APRSAdapter.showCommentInFeed(object.getType()));
        assertTrue(APRSAdapter.showCommentInFeed(AprsEvent.STATUS_TYPE));
        assertTrue(APRSAdapter.showCommentInFeed(AprsEvent.STATION_CAPABILITIES_TYPE));
        assertTrue(APRSAdapter.showCommentInFeed(AprsEvent.UNKNOWN_TYPE));
        assertEquals("VK3ABC-7: Listening on 146.52", APRSAdapter.mapLabel(position));
        assertEquals("VK3RPT B · 439.15 MHz", APRSAdapter.mapLabel(object));
    }

    @Test public void convertsAprsWeatherUnitsForMetricLocales() {
        assertEquals(20.0, APRSAdapter.fahrenheitToCelsius(68.0), 0.00001);
        assertEquals(293.15, APRSAdapter.fahrenheitToKelvin(68.0), 0.00001);
        assertEquals(16.09344,
            APRSAdapter.milesPerHourToKilometresPerHour(10.0), 0.00001);
    }

    private void assertStyle(int state, int drawable, int description) {
        APRSAdapter.DeliveryStatusStyle style = APRSAdapter.deliveryStatusStyle(state);
        assertEquals(drawable, style.drawable);
        assertEquals(description, style.description);
    }
}
