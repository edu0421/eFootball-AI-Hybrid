"""Frame analysis for eFootball AI Hybrid.

Entry point for Kotlin (via Chaquopy):
    analyze_frame(frame, width, height, settings_json) -> JSON string

`frame` is tightly packed RGBA_8888 (width*height*4 bytes).

Everything returned is derived from pixels. What this module CAN detect:
  * whether a green pitch fills a meaningful part of the screen,
  * the pitch's bounding box,
  * candidate players (non-green blobs of plausible size inside the pitch),
  * a ball *candidate* (small, bright, round blob inside the pitch).
What it CANNOT know: who has possession, which team is yours, the score.
Recommendations are therefore phrased conditionally and confidence is lowered
whenever detection is weak. HUD overlays can create false player blobs;
`hud_top_margin` / `hud_bottom_margin` exclude screen bands.
"""
import json
import time

import cv2
import numpy as np

DEFAULTS = {
    "analysis_width": 480,       # frame is downscaled to this width before analysis
    "attack_direction": "right",  # "right" or "left": side of the screen you attack towards
    "hud_top_margin": 0.10,      # fraction of height ignored at top (score bar)
    "hud_bottom_margin": 0.0,
    "min_pitch_ratio": 0.15,     # min fraction of frame that must be pitch-green
    "ignore_rects": [],          # [[x0,y0,x1,y1],...] normalised 0-1, e.g. our own floating panel
}

GREEN_LO = np.array([33, 50, 35], np.uint8)
GREEN_HI = np.array([88, 255, 255], np.uint8)


def _settings(settings_json):
    s = dict(DEFAULTS)
    try:
        s.update(json.loads(settings_json or "{}"))
    except (ValueError, TypeError):
        pass
    return s


def _to_rgb(frame, width, height, target_w):
    arr = np.frombuffer(frame, dtype=np.uint8)  # bytes or Java byte[] (buffer protocol)
    if arr.size != width * height * 4:
        raise ValueError(f"frame size {arr.size} != {width}x{height}x4")
    rgb = arr.reshape(height, width, 4)[:, :, :3]
    if width > target_w:
        h = max(1, int(height * target_w / width))
        rgb = cv2.resize(rgb, (target_w, h), interpolation=cv2.INTER_AREA)
    return np.ascontiguousarray(rgb)


def detect_pitch(hsv):
    """Returns (ratio, hull_mask, bbox) or (ratio, None, None)."""
    green = cv2.inRange(hsv, GREEN_LO, GREEN_HI)
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7))
    green = cv2.morphologyEx(green, cv2.MORPH_CLOSE, k)
    green = cv2.morphologyEx(green, cv2.MORPH_OPEN, k)
    ratio = float(np.count_nonzero(green)) / green.size
    cnts, _ = cv2.findContours(green, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cnts:
        return ratio, None, None
    big = max(cnts, key=cv2.contourArea)
    hull = cv2.convexHull(big)
    mask = np.zeros(green.shape, np.uint8)
    cv2.drawContours(mask, [hull], -1, 255, -1)
    return ratio, mask, cv2.boundingRect(hull)


def _blank(mask, rects):
    h, w = mask.shape
    for r in rects or []:
        try:
            x0, y0, x1, y1 = r
            mask[max(0, int(y0 * h)):int(y1 * h) + 1, max(0, int(x0 * w)):int(x1 * w) + 1] = 0
        except (TypeError, ValueError):
            continue


def detect_players(rgb, hsv, pitch_mask, hud_top, hud_bottom, ignore=None):
    h, w = pitch_mask.shape
    green = cv2.inRange(hsv, GREEN_LO, GREEN_HI)
    non_green = cv2.bitwise_and(cv2.bitwise_not(green), pitch_mask)
    # drop thin pitch lines / noise, keep compact bodies
    non_green = cv2.morphologyEx(non_green, cv2.MORPH_OPEN,
                                 cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3)))
    non_green[: int(h * hud_top), :] = 0
    _blank(non_green, ignore)
    if hud_bottom > 0:
        non_green[int(h * (1 - hud_bottom)):, :] = 0
    n, _, stats, cents = cv2.connectedComponentsWithStats(non_green, connectivity=8)
    frame_area = float(h * w)
    players = []
    for i in range(1, n):
        x, y, bw, bh, area = stats[i]
        frac = area / frame_area
        if not (0.00025 <= frac <= 0.012):
            continue
        aspect = bh / float(max(bw, 1))
        if not (0.6 <= aspect <= 4.5):
            continue
        players.append((float(cents[i][0]), float(cents[i][1])))
    return players


def detect_ball(hsv, pitch_mask, hud_top, ignore=None):
    h, w = pitch_mask.shape
    bright = cv2.inRange(hsv, np.array([0, 0, 200], np.uint8), np.array([179, 60, 255], np.uint8))
    bright = cv2.bitwise_and(bright, pitch_mask)
    bright[: int(h * hud_top), :] = 0
    _blank(bright, ignore)
    bright = cv2.morphologyEx(bright, cv2.MORPH_OPEN,
                              cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3)))
    cnts, _ = cv2.findContours(bright, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    best, best_score = None, 0.0
    frame_area = float(h * w)
    cands = 0
    for c in cnts:
        area = cv2.contourArea(c)
        if not (0.00004 <= area / frame_area <= 0.0015):
            continue
        per = cv2.arcLength(c, True)
        if per <= 0:
            continue
        circ = 4 * np.pi * area / (per * per)
        if circ < 0.65:
            continue
        cands += 1
        if circ > best_score:
            m = cv2.moments(c)
            if m["m00"] > 0:
                best, best_score = (m["m10"] / m["m00"], m["m01"] / m["m00"]), circ
    if best is None:
        return None, 0.0
    # ambiguity (many round bright blobs, e.g. white kits) lowers confidence
    conf = min(0.7, best_score * 0.8) / (1 + 0.5 * (cands - 1))
    return best, float(conf)


RECS = {
    "own_third": ("Build up safely: short passes to the nearest open teammate; avoid risky balls through the middle.",
                  "If you have the ball: keep possession and draw pressure before progressing.",
                  "If defending: hold shape and keep players between the ball and your goal."),
    "midfield": ("Look for a progressive pass into space; switch play if the ball side is crowded.",
                 "If you have the ball: increase tempo only when a runner has space ahead.",
                 "If defending: press the ball carrier and cover passing lanes forward."),
    "final_third": ("Seek a quick one-two or cut-back; avoid forcing passes into crowded areas.",
                    "If you have the ball: shoot when you have a clear lane, otherwise recycle wide.",
                    "If defending: drop and block shooting lanes; the box is the priority."),
}
CROWD_NOTE = " Area around the ball looks congested."
OPEN_NOTE = " Space around the ball looks open."


def classify(ball, bbox, players, direction):
    x0, y0, bw, bh = bbox
    bx, by = ball
    t = (bx - x0) / float(max(bw, 1))
    t = min(max(t, 0.0), 1.0)
    if direction == "left":
        t = 1.0 - t
    zone = "own_third" if t < 1 / 3 else ("midfield" if t < 2 / 3 else "final_third")
    radius = 0.12 * bw
    near = sum(1 for (px, py) in players if (px - bx) ** 2 + (py - by) ** 2 <= radius ** 2)
    return zone, near


def analyze_frame(frame, width, height, settings_json="{}"):
    t0 = time.time()
    try:
        s = _settings(settings_json)
        rgb = _to_rgb(frame, int(width), int(height), int(s["analysis_width"]))
        hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV)
        ratio, mask, bbox = detect_pitch(hsv)
        out = {"ok": True, "pitch_ratio": round(ratio, 3), "players_detected": 0,
               "ball": None, "notes": []}
        if mask is None or ratio < s["min_pitch_ratio"]:
            out.update(situation="not_in_match", confidence=0.0,
                       passing="No pitch detected.", attacking="", defensive="",
                       notes=["Open a match view; recommendations are paused."])
        else:
            players = detect_players(rgb, hsv, mask, s["hud_top_margin"], s["hud_bottom_margin"],
                                     s["ignore_rects"])
            ball, ball_conf = detect_ball(hsv, mask, s["hud_top_margin"], s["ignore_rects"])
            out["players_detected"] = len(players)
            pitch_conf = min(1.0, ratio / 0.5)
            players_conf = min(1.0, len(players) / 10.0)
            if ball is None:
                out.update(situation="pitch_visible_ball_unknown",
                           confidence=round(0.3 * pitch_conf + 0.2 * players_conf, 2),
                           passing="Ball not located; no pass advice.",
                           attacking="", defensive="")
                out["notes"].append("Ball not detected in this frame.")
            else:
                zone, near = classify(ball, bbox, players, s["attack_direction"])
                p, a, d = RECS[zone]
                note = CROWD_NOTE if near >= 4 else OPEN_NOTE
                conf = 0.45 * pitch_conf + 0.35 * ball_conf / 0.7 + 0.2 * players_conf
                out.update(situation=zone, passing=p + note, attacking=a, defensive=d,
                           confidence=round(min(conf, 0.85), 2))
                out["ball"] = {"x": round(ball[0] / rgb.shape[1], 3),
                               "y": round(ball[1] / rgb.shape[0], 3),
                               "confidence": round(ball_conf, 2)}
                out["notes"].append("Possession unknown: advice is conditional.")
        out["processing_ms"] = int((time.time() - t0) * 1000)
        return json.dumps(out)
    except Exception as e:  # never let an exception cross into Kotlin as a crash
        return json.dumps({"ok": False, "error": f"{type(e).__name__}: {e}",
                           "processing_ms": int((time.time() - t0) * 1000)})
