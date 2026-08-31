#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""OpenCV fracture analysis for teaching lab platform.

Reads JSON from stdin:
  { "imageBase64": "data:image/...;base64,..." | "<raw base64>", "points": [{"d":..,"f":..}, ...] }

Writes JSON to stdout:
  fractureType, fractureConf, geometry, steps[], curveInsight, elapsedMs, error?
"""
from __future__ import annotations

import base64
import json
import math
import sys
import time

# Force UTF-8 JSON on Windows consoles
try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stdin.reconfigure(encoding="utf-8")
except Exception:
    pass


def fail(msg: str, code: int = 1) -> None:
    sys.stdout.write(json.dumps({"error": msg}, ensure_ascii=False))
    sys.exit(code)


try:
    import cv2
    import numpy as np
except Exception as e:  # pragma: no cover
    fail("未安装 OpenCV/numpy，请执行: pip install -r requirements-fracture.txt (%s)" % e)


def decode_image(image_b64: str):
    raw = image_b64 or ""
    if "," in raw and raw.strip().startswith("data:"):
        raw = raw.split(",", 1)[1]
    raw = raw.strip()
    if not raw:
        fail("缺少断口图像")
    try:
        buf = base64.b64decode(raw)
    except Exception:
        fail("图像 Base64 解码失败")
    arr = np.frombuffer(buf, dtype=np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if img is None:
        fail("无法解码图像，请上传常见格式（jpg/png）")
    # Limit size for speed / payload
    h, w = img.shape[:2]
    max_side = 900
    if max(h, w) > max_side:
        scale = max_side / float(max(h, w))
        img = cv2.resize(img, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_AREA)
    return img


def encode_b64(img, is_gray: bool = False) -> str:
    if is_gray and len(img.shape) == 2:
        out = img
    elif is_gray:
        out = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    else:
        out = img
    ok, buf = cv2.imencode(".jpg", out, [int(cv2.IMWRITE_JPEG_QUALITY), 82])
    if not ok:
        return ""
    return "data:image/jpeg;base64," + base64.b64encode(buf.tobytes()).decode("ascii")


def curve_features(points):
    """Curve ductility cues from force–displacement.

    Returns:
      drop_ratio: 0~1, higher = steeper end collapse (brittle-like)
      ductility: 0~1, higher = more ductile response (elongation + mild drop)
      meta: summary for UI / insight
    """
    if not points or len(points) < 4:
        return 0.0, 0.0, None
    try:
        fs = [float(p.get("f", 0)) for p in points]
        ds = [float(p.get("d", 0)) for p in points]
    except Exception:
        return 0.0, 0.0, None
    fmax = max(fs) if fs else 0.0
    if fmax <= 1e-6:
        return 0.0, 0.0, None
    n = len(fs)
    tail = fs[max(0, n - max(5, n // 8)) :]
    f_end = tail[-1]
    drop = max(0.0, (fmax - f_end) / fmax)
    dmax = max(ds) if ds else 0.0
    dmin = min(ds) if ds else 0.0
    span = max(dmax - dmin, 1e-6)
    cut = dmax - 0.15 * span
    seg = [(d, f) for d, f in zip(ds, fs) if d >= cut]
    steep = 0.0
    if len(seg) >= 2:
        d0, f0 = seg[0]
        d1, f1 = seg[-1]
        dd = max(d1 - d0, 1e-6)
        steep = max(0.0, (f0 - f1) / fmax / dd)
        steep = min(1.0, steep / 2.0)
    drop_ratio = min(1.0, 0.55 * drop + 0.45 * steep)
    # Teaching elongation proxy: longer displacement before collapse → more ductile
    elong = min(1.0, dmax / 16.0)
    ductility = float(min(1.0, max(0.0, 0.55 * elong + 0.45 * (1.0 - drop_ratio))))
    return drop_ratio, ductility, {
        "fMax": round(fmax, 3),
        "fEnd": round(f_end, 3),
        "drop": round(drop, 3),
        "dMax": round(dmax, 3),
        "ductility": round(ductility, 3),
    }


def surface_stats(gray, mask, geometry=None):
    """Macro morphology proxies inside fracture mask."""
    if mask is None or int(np.count_nonzero(mask)) < 200:
        return None
    geometry = geometry or {}
    gray_f = gray.astype(np.float32)
    blur_m = cv2.GaussianBlur(gray_f, (0, 0), 2.0)
    local_var = cv2.GaussianBlur((gray_f - blur_m) ** 2, (9, 9), 0)
    roi_v = local_var[mask > 0]
    roi_g = gray_f[mask > 0]
    mean_g = float(np.mean(roi_g)) + 1e-3
    rough = float(np.mean(roi_v)) / (mean_g * mean_g)
    # Flatness: high when local variation is low (brittle flat face)
    flatness = float(1.0 / (1.0 + 55.0 * rough))

    ys, xs = np.where(mask > 0)
    cy, cx = float(np.mean(ys)), float(np.mean(xs))
    yy, xx = np.ogrid[: gray.shape[0], : gray.shape[1]]
    dist = np.sqrt((yy - cy) ** 2 + (xx - cx) ** 2)
    max_d = float(np.percentile(dist[mask > 0], 90)) + 1e-6
    core = (mask > 0) & (dist <= 0.35 * max_d)
    ring = (mask > 0) & (dist >= 0.55 * max_d)
    center_dark = 0.0
    if int(np.count_nonzero(core)) > 30 and int(np.count_nonzero(ring)) > 30:
        center_dark = (
            float(np.mean(gray_f[ring])) - float(np.mean(gray_f[core]))
        ) / (float(np.std(roi_g)) + 1e-6)

    solidity = float(geometry.get("solidity") or 0)
    roundness = float(geometry.get("roundness") or 0)
    # Shear-lip proxy: irregular rim (low solidity) + mild shape distortion
    shear = 0.0
    if solidity > 0:
        shear = max(0.0, min(1.0, (0.94 - solidity) / 0.16))
        if roundness > 0 and roundness < 0.55:
            shear = min(1.0, shear + 0.15)
    return {
        "roughness": round(rough, 5),
        "flatness": round(flatness, 3),
        "shearLip": round(shear, 3),
        "grayMean": round(mean_g, 2),
        "grayStd": round(float(np.std(roi_g)), 2),
        "centerDarkGap": round(center_dark, 3),
    }


def classify(geometry, edge_density, drop_ratio, ductility=0.0, zone_probe=None, surf=None):
    """Macroscopic fracture cues used in teaching labs.

    Five evidence channels (neutral bands score neither side):
      1) 杯锥分区/纤维区
      2) 剪切唇迹象
      3) 断面平整度
      4) 曲线延性
      5) 断面纹理密度
    """
    zone_probe = zone_probe or {}
    surf = surf or {}
    flatness = float(surf.get("flatness") or 0)
    shear = float(surf.get("shearLip") or 0)
    center_gap = float(surf.get("centerDarkGap") or 0)
    ductility = float(ductility or 0)

    score_ductile = 0.0
    score_brittle = 0.0
    cues = []
    macro = {}

    # --- 1) Cup-cone / fibrous zone ---
    z_method = zone_probe.get("method") or ""
    z_score = float((zone_probe.get("fibrous") or {}).get("score") or 0)
    z_ratio = float((zone_probe.get("areaRatio") or {}).get("fibrousToMask") or 0)
    has_fibrous = (
        z_method == "darkness+local-variance"
        and z_score >= 0.72
        and 0.04 <= z_ratio <= 0.40
    )
    if has_fibrous and center_gap >= 0.18:
        w = 0.24
        score_ductile += w
        level = "明显"
        favor = "ductile"
        note = "检出中心偏暗纤维区，符合杯锥状塑性断口分区"
    elif has_fibrous:
        w = 0.14
        score_ductile += w
        level = "疑似"
        favor = "ductile"
        note = "疑似纤维区，亮度对比一般，中等支持塑性"
    elif z_method == "centroid-prior-fallback":
        w = 0.16
        score_brittle += w
        level = "未检出"
        favor = "brittle"
        note = "未稳定检出纤维区，更符合无明显杯锥分区的脆性断口"
    else:
        w = 0
        level = "不明确" if zone_probe else "无掩膜"
        favor = "neutral"
        note = "杯锥/纤维区证据不明确，本项不计分" if zone_probe else "断口掩膜不足，未做分区探测"
    cues.append({
        "name": "杯锥分区/纤维区",
        "value": level if w == 0 else round(z_score, 3),
        "favor": favor,
        "weight": w,
        "note": note,
    })
    macro["cupCone"] = {"level": level, "score": round(z_score, 3), "ratio": round(z_ratio, 3)}

    # --- 2) Shear lip ---
    if shear >= 0.55:
        w = 0.20
        score_ductile += w
        favor = "ductile"
        note = "轮廓相对凸包不充实，外缘不规则，符合剪切唇迹象"
        level = round(shear, 3)
    elif shear <= 0.20 and float(geometry.get("solidity") or 0) >= 0.92:
        w = 0.16
        score_brittle += w
        favor = "brittle"
        note = "外缘齐整、剪切唇迹象弱，符合脆性平断口"
        level = round(shear, 3)
    else:
        w = 0
        favor = "neutral"
        note = "剪切唇迹象不显著，本项不计分"
        level = round(shear, 3)
    cues.append({
        "name": "剪切唇迹象",
        "value": level,
        "favor": favor,
        "weight": w,
        "note": note,
    })
    macro["shearLip"] = {"index": round(shear, 3), "solidity": geometry.get("solidity")}

    # --- 3) Surface flatness ---
    if flatness >= 0.72:
        w = 0.18
        score_brittle += w
        favor = "brittle"
        note = "断面局部起伏小，宏观偏平整，倾向脆性"
    elif flatness > 0 and flatness <= 0.42:
        w = 0.16
        score_ductile += w
        favor = "ductile"
        note = "断面起伏较大，纤维/韧窝类粗糙形貌更常见"
    else:
        w = 0
        favor = "neutral"
        note = "断面平整度居中，本项不计分"
    cues.append({
        "name": "断面平整度",
        "value": round(flatness, 3),
        "favor": favor,
        "weight": w,
        "note": note,
    })
    macro["flatness"] = round(flatness, 3)

    # --- 4) Curve ductility (sim today, real DAQ later) ---
    if ductility <= 0 and drop_ratio <= 0:
        w = 0
        favor = "neutral"
        note = "暂无有效曲线，本项不计分"
        val = 0
    elif drop_ratio >= 0.55 or ductility <= 0.35:
        w = 0.22
        score_brittle += w
        favor = "brittle"
        note = "曲线延性偏低或末端跌落陡，符合脆性失稳"
        val = round(ductility if ductility > 0 else (1.0 - drop_ratio), 3)
    elif ductility >= 0.55 or (0 < drop_ratio < 0.38):
        w = 0.22
        score_ductile += w
        favor = "ductile"
        note = "曲线延性较好、末端相对平缓，倾向塑性"
        val = round(ductility, 3)
    else:
        w = 0
        favor = "neutral"
        note = "曲线延性特征不典型，本项不计分"
        val = round(ductility, 3)
    cues.append({
        "name": "曲线延性",
        "value": val,
        "favor": favor,
        "weight": w,
        "note": note,
    })
    macro["curveDuctility"] = {
        "ductility": round(ductility, 3),
        "dropRatio": round(drop_ratio, 3),
    }

    # --- 5) Texture density inside mask ---
    if edge_density >= 0.11:
        w = 0.16
        score_ductile += w
        favor = "ductile"
        note = "断口内边缘较密，粗糙纤维状纹理更明显"
    elif 0 < edge_density <= 0.05:
        w = 0.16
        score_brittle += w
        favor = "brittle"
        note = "断口内边缘偏少，纹理简洁、断面偏平"
    else:
        w = 0
        favor = "neutral"
        note = "纹理密度居中，本项不计分" if edge_density > 0 else "未能计算断口内纹理密度"
    cues.append({
        "name": "断面纹理密度",
        "value": round(edge_density, 5) if edge_density > 0 else 0,
        "favor": favor,
        "weight": w,
        "note": note,
    })
    macro["textureDensity"] = round(edge_density, 5)

    total = score_ductile + score_brittle
    margin = abs(score_ductile - score_brittle)
    tied = margin < 1e-9
    sep = (margin / total) if total > 1e-9 else 0.0
    close = (not tied) and (sep < 0.12 or margin < 0.05)

    if score_ductile > score_brittle:
        ftype = "ductile"
    elif score_brittle > score_ductile:
        ftype = "brittle"
    else:
        if drop_ratio >= 0.55:
            ftype = "brittle"
        elif ductility >= 0.55 or has_fibrous:
            ftype = "ductile"
        else:
            ftype = "brittle"

    conf = 0.52 + 0.44 * sep
    if tied:
        conf = 0.52
    elif close:
        conf = min(conf, 0.66)
    conf = float(min(0.96, max(0.50, conf)))

    detail = {
        "scoreDuctile": round(score_ductile, 3),
        "scoreBrittle": round(score_brittle, 3),
        "scoreMargin": round(margin, 3),
        "tied": tied,
        "close": close,
        "cues": cues,
        "macroFeatures": macro,
        "rule": "宏观五项：杯锥分区/纤维区、剪切唇迹象、断面平整度、曲线延性、断面纹理密度；中性不计分，分高者结论",
    }
    return ftype, conf, detail


def build_curve_insight(ftype: str, drop_ratio: float, curve_meta):
    if curve_meta is None:
        return {
            "consistent": None,
            "dropRatio": round(drop_ratio, 3),
            "text": "暂无力—位移曲线数据，仅根据断口形貌给出结论。",
        }
    # steep end drop aligns with brittle; gradual with ductile
    expect_brittle = drop_ratio >= 0.5
    consistent = (ftype == "brittle" and expect_brittle) or (ftype == "ductile" and not expect_brittle)
    if ftype == "brittle":
        morph = "断口结论为脆性断裂"
        curve = "曲线末端力值跌落较陡" if expect_brittle else "曲线末端跌落相对平缓"
    else:
        morph = "断口结论为塑性断裂"
        curve = "曲线末端跌落相对平缓、延伸更充分" if not expect_brittle else "曲线末端跌落偏陡"
    if consistent:
        text = "%s，与力—位移曲线特征（%s）大体自洽。Fmax≈%.2f kN，ΔLmax≈%.2f mm。" % (
            morph,
            curve,
            curve_meta["fMax"],
            curve_meta["dMax"],
        )
    else:
        text = "%s，但力—位移曲线表现为「%s」，二者不完全一致，建议复核断裂标记时机与照片。" % (
            morph,
            curve,
        )
    return {
        "consistent": bool(consistent),
        "dropRatio": round(drop_ratio, 3),
        "fMax": curve_meta["fMax"],
        "dMax": curve_meta["dMax"],
        "text": text,
    }


def _norm_box(x, y, bw, bh, img_w, img_h):
    """Pixel box -> normalized fractions of image size (for CSS overlay)."""
    iw = max(int(img_w), 1)
    ih = max(int(img_h), 1)
    x = max(0, min(int(x), iw - 1))
    y = max(0, min(int(y), ih - 1))
    bw = max(1, min(int(bw), iw - x))
    bh = max(1, min(int(bh), ih - y))
    return {
        "x": round(x / float(iw), 4),
        "y": round(y / float(ih), 4),
        "w": round(bw / float(iw), 4),
        "h": round(bh / float(ih), 4),
        "px": {"x": x, "y": y, "w": bw, "h": bh},
    }


def _box_inside(inner, outer, pad=2):
    """Clamp inner px box to sit inside outer px box."""
    ox, oy, ow, oh = outer["x"], outer["y"], outer["w"], outer["h"]
    ix, iy, iw, ih = inner["x"], inner["y"], inner["w"], inner["h"]
    ix = max(ox + pad, min(ix, ox + ow - pad - 1))
    iy = max(oy + pad, min(iy, oy + oh - pad - 1))
    iw = max(8, min(iw, ox + ow - pad - ix))
    ih = max(8, min(ih, oy + oh - pad - iy))
    # Prefer nested: if still spills, shrink toward center
    if ix + iw > ox + ow - pad:
        iw = max(8, ox + ow - pad - ix)
    if iy + ih > oy + oh - pad:
        ih = max(8, oy + oh - pad - iy)
    return {"x": int(ix), "y": int(iy), "w": int(iw), "h": int(ih)}


def detect_macro_zones(gray, mask, ftype: str = None):
    """Detect fibrous (纤维区) and radial (放射区) zoning cues.

    Always probes the mask (used by classify). Display layer may hide boxes
    when final type is brittle. Heuristic:
      - 纤维区: darker + higher local roughness, biased toward fracture centroid
      - 放射区: fracture-surface bounding box surrounding the fibrous core
    """
    if mask is None or int(np.count_nonzero(mask)) < 200:
        return None

    h, w = gray.shape[:2]
    ys, xs = np.where(mask > 0)
    x0, x1 = int(xs.min()), int(xs.max())
    y0, y1 = int(ys.min()), int(ys.max())
    fw, fh = max(1, x1 - x0 + 1), max(1, y1 - y0 + 1)

    # Radial zone ≈ fracture surface extent (slight inset to avoid silhouette edge)
    inset_x = max(2, int(round(fw * 0.04)))
    inset_y = max(2, int(round(fh * 0.04)))
    radial_px = {
        "x": x0 + inset_x,
        "y": y0 + inset_y,
        "w": max(12, fw - 2 * inset_x),
        "h": max(12, fh - 2 * inset_y),
    }

    gray_f = gray.astype(np.float32)
    blur_m = cv2.GaussianBlur(gray_f, (0, 0), 2.5)
    local_var = cv2.GaussianBlur((gray_f - blur_m) ** 2, (11, 11), 0)

    roi = gray_f[mask > 0]
    mean_i = float(np.mean(roi))
    std_i = float(np.std(roi)) + 1e-6
    # Darker → higher fibrous cue
    inv_norm = np.clip((mean_i + 0.35 * std_i - gray_f) / (2.2 * std_i), 0.0, 1.0)
    vmax = float(np.percentile(local_var[mask > 0], 92)) + 1e-6
    var_norm = np.clip(local_var / vmax, 0.0, 1.0)

    cy, cx = float(np.mean(ys)), float(np.mean(xs))
    yy, xx = np.ogrid[:h, :w]
    dist = np.sqrt((yy - cy) ** 2 + (xx - cx) ** 2)
    max_dist = 0.5 * math.sqrt(float(fw * fw + fh * fh)) + 1e-6
    # Stronger central prior: fibrous zone of cup-cone is near initiation center
    center_w = np.clip(1.0 - dist / (0.72 * max_dist), 0.0, 1.0) ** 1.6

    score = (0.48 * inv_norm + 0.52 * var_norm) * (0.12 + 0.88 * center_w)
    score = score.copy()
    score[mask == 0] = 0.0
    # Suppress outer ring (shear lip / background edge) — keep inner ~70% radius
    score[dist > 0.70 * max_dist] *= 0.25

    thr = float(np.percentile(score[score > 0], 82)) if np.any(score > 0) else 0.5
    fibrous_bin = ((score >= thr) & (mask > 0)).astype(np.uint8) * 255
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (max(5, fw // 40) | 1, max(5, fh // 40) | 1))
    fibrous_bin = cv2.morphologyEx(fibrous_bin, cv2.MORPH_OPEN, k, iterations=1)
    fibrous_bin = cv2.morphologyEx(fibrous_bin, cv2.MORPH_CLOSE, k, iterations=2)

    fibrous_px = None
    nlab, labels, stats, cents = cv2.connectedComponentsWithStats(fibrous_bin, connectivity=8)
    best_i, best_cost = -1, 1e18
    min_area = max(50.0, 0.025 * float(np.count_nonzero(mask)))
    max_area = 0.38 * float(np.count_nonzero(mask))
    for i in range(1, nlab):
        area = float(stats[i, cv2.CC_STAT_AREA])
        if area < min_area or area > max_area:
            continue
        ccx, ccy = float(cents[i][0]), float(cents[i][1])
        d_center = math.hypot(ccx - cx, ccy - cy) / max_dist
        if d_center > 0.50:
            continue
        cost = d_center - 0.0012 * area
        if cost < best_cost:
            best_cost = cost
            best_i = i

    if best_i > 0:
        fibrous_px = {
            "x": int(stats[best_i, cv2.CC_STAT_LEFT]),
            "y": int(stats[best_i, cv2.CC_STAT_TOP]),
            "w": int(stats[best_i, cv2.CC_STAT_WIDTH]),
            "h": int(stats[best_i, cv2.CC_STAT_HEIGHT]),
        }
    else:
        # Fallback: central ~36% of fracture bbox (cup-cone prior)
        fw2 = max(12, int(round(radial_px["w"] * 0.36)))
        fh2 = max(12, int(round(radial_px["h"] * 0.36)))
        fibrous_px = {
            "x": int(round(radial_px["x"] + (radial_px["w"] - fw2) / 2.0)),
            "y": int(round(radial_px["y"] + (radial_px["h"] - fh2) / 2.0)),
            "w": fw2,
            "h": fh2,
        }

    fibrous_px = _box_inside(fibrous_px, radial_px, pad=3)

    # Ensure fibrous is meaningfully smaller than radial
    if fibrous_px["w"] * fibrous_px["h"] > 0.72 * radial_px["w"] * radial_px["h"]:
        fw2 = max(12, int(round(radial_px["w"] * 0.42)))
        fh2 = max(12, int(round(radial_px["h"] * 0.42)))
        fibrous_px = _box_inside(
            {
                "x": int(round(radial_px["x"] + (radial_px["w"] - fw2) / 2.0)),
                "y": int(round(radial_px["y"] + (radial_px["h"] - fh2) / 2.0)),
                "w": fw2,
                "h": fh2,
            },
            radial_px,
            pad=3,
        )

    fibrous_area = float(fibrous_px["w"] * fibrous_px["h"])
    radial_area = float(radial_px["w"] * radial_px["h"])
    frac_mask = float(np.count_nonzero(mask))

    # Confidence: how distinctly dark/rough the fibrous ROI is vs surroundings
    fx, fy, fbw, fbh = fibrous_px["x"], fibrous_px["y"], fibrous_px["w"], fibrous_px["h"]
    core = np.zeros_like(mask)
    core[fy : fy + fbh, fx : fx + fbw] = mask[fy : fy + fbh, fx : fx + fbw]
    ring = mask.copy()
    ring[core > 0] = 0
    conf = 0.62
    if np.count_nonzero(core) > 20 and np.count_nonzero(ring) > 20:
        core_dark = float(np.mean(gray_f[core > 0]))
        ring_dark = float(np.mean(gray_f[ring > 0]))
        core_tex = float(np.mean(local_var[core > 0]))
        ring_tex = float(np.mean(local_var[ring > 0])) + 1e-6
        dark_gap = (ring_dark - core_dark) / std_i
        tex_ratio = core_tex / ring_tex
        conf = float(min(0.94, max(0.55, 0.58 + 0.12 * dark_gap + 0.08 * min(tex_ratio, 2.0))))

    method = "darkness+local-variance" if best_i > 0 else "centroid-prior-fallback"

    return {
        "fibrous": dict(_norm_box(fx, fy, fbw, fbh, w, h), label="纤维区", score=round(conf, 3)),
        "radial": dict(
            _norm_box(radial_px["x"], radial_px["y"], radial_px["w"], radial_px["h"], w, h),
            label="放射区",
            score=round(min(0.92, conf + 0.05), 3),
        ),
        "areaRatio": {
            "fibrousToRadial": round(fibrous_area / max(radial_area, 1.0), 3),
            "fibrousToMask": round(fibrous_area / max(frac_mask, 1.0), 3),
        },
        "method": method,
        "note": "纤维区为断口内偏暗、纹理较粗的中心区域；放射区为断口轮廓包围的主体范围。",
    }


def analyze(payload: dict) -> dict:
    t0 = time.time()
    img = decode_image(payload.get("imageBase64") or payload.get("image") or "")
    points = payload.get("points") or []

    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    blur = cv2.GaussianBlur(gray, (5, 5), 0)
    _, thresh = cv2.threshold(blur, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    # Prefer dark fracture region as foreground
    if np.mean(thresh) > 127:
        thresh_inv = cv2.bitwise_not(thresh)
    else:
        thresh_inv = thresh
    edges = cv2.Canny(blur, 60, 160)

    contours, _ = cv2.findContours(thresh_inv, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    contour_vis = img.copy()
    mask = np.zeros(gray.shape, dtype=np.uint8)
    geometry = {
        "area": 0.0,
        "perimeter": 0.0,
        "aspectRatio": 1.0,
        "roundness": 0.0,
        "solidity": 0.0,
    }

    if contours:
        cnt = max(contours, key=cv2.contourArea)
        area = float(cv2.contourArea(cnt))
        peri = float(cv2.arcLength(cnt, True))
        x, y, bw, bh = cv2.boundingRect(cnt)
        aspect = (bw / float(bh)) if bh > 0 else 1.0
        roundness = (4.0 * math.pi * area / (peri * peri)) if peri > 1e-6 else 0.0
        hull = cv2.convexHull(cnt)
        hull_area = float(cv2.contourArea(hull)) if hull is not None else area
        solidity = (area / hull_area) if hull_area > 1e-6 else 0.0
        geometry = {
            "area": round(area, 2),
            "perimeter": round(peri, 2),
            "aspectRatio": round(aspect, 3),
            "roundness": round(min(1.0, roundness), 3),
            "solidity": round(min(1.0, solidity), 3),
            "bbox": {"x": int(x), "y": int(y), "w": int(bw), "h": int(bh)},
        }
        cv2.drawContours(mask, [cnt], -1, 255, -1)
        cv2.drawContours(contour_vis, [cnt], -1, (46, 98, 232), 2)
        cv2.rectangle(contour_vis, (x, y), (x + bw, y + bh), (13, 148, 136), 1)

    # Edge density inside fracture mask (background edges must not dominate)
    mask_px = int(np.count_nonzero(mask))
    if mask_px > 0:
        edge_density = float(np.count_nonzero(edges[mask > 0])) / float(mask_px)
    else:
        edge_density = float(np.count_nonzero(edges)) / float(edges.size)

    drop_ratio, ductility, curve_meta = curve_features(points)
    zone_probe = detect_macro_zones(gray, mask)
    surf = surface_stats(gray, mask, geometry)
    ftype, conf, classify_detail = classify(
        geometry, edge_density, drop_ratio, ductility, zone_probe, surf
    )
    insight = build_curve_insight(ftype, drop_ratio, curve_meta)
    # Overlay zones on ductile conclusions when a real fibrous probe succeeded
    zones = None
    if ftype == "ductile" and zone_probe and zone_probe.get("method") == "darkness+local-variance":
        zones = zone_probe

    pipeline = [
        {
            "id": "original",
            "title": "原图",
            "method": "采集输入",
            "desc": "保留断口宏观形貌，作为后续灰度、分割与标注的基准。塑性断口可见杯锥与分区；脆性断口多较平整。",
            "calc": "输入 JPEG/PNG → Base64 解码 → 最长边缩至 ≤900 px（INTER_AREA），保证处理速度与上传体积。",
        },
        {
            "id": "gray",
            "title": "灰度",
            "method": "BGR→Gray",
            "desc": "去掉颜色干扰，只保留亮度信息，便于用阈值和边缘算子提取断面轮廓与纹理。",
            "calc": "OpenCV cvtColor(BGR2GRAY)；再对灰度图做 5×5 高斯模糊，抑制噪声后再做 Otsu / Canny。",
        },
        {
            "id": "thresh",
            "title": "阈值分割",
            "method": "Otsu 自动阈值",
            "desc": "按灰度分布自动取阈值，把暗色断口区域与背景分开，得到前景掩膜供轮廓提取。",
            "calc": "THRESH_BINARY + OTSU 自动求阈值 T；若均值偏亮则取反，使暗色断口成为前景（mask=255）。",
        },
        {
            "id": "edges",
            "title": "边缘检测",
            "method": "Canny",
            "desc": "提取局部灰度突变；在断口掩膜内统计纹理密度，参与塑/脆打分。",
            "calc": "Canny(blur, low=60, high=160)；纹理密度 = 掩膜内边缘像素数 / 掩膜像素数。",
        },
        {
            "id": "contour",
            "title": "轮廓提取",
            "method": "最大外轮廓",
            "desc": "在分割掩膜中取面积最大轮廓，计算面积、周长、圆度、实心度等几何量，并绘制外轮廓。",
            "calc": "findContours(RETR_EXTERNAL) 取最大轮廓；圆度=4πA/P²，实心度=A/凸包面积，并据此估剪切唇迹象。",
        },
    ]

    algorithm_guide = {
        "title": "OpenCV 宏观断口分析（教学辅助）",
        "engine": "opencv",
        "overview": (
            "本流程用 OpenCV 做宏观图像处理，再按五项形貌/曲线证据加权打分，"
            "比较塑性得分与脆性得分给出结论。属教学启发式判别，不能替代金相/SEM 标准鉴定。"
        ),
        "pipelineSteps": [
            {"step": 1, "name": "原图预处理", "algo": "解码 + 等比缩放", "detail": "最长边 ≤900 px，保留宏观杯锥/平整等可辨特征。"},
            {"step": 2, "name": "灰度与平滑", "algo": "BGR→Gray + GaussianBlur(5×5)", "detail": "去掉颜色，降低噪声，供阈值与边缘使用。"},
            {"step": 3, "name": "前景分割", "algo": "Otsu 自动阈值", "detail": "分离暗色断口与背景；必要时反相，保证断口为前景。"},
            {"step": 4, "name": "纹理边缘", "algo": "Canny(60,160)", "detail": "在掩膜内统计边缘占比，作为断面粗糙/纤维状纹理代理。"},
            {"step": 5, "name": "几何与分区", "algo": "最大外轮廓 + 暗度/局部方差", "detail": "算圆度、实心度；探测中心偏暗纤维区与放射区包围盒。"},
            {"step": 6, "name": "曲线特征", "algo": "力—位移末端统计", "detail": "由 Fmax、末端跌落比与延伸量合成延性指标，与断口交叉验证。"},
            {"step": 7, "name": "五项加权分类", "algo": "中性带不计分", "detail": "分项落入塑性/脆性阈值则累加权重，总分高者判型；置信度由分差比例映射。"},
        ],
        "features": [
            {
                "name": "圆度 roundness",
                "formula": "4π × 面积 / 周长²",
                "meaning": "越接近 1 轮廓越圆；杯锥投影常落在中等偏高区间。",
            },
            {
                "name": "实心度 solidity",
                "formula": "轮廓面积 / 凸包面积",
                "meaning": "越低外缘越凹凸；偏低常作为剪切唇（塑性）迹象。",
            },
            {
                "name": "断面平整度 flatness",
                "formula": "1 / (1 + 55 × 局部灰度方差归一化值)",
                "meaning": "局部起伏小 → 平整度高 → 偏脆性平断口。",
            },
            {
                "name": "剪切唇指数 shearLip",
                "formula": "max(0, min(1, (0.94 − solidity) / 0.16))，圆度<0.55 时再加 0.15",
                "meaning": "由轮廓相对凸包的不充实程度代理外缘剪切唇。",
            },
            {
                "name": "中心偏暗 centerDarkGap",
                "formula": "(外围环带灰度均值 − 中心区灰度均值) / 灰度标准差",
                "meaning": "正值越大，中心越暗，支持杯锥纤维区（塑性）。",
            },
            {
                "name": "纹理密度 edgeDensity",
                "formula": "掩膜内 Canny 边缘像素 / 掩膜像素",
                "meaning": "偏高 → 粗糙纤维状；偏低 → 断面简洁偏平。",
            },
            {
                "name": "曲线跌落比 dropRatio",
                "formula": "0.55×(Fmax−Fend)/Fmax + 0.45×末端陡度项",
                "meaning": "偏高表示峰值后力值陡降，脆性失稳更常见。",
            },
            {
                "name": "曲线延性 ductility",
                "formula": "0.55×min(1, ΔLmax/16) + 0.45×(1 − dropRatio)",
                "meaning": "延伸充分且末端较缓 → 延性高 → 倾向塑性。",
            },
            {
                "name": "纤维区探测",
                "formula": "score = (0.48×暗度 + 0.52×局部方差) × 中心先验；连通域筛选",
                "meaning": "在断口掩膜中心附近找偏暗且纹理较粗的区域作为纤维区。",
            },
        ],
        "scoring": {
            "rule": "宏观五项：杯锥分区/纤维区、剪切唇迹象、断面平整度、曲线延性、断面纹理密度；落在中性带不计分，塑性/脆性分高者结论。",
            "channels": [
                {
                    "name": "杯锥分区/纤维区",
                    "ductile": "检出纤维区且中心偏暗≥0.18 → 塑性 +0.24；仅疑似纤维区 → +0.14",
                    "brittle": "仅中心先验回退、未稳定检出 → 脆性 +0.16",
                    "neutral": "证据不明确或掩膜不足 → 0",
                },
                {
                    "name": "剪切唇迹象",
                    "ductile": "shearLip ≥ 0.55 → 塑性 +0.20",
                    "brittle": "shearLip ≤ 0.20 且 solidity ≥ 0.92 → 脆性 +0.16",
                    "neutral": "介于中间 → 0",
                },
                {
                    "name": "断面平整度",
                    "ductile": "flatness ≤ 0.42 → 塑性 +0.16（起伏大）",
                    "brittle": "flatness ≥ 0.72 → 脆性 +0.18（偏平）",
                    "neutral": "0.42～0.72 → 0",
                },
                {
                    "name": "曲线延性",
                    "ductile": "ductility ≥ 0.55 或 dropRatio < 0.38 → 塑性 +0.22",
                    "brittle": "dropRatio ≥ 0.55 或 ductility ≤ 0.35 → 脆性 +0.22",
                    "neutral": "无曲线或不典型 → 0",
                },
                {
                    "name": "断面纹理密度",
                    "ductile": "edgeDensity ≥ 0.11 → 塑性 +0.16",
                    "brittle": "0 < edgeDensity ≤ 0.05 → 脆性 +0.16",
                    "neutral": "居中或未算出 → 0",
                },
            ],
            "decision": "若 Sd > Sb → 塑性；Sb > Sd → 脆性；相等时用 dropRatio / ductility / 纤维区检出做平局裁决。",
            "confidence": "conf = clamp(0.50～0.96, 0.52 + 0.44 × |Sd−Sb|/(Sd+Sb))；得分接近时上限压至 0.66；平局约 0.52。",
        },
        "disclaimer": "光照、对焦、背景杂物会改变阈值与边缘结果；请结合实物照片与力—位移曲线人工复核。",
    }

    steps = [
        {"id": "original", "title": "原图", "imageBase64": encode_b64(img)},
        {"id": "gray", "title": "灰度", "imageBase64": encode_b64(gray, is_gray=True)},
        {"id": "thresh", "title": "阈值分割", "imageBase64": encode_b64(thresh_inv, is_gray=True)},
        {"id": "edges", "title": "边缘检测", "imageBase64": encode_b64(edges, is_gray=True)},
        {"id": "contour", "title": "轮廓提取", "imageBase64": encode_b64(contour_vis)},
    ]

    label = "塑性断裂" if ftype == "ductile" else "脆性断裂"
    elapsed = round((time.time() - t0) * 1000.0, 1)

    return {
        "fractureType": ftype,
        "fractureLabel": label,
        "fractureConf": round(conf, 4),
        "geometry": geometry,
        "edgeDensity": round(edge_density, 5),
        "macroFeatures": (classify_detail or {}).get("macroFeatures") or {},
        "surface": surf,
        "zones": zones,
        "pipeline": pipeline,
        "algorithmGuide": algorithm_guide,
        "classifyDetail": classify_detail,
        "steps": steps,
        "curveInsight": insight,
        "elapsedMs": elapsed,
        "engine": "opencv",
    }


def main():
    try:
        raw = sys.stdin.read()
        payload = json.loads(raw) if raw.strip() else {}
    except Exception:
        fail("请求 JSON 无效")
    try:
        result = analyze(payload)
    except SystemExit:
        raise
    except Exception as e:
        fail("分析失败: %s" % e)
    sys.stdout.write(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
