package com.example.walkpromote22.data.database;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import android.content.Context;

import com.example.walkpromote22.data.dao.LocationDao;
import com.example.walkpromote22.data.dao.PathDao;
import com.example.walkpromote22.data.dao.PathPointDao;
import com.example.walkpromote22.data.dao.RouteDao;
import com.example.walkpromote22.data.dao.StepDao;
import com.example.walkpromote22.data.dao.UserDao;
import com.example.walkpromote22.data.model.Location;
import com.example.walkpromote22.data.model.Path;
import com.example.walkpromote22.data.model.PathPoint;
import com.example.walkpromote22.data.model.Step;
import com.example.walkpromote22.data.model.User;
import com.example.walkpromote22.data.model.Route;


import android.database.Cursor;

@Database(entities = {
        User.class,
        Step.class,
        Path.class,
        PathPoint.class,
        Location.class,
        Route.class
}, version = 18, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    public abstract UserDao userDao();
    public abstract StepDao stepDao();
    public abstract PathDao pathDao();
    public abstract PathPointDao pathPointDao();
    public abstract LocationDao locationDao();
    public abstract RouteDao routeDao();

    public static volatile AppDatabase INSTANCE;

    public static AppDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, "running_database")
                            .fallbackToDestructiveMigration() // ← 关键代码
                            .build();
                }
            }
        }
        return INSTANCE;
    }

    // ✅ 将每个迁移类提取为静态内部类（不再用匿名类）

    public static class Migration_11_12 extends Migration {
        public Migration_11_12() {
            super(11, 12);
        }

        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("DELETE FROM paths");
            database.execSQL("ALTER TABLE paths ADD COLUMN distance REAL DEFAULT 0 NOT NULL");
            database.execSQL("ALTER TABLE paths ADD COLUMN calories REAL DEFAULT 0 NOT NULL");
            database.execSQL("ALTER TABLE paths ADD COLUMN averageSpeed REAL DEFAULT 0 NOT NULL");
        }
    }

    public static class Migration_12_13 extends Migration {
        public Migration_12_13() {
            super(12, 13);
        }

        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE locations_new (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "routeId INTEGER, " +
                    "name TEXT NOT NULL, " +
                    "latitude REAL NOT NULL, " +
                    "longitude REAL NOT NULL, " +
                    "footTraffic INTEGER NOT NULL DEFAULT 1, " +
                    "scenery INTEGER NOT NULL DEFAULT 1, " +
                    "vehicleTraffic INTEGER NOT NULL DEFAULT 1, " +
                    "FOREIGN KEY(routeId) REFERENCES routes(id) ON DELETE CASCADE)");

            database.execSQL("INSERT INTO locations_new (id, routeId, name, latitude, longitude) " +
                    "SELECT id, routeId, name, latitude, longitude FROM locations");

            database.execSQL("DROP TABLE locations");
            database.execSQL("ALTER TABLE locations_new RENAME TO locations");
            database.execSQL("CREATE INDEX index_locations_routeId ON locations(routeId)");
        }
    }

    public static class Migration_13_14 extends Migration {
        public Migration_13_14() {
            super(13, 14);
        }

        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE locations_new (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "routeId INTEGER, " +
                    "name TEXT NOT NULL, " +
                    "latitude REAL NOT NULL, " +
                    "longitude REAL NOT NULL, " +
                    "features INTEGER NOT NULL DEFAULT 11111, " +
                    "FOREIGN KEY(routeId) REFERENCES routes(id) ON DELETE CASCADE)");

            Cursor cursor = database.query("SELECT id, routeId, name, latitude, longitude FROM locations");

            while (cursor.moveToNext()) {
                int id = cursor.getInt(0);
                Integer routeId = cursor.isNull(1) ? null : cursor.getInt(1);
                String name = cursor.getString(2);
                double latitude = cursor.getDouble(3);
                double longitude = cursor.getDouble(4);

                int s = 1 + (int)(Math.random() * 9);
                int f = 1 + (int)(Math.random() * 9);
                int r = 1 + (int)(Math.random() * 9);
                int sh = 1 + (int)(Math.random() * 9);
                int p = 1 + (int)(Math.random() * 9);
                int features = s * 10000 + f * 1000 + r * 100 + sh * 10 + p;

                database.execSQL("INSERT INTO locations_new (id, routeId, name, latitude, longitude, features) VALUES (?, ?, ?, ?, ?, ?)",
                        new Object[]{id, routeId, name, latitude, longitude, features});
            }
            cursor.close();

            database.execSQL("DROP TABLE locations");
            database.execSQL("ALTER TABLE locations_new RENAME TO locations");
            database.execSQL("CREATE INDEX index_locations_routeId ON locations(routeId)");
        }
    }
}

