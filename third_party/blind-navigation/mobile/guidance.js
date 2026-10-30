// Spoken guidance — JavaScript port of ../scripts/guidance.py (keep in sync).
// One short Arabic/English sentence per frame, with hysteresis and rate limits.

export const OBJECT_NAMES = {
  person: ['شخص', 'person'], bicycle: ['دراجة هوائية', 'bicycle'], car: ['سيارة', 'car'],
  motorcycle: ['دراجة نارية', 'motorcycle'], bus: ['حافلة', 'bus'], truck: ['شاحنة', 'truck'],
  train: ['قطار', 'train'], 'traffic light': ['إشارة مرور', 'traffic light'],
  'fire hydrant': ['صنبور إطفاء', 'fire hydrant'], 'stop sign': ['إشارة قف', 'stop sign'],
  'parking meter': ['عداد موقف', 'parking meter'], bench: ['مقعد', 'bench'], dog: ['كلب', 'dog'],
  cat: ['قطة', 'cat'], horse: ['حصان', 'horse'], cow: ['بقرة', 'cow'], sheep: ['خروف', 'sheep'],
  backpack: ['حقيبة ظهر', 'backpack'], umbrella: ['مظلة', 'umbrella'], handbag: ['حقيبة يد', 'handbag'],
  suitcase: ['حقيبة سفر', 'suitcase'], chair: ['كرسي', 'chair'], couch: ['أريكة', 'couch'],
  'potted plant': ['نبتة', 'potted plant'], bed: ['سرير', 'bed'], 'dining table': ['طاولة', 'table'],
  toilet: ['مرحاض', 'toilet'], tv: ['تلفاز', 'TV'], refrigerator: ['ثلاجة', 'refrigerator'],
  sink: ['مغسلة', 'sink'], oven: ['فرن', 'oven'],
};
const VEHICLES = new Set(['car', 'motorcycle', 'bus', 'truck', 'bicycle', 'train']);
const INFO = 1, NORMAL = 2, URGENT = 3;

const MESSAGES = {
  forward: ['الطريق أمامك سالك', 'Path ahead is clear'],
  slight_left: ['انحرف قليلاً إلى اليسار', 'Bear slightly left'],
  left: ['اتجه إلى اليسار', 'Turn left'],
  slight_right: ['انحرف قليلاً إلى اليمين', 'Bear slightly right'],
  right: ['اتجه إلى اليمين', 'Turn right'],
  slow: ['تمهّل، عائق أمامك على بعد {d}', 'Slow down, obstacle ahead in {d}'],
  stop: ['توقّف! عائق قريب جداً أمامك', 'Stop! Obstacle right in front of you'],
  caution: ['توقّف، لا أرى الأرض أمامك بوضوح', "Stop, I can't see the ground ahead"],
  head_level: ['انتبه، عائق على مستوى الرأس على بعد {d}', 'Watch out, head-level obstacle in {d}'],
  drop: ['انتبه، قد يوجد درج نازل أو حفرة على بعد {d}', 'Careful, possible step down or hole in {d}'],
  vehicle: ['انتبه، {obj} {pos} على بعد {d}', 'Careful, {obj} {pos} in {d}'],
  object: ['{obj} {pos} على بعد {d}', '{obj} {pos} in {d}'],
};
const POSITIONS = { left: ['على يسارك', 'on your left'], ahead: ['أمامك', 'ahead'], right: ['على يمينك', 'on your right'] };

const li = (lang) => (lang === 'ar' ? 0 : 1);
const text = (key, lang, kw = {}) => MESSAGES[key][li(lang)].replace(/\{(\w+)\}/g, (_, k) => kw[k]);

export function distancePhrase(m, lang = 'ar') {
  if (m == null || !Number.isFinite(m)) return lang === 'ar' ? 'مسافة غير معروفة' : 'unknown distance';
  m = Math.max(0, Math.round(m * 2) / 2);
  if (lang !== 'ar') return m < 1 ? 'less than a meter' : `${m} meter${m === 1 ? '' : 's'}`;
  if (m < 1) return 'أقل من متر';
  const whole = Math.floor(m), half = m - whole >= 0.5;
  const base = whole === 1 ? 'متر' : whole === 2 ? 'مترين' : whole <= 10 ? `${whole} أمتار` : `${whole} متر`;
  return base + (half ? ' ونصف' : '');
}

export const positionOf = (b) => (b < -12 ? 'left' : b > 12 ? 'right' : 'ahead');

export class GuidanceEngine {
  constructor({ lang = 'ar', confirmFrames = 2, repeatS = 5, urgentRepeatS = 2, infoRepeatS = 10,
    announceDistanceM = 4, vehicleDistanceM = 6 } = {}) {
    Object.assign(this, { lang, confirmFrames, repeatS, urgentRepeatS, infoRepeatS, announceDistanceM, vehicleDistanceM });
    this._pending = null; this._count = 0; this._stable = null;
    this._lastKey = null; this._lastTime = 0; this._saidAt = new Map();
  }

  _stabilize(action) {
    if (action === 'stop' || action === 'caution') {
      this._stable = this._pending = action; this._count = this.confirmFrames;
      return action;
    }
    if (action === this._pending) this._count++; else { this._pending = action; this._count = 1; }
    if (this._stable == null || this._count >= this.confirmFrames) this._stable = action;
    return this._stable;
  }

  /**
   * analysis: PathPlanner.analyze() result; objects: [{class, distanceM, bearingDeg}]; now: seconds.
   * Returns {action, key, text, priority, speak}.
   */
  update(analysis, objects = [], now = performance.now() / 1000) {
    const lang = this.lang;
    const action = this._stabilize(analysis.action || 'caution');
    const cands = []; // [priority, key, text]
    if (action === 'stop' || action === 'caution') cands.push([URGENT, action, text(action, lang)]);
    if (analysis.headLevel) cands.push([URGENT, 'head_level', text('head_level', lang, { d: distancePhrase(analysis.headLevel.distanceM, lang) })]);
    if (analysis.drop) cands.push([URGENT, 'drop', text('drop', lang, { d: distancePhrase(analysis.drop.distanceM, lang) })]);

    let nearestObj = null;
    const sorted = objects.filter((o) => o.distanceM != null).sort((p, q) => p.distanceM - q.distanceM);
    for (const o of sorted) {
      const names = OBJECT_NAMES[o.class];
      if (!names) continue;
      const isVehicle = VEHICLES.has(o.class);
      if (o.distanceM > (isVehicle ? this.vehicleDistanceM : this.announceDistanceM)) continue;
      const pos = positionOf(o.bearingDeg || 0);
      const kw = { obj: names[li(lang)], pos: POSITIONS[pos][li(lang)], d: distancePhrase(o.distanceM, lang) };
      if (isVehicle) cands.push([NORMAL + 0.5, `vehicle:${o.class}:${pos}`, text('vehicle', lang, kw)]);
      else if (!nearestObj) nearestObj = [`object:${o.class}:${pos}`, text('object', lang, kw)];
    }

    const sep = lang === 'ar' ? '، ' : ', ';
    if (['slight_left', 'left', 'slight_right', 'right'].includes(action)) {
      cands.push([NORMAL, action, (nearestObj ? nearestObj[1] + sep : '') + text(action, lang)]);
    } else if (action === 'slow') {
      cands.push([NORMAL, 'slow', (nearestObj ? nearestObj[1] + sep : '') + text('slow', lang, { d: distancePhrase(analysis.clearDistanceM, lang) })]);
    } else if (action === 'forward') {
      if (nearestObj) cands.push([INFO, nearestObj[0], nearestObj[1]]);
      cands.push([INFO - 0.5, 'forward', text('forward', lang)]);
    }
    if (!cands.length) return { action, key: null, text: '', priority: 0, speak: false };

    const [priority, key, msg] = cands.reduce((p, c) => (c[0] > p[0] ? c : p));
    const speak = this._shouldSpeak(key, priority, now);
    if (speak) { this._lastKey = key; this._lastTime = now; this._saidAt.set(key, now); }
    return { action, key, text: msg, priority: Math.ceil(priority), speak };
  }

  _shouldSpeak(key, priority, now) {
    const last = this._saidAt.get(key);
    if (key === 'forward') return !(this._lastKey == null || this._lastKey === 'forward') || last === undefined;
    if (priority >= URGENT) return last === undefined || now - last >= this.urgentRepeatS;
    if (key !== this._lastKey) {
      return !(['stop', 'caution', 'head_level', 'drop'].includes(this._lastKey) && now - this._lastTime < 1.0);
    }
    return now - this._lastTime >= (priority >= NORMAL ? this.repeatS : this.infoRepeatS);
  }
}
