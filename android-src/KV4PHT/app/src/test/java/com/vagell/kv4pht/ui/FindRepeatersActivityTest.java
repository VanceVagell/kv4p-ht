package com.vagell.kv4pht.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Build;

import org.junit.Test;

public class FindRepeatersActivityTest {
    @Test
    public void requiresStoragePermissionOnlyThroughAndroidPie() {
        assertTrue(FindRepeatersActivity.requiresLegacyStoragePermission(Build.VERSION_CODES.O));
        assertTrue(FindRepeatersActivity.requiresLegacyStoragePermission(Build.VERSION_CODES.P));
        assertFalse(FindRepeatersActivity.requiresLegacyStoragePermission(Build.VERSION_CODES.Q));
    }
}
