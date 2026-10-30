"""
Spoken guidance for the Blind Navigation skill (Arabic + English).

Turns a PathAnalysis plus object detections into at most one short message per
frame, with hysteresis (no flip-flopping between left/right) and rate limiting
(urgent messages repeat quickly, routine ones rarely).
"""

from __future__ import annotations

import math
import time
from typing import Optional

# COCO class → (Arabic, English) for objects worth announcing while walking
OBJECT_NAMES = {
    "person": ("شخص", "person"),
    "bicycle": ("دراجة هوائية", "bicycle"),
    "car": ("سيارة", "car"),
    "motorcycle": ("دراجة نارية", "motorcycle"),
    "bus": ("حافلة", "bus"),
    "truck": ("شاحنة", "truck"),
    "train": ("قطار", "train"),
    "traffic light": ("إشارة مرور", "traffic light"),
    "fire hydrant": ("صنبور إطفاء", "fire hydrant"),
    "stop sign": ("إشارة قف", "stop sign"),
    "parking meter": ("عداد موقف", "parking meter"),
    "bench": ("مقعد", "bench"),
    "dog": ("كلب", "dog"),
    "cat": ("قطة", "cat"),
    "horse": ("حصان", "horse"),
    "cow": ("بقرة", "cow"),
    "sheep": ("خروف", "sheep"),
    "backpack": ("حقيبة ظهر", "backpack"),
    "umbrella": ("مظلة", "umbrella"),
    "handbag": ("حقيبة يد", "handbag"),
    "suitcase": ("حقيبة سفر", "suitcase"),
    "chair": ("كرسي", "chair"),
    "couch": ("أريكة", "couch"),
    "potted plant": ("نبتة", "potted plant"),
    "bed": ("سرير", "bed"),
    "dining table": ("طاولة", "table"),
    "toilet": ("مرحاض", "toilet"),
    "tv": ("تلفاز", "TV"),
    "refrigerator": ("ثلاجة", "refrigerator"),
    "sink": ("مغسلة", "sink"),
    "oven": ("فرن", "oven"),
}

VEHICLES = {"car", "motorcycle", "bus", "truck", "bicycle", "train"}

PRIORITY_INFO, PRIORITY_NORMAL, PRIORITY_URGENT = 1, 2, 3

MESSAGES = {
    "forward": ("الطريق أمامك سالك", "Path ahead is clear"),
    "slight_left": ("انحرف قليلاً إلى اليسار", "Bear slightly left"),
    "left": ("اتجه إلى اليسار", "Turn left"),
    "slight_right": ("انحرف قليلاً إلى اليمين", "Bear slightly right"),
    "right": ("اتجه إلى اليمين", "Turn right"),
    "slow": ("تمهّل، عائق أمامك على بعد {d}", "Slow down, obstacle ahead in {d}"),
    "stop": ("توقّف! عائق قريب جداً أمامك", "Stop! Obstacle right in front of you"),
    "caution": ("توقّف، لا أرى الأرض أمامك بوضوح", "Stop, I can't see the ground ahead"),
    "head_level": ("انتبه، عائق على مستوى الرأس على بعد {d}", "Watch out, head-level obstacle in {d}"),
    "drop": ("انتبه، قد يوجد درج نازل أو حفرة على بعد {d}", "Careful, possible step down or hole in {d}"),
    "vehicle": ("انتبه، {obj} {pos} على بعد {d}", "Careful, {obj} {pos} in {d}"),
    "object": ("{obj} {pos} على بعد {d}", "{obj} {pos} in {d}"),
}

POSITIONS = {
    "left": ("على يسارك", "on your left"),
    "ahead": ("أمامك", "ahead"),
    "right": ("على يمينك", "on your right"),
}


def distance_phrase(meters: Optional[float], lang: str = "ar") -> str:
    """Spoken distance rounded to half meters: 'متر ونصف', 'مترين', '3 أمتار'."""
    if meters is None or not math.isfinite(meters):
        return "مسافة غير معروفة" if lang == "ar" else "unknown distance"
    m = max(0.0, round(meters * 2) / 2)
    if lang != "ar":
        if m < 1:
            return "less than a meter"
        return f"{m:g} meter" + ("" if m == 1 else "s")
    if m < 1:
        return "أقل من متر"
    whole, half = int(m), (m - int(m)) >= 0.5
    if whole == 1:
        base = "متر"
    elif whole == 2:
        base = "مترين"
    elif 3 <= whole <= 10:
        base = f"{whole} أمتار"
    else:
        base = f"{whole} متر"
    return base + (" ونصف" if half else "")


def position_of(bearing_deg: float) -> str:
    if bearing_deg < -12:
        return "left"
    if bearing_deg > 12:
        return "right"
    return "ahead"


def _text(key: str, lang: str, **kw) -> str:
    ar, en = MESSAGES[key]
    return (ar if lang == "ar" else en).format(**kw)


class GuidanceEngine:
    """Decides what (if anything) to say for each analyzed frame."""

    def __init__(self, lang: str = "ar", confirm_frames: int = 2, repeat_s: float = 5.0,
                 urgent_repeat_s: float = 2.0, info_repeat_s: float = 10.0,
                 announce_distance_m: float = 4.0, vehicle_distance_m: float = 6.0):
        self.lang = lang
        self.confirm_frames = confirm_frames
        self.repeat_s = repeat_s
        self.urgent_repeat_s = urgent_repeat_s
        self.info_repeat_s = info_repeat_s
        self.announce_distance_m = announce_distance_m
        self.vehicle_distance_m = vehicle_distance_m
        self._pending_action = None
        self._pending_count = 0
        self._stable_action = None
        self._last_key = None
        self._last_time = 0.0
        self._said_at: dict[str, float] = {}

    def _stable(self, action: str) -> str:
        """Hysteresis: a new direction must persist before it replaces the old one; stops are instant."""
        if action in ("stop", "caution"):
            self._stable_action, self._pending_action, self._pending_count = action, action, self.confirm_frames
            return action
        if action == self._pending_action:
            self._pending_count += 1
        else:
            self._pending_action, self._pending_count = action, 1
        if self._stable_action is None or self._pending_count >= self.confirm_frames:
            self._stable_action = action
        return self._stable_action

    def update(self, analysis: dict, objects: list, now: Optional[float] = None) -> dict:
        """
        analysis: PathAnalysis.to_dict(); objects: [{class, distance_m, bearing_deg, ...}].
        Returns {action, key, text, priority, speak}.
        """
        now = time.monotonic() if now is None else now
        lang = self.lang
        action = self._stable(analysis.get("action", "caution"))

        candidates = []  # (priority, key, text)
        if action in ("stop", "caution"):
            candidates.append((PRIORITY_URGENT, action, _text(action, lang)))
        if analysis.get("head_level"):
            d = analysis["head_level"]["distance_m"]
            candidates.append((PRIORITY_URGENT, "head_level", _text("head_level", lang, d=distance_phrase(d, lang))))
        if analysis.get("drop"):
            d = analysis["drop"]["distance_m"]
            candidates.append((PRIORITY_URGENT, "drop", _text("drop", lang, d=distance_phrase(d, lang))))

        nearest_obj = None
        for obj in sorted((o for o in objects if o.get("distance_m") is not None), key=lambda o: o["distance_m"]):
            cls = obj.get("class")
            if cls not in OBJECT_NAMES:
                continue
            is_vehicle = cls in VEHICLES
            limit = self.vehicle_distance_m if is_vehicle else self.announce_distance_m
            if obj["distance_m"] > limit:
                continue
            name = OBJECT_NAMES[cls][0 if lang == "ar" else 1]
            pos = POSITIONS[position_of(obj.get("bearing_deg", 0.0))][0 if lang == "ar" else 1]
            kw = dict(obj=name, pos=pos, d=distance_phrase(obj["distance_m"], lang))
            if is_vehicle:
                candidates.append((PRIORITY_NORMAL + 0.5, f"vehicle:{cls}:{position_of(obj.get('bearing_deg', 0))}",
                                   _text("vehicle", lang, **kw)))
            elif nearest_obj is None:
                nearest_obj = (f"object:{cls}:{position_of(obj.get('bearing_deg', 0))}", _text("object", lang, **kw))

        if action in ("slight_left", "left", "slight_right", "right"):
            text = _text(action, lang)
            if nearest_obj:
                text = f"{nearest_obj[1]}، {text}" if lang == "ar" else f"{nearest_obj[1]}, {text}"
            candidates.append((PRIORITY_NORMAL, action, text))
        elif action == "slow":
            text = _text("slow", lang, d=distance_phrase(analysis.get("clear_distance_m"), lang))
            if nearest_obj:
                text = nearest_obj[1] + ("، " if lang == "ar" else ", ") + text
            candidates.append((PRIORITY_NORMAL, "slow", text))
        elif action == "forward":
            if nearest_obj:
                candidates.append((PRIORITY_INFO, nearest_obj[0], nearest_obj[1]))
            candidates.append((PRIORITY_INFO - 0.5, "forward", _text("forward", lang)))

        if not candidates:
            return {"action": action, "key": None, "text": "", "priority": 0, "speak": False}

        priority, key, text = max(candidates, key=lambda c: c[0])
        speak = self._should_speak(key, priority, now)
        if speak:
            self._last_key, self._last_time = key, now
            self._said_at[key] = now
        return {"action": action, "key": key, "text": text, "priority": int(math.ceil(priority)), "speak": speak}

    def _should_speak(self, key: str, priority: float, now: float) -> bool:
        last = self._said_at.get(key)
        if key == "forward":
            # Only confirm a clear path when it changes from something else
            return self._last_key not in (None, "forward") or last is None
        if priority >= PRIORITY_URGENT:
            return last is None or now - last >= self.urgent_repeat_s
        if key != self._last_key:
            # Don't interrupt an urgent warning that was just spoken
            return not (self._last_key in ("stop", "caution", "head_level", "drop")
                        and now - self._last_time < 1.0)
        interval = self.repeat_s if priority >= PRIORITY_NORMAL else self.info_repeat_s
        return now - self._last_time >= interval


def describe_locally(analysis: dict, objects: list, lang: str = "ar") -> str:
    """
    Offline summary of the surroundings (no VLM): free path ahead, what is on
    each side, hazards. Used for the "describe" request when no VLM is set up.
    """
    ar = lang == "ar"
    if not analysis.get("floor_valid"):
        return ("لا أرى الأرض أمامك بوضوح، قد يكون هناك عائق قريب جداً أو الكاميرا موجهة للأعلى."
                if ar else "I can't see the ground — something may be very close, or the camera points up.")
    parts = []
    clear = analysis.get("clear_distance_m")
    if clear is not None and clear >= 4.5:
        parts.append("الطريق أمامك سالك لمسافة 5 أمتار على الأقل" if ar else "The path ahead is clear for at least 5 meters")
    else:
        parts.append(f"أمامك مسافة خالية تقارب {distance_phrase(clear, lang)}" if ar
                     else f"About {distance_phrase(clear, lang)} of free path ahead")

    named = {"left": [], "ahead": [], "right": []}
    for obj in sorted((o for o in objects if o.get("distance_m") is not None), key=lambda o: o["distance_m"]):
        names = OBJECT_NAMES.get(obj.get("class"))
        if names and obj["distance_m"] <= 8:
            named[position_of(obj.get("bearing_deg", 0.0))].append(
                f"{names[0 if ar else 1]} {'على بعد' if ar else 'at'} {distance_phrase(obj['distance_m'], lang)}")
    sectors = analysis.get("sectors") or {}
    for side, sector in (("left", "left"), ("ahead", "center"), ("right", "right")):
        pos = POSITIONS[side][0 if ar else 1]
        if named[side]:
            items = ("، و" if ar else ", ").join(named[side][:2])
            parts.append(f"{pos} {items}" if ar else f"{pos}: {items}")
        elif sectors.get(sector) is not None and side != "ahead":
            d = distance_phrase(sectors[sector], lang)
            parts.append(f"{pos} عائق على بعد {d}" if ar else f"{pos}: an obstacle at {d}")
    if analysis.get("head_level"):
        parts.append(_text("head_level", lang, d=distance_phrase(analysis["head_level"]["distance_m"], lang)))
    if analysis.get("drop"):
        parts.append(_text("drop", lang, d=distance_phrase(analysis["drop"]["distance_m"], lang)))
    if ar:
        return "، ".join(parts) + "."
    return ". ".join(p[:1].upper() + p[1:] for p in parts) + "."
