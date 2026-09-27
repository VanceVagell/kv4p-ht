/*
kv4p HT (see http://kv4p.com)
Copyright (C) 2024 Vance Vagell

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.
*/

package com.vagell.kv4pht.data;

import static org.junit.Assert.assertEquals;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Queue;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import org.junit.Rule;
import org.junit.Test;

public class AprsFeedRepositoryTest {
    @Rule public InstantTaskExecutorRule liveData = new InstantTaskExecutorRule();

    @Test public void burstRefreshesExecuteOneQueryUsingLatestSettings() {
        Queue<Runnable> tasks = new ArrayDeque<>();
        int[] queries = {0};
        AprsEventDao dao = dao((method, args) -> {
            queries[0]++;
            assertEquals("getMineFeedSince", method);
            assertEquals("VK3ME", args[2]);
        });
        AprsFeedRepository repository = new AprsFeedRepository(dao, tasks::add, () -> "VK3ME-0");
        for (int i = 0; i < 100; i++) repository.refresh();
        repository.setDestinationFilter("mine");
        assertEquals(1, tasks.size());
        tasks.remove().run();
        assertEquals(1, queries[0]);
        assertEquals(0, tasks.size());
    }

    @Test public void writesDuringQueryScheduleOnlyOneFollowUpAndYieldToPackets() {
        Queue<Runnable> tasks = new ArrayDeque<>();
        int[] queries = {0};
        AprsFeedRepository[] repository = new AprsFeedRepository[1];
        AprsEventDao dao = dao((method, args) -> {
            if (++queries[0] == 1) {
                for (int i = 0; i < 100; i++) repository[0].refresh();
            }
        });
        repository[0] = new AprsFeedRepository(dao, tasks::add, () -> "VK3ME");
        int[] packets = {0};
        tasks.add(() -> packets[0]++);
        tasks.remove().run();
        assertEquals(2, tasks.size());
        tasks.remove().run();
        assertEquals(1, packets[0]);
        tasks.remove().run();
        assertEquals(2, queries[0]);
        assertEquals(0, tasks.size());
    }

    private interface QueryCheck {
        void check(String method, Object[] args);
    }

    private static AprsEventDao dao(QueryCheck check) {
        return (AprsEventDao) Proxy.newProxyInstance(AprsEventDao.class.getClassLoader(),
            new Class<?>[] {AprsEventDao.class}, (proxy, method, args) -> {
                check.check(method.getName(), args);
                return Collections.emptyList();
            });
    }
}
