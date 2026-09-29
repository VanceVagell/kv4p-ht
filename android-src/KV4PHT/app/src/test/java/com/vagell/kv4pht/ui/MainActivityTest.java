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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.recyclerview.widget.RecyclerView;
import org.junit.Test;

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
}
