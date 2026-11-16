"""SQLite storage for matches, recommendations and user feedback."""
import sqlite3
import time
from pathlib import Path


class Database:
    def __init__(self, path):
        self.path = str(path)
        Path(self.path).parent.mkdir(parents=True, exist_ok=True) if self.path != ":memory:" else None
        self.conn = sqlite3.connect(self.path, check_same_thread=False)  # guarded by api lock
        self._n = 0
        self.conn.row_factory = sqlite3.Row
        self._init()

    def _init(self):
        self.conn.executescript("""
        CREATE TABLE IF NOT EXISTS matches(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            played_at REAL NOT NULL,
            opponent TEXT, goals_for INTEGER DEFAULT 0, goals_against INTEGER DEFAULT 0,
            possession REAL, passes INTEGER, pass_accuracy REAL,
            shots INTEGER, shots_on_target INTEGER, formation TEXT, notes TEXT);
        CREATE TABLE IF NOT EXISTS recommendations(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            created_at REAL NOT NULL, match_id INTEGER, situation TEXT,
            action TEXT, confidence REAL, feedback INTEGER);
        """)
        self.conn.commit()

    def add_match(self, **f):
        cols = {"opponent", "goals_for", "goals_against", "possession", "passes",
                "pass_accuracy", "shots", "shots_on_target", "formation", "notes"}
        bad = set(f) - cols
        if bad:
            raise ValueError(f"Unknown fields: {bad}")
        keys = ["played_at"] + list(f)
        vals = [time.time()] + list(f.values())
        cur = self.conn.execute(
            f"INSERT INTO matches({','.join(keys)}) VALUES({','.join('?' * len(keys))})", vals)
        self.conn.commit()
        return cur.lastrowid

    def recent_matches(self, limit=10):
        return [dict(r) for r in self.conn.execute(
            "SELECT * FROM matches ORDER BY played_at DESC, id DESC LIMIT ?", (limit,))]

    def add_recommendation(self, situation, action, confidence, match_id=None):
        cur = self.conn.execute(
            "INSERT INTO recommendations(created_at, match_id, situation, action, confidence) "
            "VALUES(?,?,?,?,?)", (time.time(), match_id, situation, action, confidence))
        self._n += 1
        if self._n % 200 == 0:   # cap table size
            self.conn.execute("DELETE FROM recommendations WHERE id < "
                              "(SELECT MAX(id) FROM recommendations) - 5000")
        self.conn.commit()
        return cur.lastrowid

    def set_feedback(self, rec_id, helpful: bool):
        self.conn.execute("UPDATE recommendations SET feedback=? WHERE id=?",
                          (1 if helpful else -1, rec_id))
        self.conn.commit()

    def summary(self):
        r = self.conn.execute("""SELECT COUNT(*) n, COALESCE(SUM(goals_for),0) gf,
            COALESCE(SUM(goals_against),0) ga,
            COALESCE(SUM(goals_for>goals_against),0) w,
            COALESCE(SUM(goals_for=goals_against),0) d,
            COALESCE(SUM(goals_for<goals_against),0) l FROM matches""").fetchone()
        return dict(r)

    def close(self):
        self.conn.close()
