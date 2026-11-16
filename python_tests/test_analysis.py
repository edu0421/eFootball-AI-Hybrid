import json, os, sys, unittest
import cv2, numpy as np
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python"))
from efai.analysis import analyze_frame


def make_frame(w=960, h=540, ball=None, players=True):
    img = np.zeros((h, w, 3), np.uint8)
    img[:] = (30, 140, 40)                                  # pitch green (RGB)
    cv2.rectangle(img, (0, 0), (w, int(h * 0.08)), (20, 20, 20), -1)   # HUD bar
    cv2.line(img, (w // 2, 60), (w // 2, h - 20), (240, 240, 240), 2)  # pitch line
    if players:
        for i, (x, y) in enumerate([(300, 250), (340, 300), (700, 200), (720, 330),
                                    (500, 400), (560, 150), (200, 400), (850, 280)]):
            col = (200, 30, 30) if i % 2 else (30, 30, 200)
            cv2.rectangle(img, (x, y), (x + 14, y + 32), col, -1)
    if ball:
        cv2.circle(img, ball, 6, (250, 250, 250), -1)
    rgba = cv2.cvtColor(img, cv2.COLOR_RGB2RGBA)
    return rgba.tobytes(), w, h


class T(unittest.TestCase):
    def run_it(self, frame, **s):
        f, w, h = frame
        return json.loads(analyze_frame(f, w, h, json.dumps(s)))

    def test_ball_right_attacking(self):
        r = self.run_it(make_frame(ball=(820, 300)), attack_direction="right")
        self.assertTrue(r["ok"]); self.assertEqual(r["situation"], "final_third")
        self.assertIsNotNone(r["ball"]); self.assertGreaterEqual(r["players_detected"], 6)

    def test_direction_flips_zone(self):
        r = self.run_it(make_frame(ball=(820, 300)), attack_direction="left")
        self.assertEqual(r["situation"], "own_third")

    def test_ignore_rect_hides_ball(self):
        r = self.run_it(make_frame(ball=(820, 300)), ignore_rects=[[0.80, 0.50, 0.92, 0.62]])
        self.assertIsNone(r["ball"])

    def test_no_ball(self):
        r = self.run_it(make_frame())
        self.assertEqual(r["situation"], "pitch_visible_ball_unknown")
        self.assertIsNone(r["ball"])

    def test_no_pitch(self):
        img = np.full((540, 960, 4), 40, np.uint8)
        r = self.run_it((img.tobytes(), 960, 540))
        self.assertEqual(r["situation"], "not_in_match"); self.assertEqual(r["confidence"], 0.0)

    def test_bad_input_returns_error(self):
        r = json.loads(analyze_frame(b"abc", 960, 540, "{}"))
        self.assertFalse(r["ok"])

if __name__ == "__main__":
    unittest.main()
