"""Single facade Kotlin talks to (via Chaquopy). All DB access is serialised by one lock."""
import json
import os
import threading

from . import tactics
from .analysis import analyze_frame
from .database import Database

_db = None
_lock = threading.Lock()
LOGGED = ("own_third", "midfield", "final_third")   # don't log "no pitch"/"no ball" noise


def init(data_dir):
    global _db
    with _lock:
        if _db is None:
            _db = Database(os.path.join(str(data_dir), "efai.sqlite3"))
    return True


def _need():
    if _db is None:
        raise RuntimeError("api.init() was not called")
    return _db


def analyze_and_log(frame, width, height, settings_json="{}"):
    """Runs analysis; logs meaningful results; returns result JSON with 'rec_id' (-1 if not logged)."""
    out = json.loads(analyze_frame(frame, width, height, settings_json))
    out["rec_id"] = -1
    if out.get("ok") and out.get("situation") in LOGGED:
        try:
            with _lock:
                out["rec_id"] = _need().add_recommendation(
                    out["situation"], out.get("passing", ""), float(out.get("confidence", 0.0)))
        except Exception as e:   # logging must never break analysis
            out.setdefault("notes", []).append(f"log failed: {e}")
    return json.dumps(out)


def set_feedback(rec_id, helpful):
    with _lock:
        _need().set_feedback(int(rec_id), bool(helpful))
    return True


def add_match(opponent, goals_for, goals_against, shots=-1):
    f = dict(opponent=str(opponent), goals_for=int(goals_for), goals_against=int(goals_against))
    if int(shots) >= 0:
        f["shots"] = int(shots)
    with _lock:
        return _need().add_match(**f)


def recent_matches(limit=10):
    with _lock:
        return json.dumps(_need().recent_matches(int(limit)))


def summary():
    with _lock:
        return json.dumps(_need().summary())


def formations():
    return json.dumps(tactics.FORMATIONS)
