import json, os, sys, tempfile, threading, unittest
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python"))
from efai import api
from python_tests.test_analysis import make_frame


class T(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.d = tempfile.mkdtemp()
        api.init(cls.d)

    def test_analyze_logs_and_feedback(self):
        f, w, h = make_frame(ball=(820, 300))
        r = json.loads(api.analyze_and_log(f, w, h, json.dumps({"attack_direction": "right"})))
        self.assertTrue(r["ok"]); self.assertGreater(r["rec_id"], 0)
        api.set_feedback(r["rec_id"], True)
        row = api._db.conn.execute("SELECT feedback FROM recommendations WHERE id=?", (r["rec_id"],)).fetchone()
        self.assertEqual(row[0], 1)

    def test_noise_not_logged(self):
        f, w, h = make_frame()   # no ball
        r = json.loads(api.analyze_and_log(f, w, h, "{}"))
        self.assertEqual(r["rec_id"], -1)

    def test_matches_and_summary(self):
        api.add_match("Rival FC", 3, 1)
        api.add_match("Other", 0, 2, 7)
        m = json.loads(api.recent_matches(5))
        self.assertEqual(m[0]["opponent"], "Other"); self.assertEqual(m[0]["shots"], 7)
        s = json.loads(api.summary()); self.assertEqual((s["w"], s["l"]), (1, 1))

    def test_other_thread(self):
        out = []
        t = threading.Thread(target=lambda: out.append(api.summary())); t.start(); t.join()
        self.assertTrue(out)

    def test_formations(self):
        self.assertIn("4-3-3", json.loads(api.formations()))


if __name__ == "__main__":
    unittest.main()
