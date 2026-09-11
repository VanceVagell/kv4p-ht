package com.vagell.kv4pht.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.ContentValues;

import androidx.room.Room;
import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Verifies the v7-to-v8 reliable-message migration and Room DAO queries against SQLite. */
@RunWith(AndroidJUnit4.class)
public class AppDatabaseMigrationTest {
    private static final String DATABASE_NAME = "aprs-migration-test";

    @Rule
    public final MigrationTestHelper migrationHelper = new MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase.class.getCanonicalName(),
        new FrameworkSQLiteOpenHelperFactory());

    @After
    public void deleteTestDatabase() {
        InstrumentationRegistry.getInstrumentation().getTargetContext().deleteDatabase(DATABASE_NAME);
    }

    @Test
    public void migratesReliableMessageFieldsAndQueriesOnlyPendingMessages() throws Exception {
        SupportSQLiteDatabase database = migrationHelper.createDatabase(DATABASE_NAME, 7);
        ContentValues legacyMessage = new ContentValues();
        legacyMessage.put("type", APRSMessage.MESSAGE_TYPE);
        legacyMessage.put("from_callsign", "VK3OLD");
        legacyMessage.put("timestamp", 1L);
        legacyMessage.put("position_lat", 0D);
        legacyMessage.put("position_long", 0D);
        legacyMessage.put("ack", false);
        legacyMessage.put("message_num", 1);
        legacyMessage.put("temperature", 0D);
        legacyMessage.put("humidity", 0D);
        legacyMessage.put("pressure", 0D);
        legacyMessage.put("rain", 0D);
        legacyMessage.put("snow", 0D);
        legacyMessage.put("wind_force", 0);
        database.insert("aprs_messages", 0, legacyMessage);
        database.close();

        database = migrationHelper.runMigrationsAndValidate(
            DATABASE_NAME, 8, true, AppDatabase.MIGRATION_7_8);
        database.close();

        AppDatabase appDatabase = Room.databaseBuilder(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                AppDatabase.class, DATABASE_NAME)
            .addMigrations(AppDatabase.MIGRATION_7_8)
            .allowMainThreadQueries()
            .build();
        try {
            APRSMessageDao dao = appDatabase.aprsMessageDao();
            APRSMessage migratedMessage = dao.getAll().get(0);
            assertEquals(APRSMessage.DELIVERY_NONE, migratedMessage.deliveryState);
            assertEquals(0, migratedMessage.transmitAttempts);
            assertNull(migratedMessage.messageIdentifier);
            assertNull(migratedMessage.nextRetryAt);

            APRSMessage pending = reliableMessage("VK3ME", "VK3ABC", "7",
                APRSMessage.DELIVERY_PENDING, 100L);
            APRSMessage delivered = reliableMessage("VK3ME", "VK3ABC", "8",
                APRSMessage.DELIVERY_DELIVERED, 100L);
            APRSMessage otherStation = reliableMessage("VK3ME", "VK3XYZ", "7",
                APRSMessage.DELIVERY_PENDING, 101L);
            dao.insertAll(pending, delivered, otherStation);

            assertEquals(1, dao.getDueReliableMessages(APRSMessage.DELIVERY_PENDING, 100L).size());
            assertEquals(pending.id, dao.getPendingOutgoingMessage("VK3ME", "VK3ABC", "7",
                APRSMessage.DELIVERY_PENDING).id);
            assertNull(dao.getPendingOutgoingMessage("VK3ME", "VK3XYZ", "8",
                APRSMessage.DELIVERY_PENDING));
        } finally {
            appDatabase.close();
        }
    }

    private APRSMessage reliableMessage(String from, String to, String identifier,
                                        int state, long nextRetryAt) {
        APRSMessage message = new APRSMessage();
        message.type = APRSMessage.MESSAGE_TYPE;
        message.fromCallsign = from;
        message.toCallsign = to;
        message.messageIdentifier = identifier;
        message.msgNum = Integer.parseInt(identifier);
        message.deliveryState = state;
        message.nextRetryAt = nextRetryAt;
        return message;
    }
}
