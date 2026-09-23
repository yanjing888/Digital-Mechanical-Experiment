"""宏观断口塑脆判别：正面/侧面轮廓颈缩 + 断口面明暗分区（OpenCV）。"""
import base64
import io
import cv2
import numpy as np
from PIL import Image


def _mask_specimen(gray: np.ndarray):
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    _, mask = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    border = np.concatenate((mask[0, :], mask[-1, :], mask[:, 0], mask[:, -1]))
    if np.mean(border) > 127:
        mask = 255 - mask
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    candidates = [c for c in contours if cv2.contourArea(c) > gray.size * 0.025]
    if not candidates:
        return None, mask, []
    contour = max(candidates, key=cv2.contourArea)
    return contour, mask, candidates


def _silhouette_widths(contour, mask):
    x, y, bw, bh = cv2.boundingRect(contour)
    sil = np.zeros_like(mask)
    cv2.drawContours(sil, [contour], -1, 255, -1)
    crop = sil[y : y + bh, x : x + bw]
    if bw > bh:
        crop = crop.T
    return np.count_nonzero(crop, axis=1), (x, y, bw, bh)


def _necking_ratio(widths: np.ndarray) -> float:
    if len(widths) < 12:
        return 1.0
    positive = widths[widths > 0]
    if len(positive) < 8:
        return 1.0
    top = float(np.max(widths[: max(3, len(widths) // 3)]))
    bottom = widths[len(widths) * 3 // 5 :]
    bottom = bottom[bottom > 0]
    if len(bottom) == 0:
        return 1.0
    narrow = float(np.min(bottom))
    return narrow / max(top, 1.0)


def _rim_center_contrast(gray: np.ndarray, contour, mask) -> tuple[float, float]:
    x, y, bw, bh = cv2.boundingRect(contour)
    sub = mask[y : y + bh, x : x + bw] > 0
    if np.count_nonzero(sub) < 80:
        return 0.0, 0.0
    crop_g = gray[y : y + bh, x : x + bw]
    ys, xs = np.where(sub)
    cx, cy = bw / 2.0, bh / 2.0
    dist = np.sqrt((xs - cx) ** 2 + (ys - cy) ** 2)
    maxd = float(np.percentile(dist, 96))
    if maxd < 2:
        return 0.0, float(np.std(crop_g[sub]))
    norm = dist / maxd
    vals = crop_g[ys, xs].astype(np.float32)
    outer = vals[norm >= 0.68]
    inner = vals[norm <= 0.42]
    if len(outer) < 12 or len(inner) < 12:
        return 0.0, float(np.std(vals))
    contrast = float(np.mean(outer) - np.mean(inner))
    return contrast, float(np.std(vals))


def _circularity(contour) -> float:
    area = cv2.contourArea(contour)
    peri = cv2.arcLength(contour, True)
    if peri <= 0:
        return 0.0
    return float(4 * np.pi * area / (peri * peri))


def _classify_tens(contour, gray, mask, angle: str, features: dict):
    widths, (_, _, bw, bh) = _silhouette_widths(contour, mask)
    neck = _necking_ratio(widths)
    rim, gstd = _rim_center_contrast(gray, contour, mask)
    circ = _circularity(contour)
    features["neckingWidthRatio"] = round(neck, 3)
    features["rimCenterContrast"] = round(rim, 2)
    features["fractureGrayStd"] = round(gstd, 2)
    features["circularity"] = round(circ, 3)

    aspect = bw / max(bh, 1)
    rod_like = bh > bw * 1.35 and len(widths) >= 16
    face_only = (not rod_like) and circ > 0.72 and 0.72 <= aspect <= 1.38
    features["viewMode"] = "side" if rod_like else ("face" if face_only else "mixed")

    evidence = []
    ductile_score = 0
    brittle_score = 0

    if rod_like and neck < 0.86:
        ductile_score += 2
        evidence.append(f"侧视/全貌照片中断口附近明显颈缩（宽度比约 {neck:.2f}）")
    elif rod_like and neck > 0.94:
        brittle_score += 1
        evidence.append(f"侧视/全貌照片中杆身宽度较均匀（宽度比约 {neck:.2f}），颈缩不明显")

    if rim >= 11:
        ductile_score += 2
        evidence.append(f"断口面外缘较亮、心部较暗（明暗差约 {rim:.1f}），符合颈缩后杯锥状塑性断口")
    elif rim < 9.2 and gstd < 17:
        brittle_score += 2
        evidence.append(f"断口面灰度较均匀（明暗差约 {rim:.1f}），符合脆性断口")

    if face_only:
        if circ > 0.84 and rim < 9.5:
            brittle_score += 2
            evidence.append("正面断口接近完整圆且较平整，未见杯锥状明暗分区")
        elif circ < 0.82 or rim >= 11:
            ductile_score += 1
            evidence.append("正面断口轮廓或明暗分布呈杯锥状特征")

    if angle in ("侧面", "斜面") and rod_like:
        mid = widths[len(widths) // 5 : len(widths) * 4 // 5]
        mid = mid[mid > 0]
        if len(mid) >= 10:
            side_ratio = float(np.percentile(mid, 20) / max(1, np.percentile(mid, 80)))
            features["silhouetteWidthRatio"] = round(side_ratio, 3)
            if side_ratio < 0.78:
                ductile_score += 2
                evidence.append("侧视轮廓呈明显颈缩")

    if ductile_score >= brittle_score + 2 and ductile_score >= 2:
        return "ductile", evidence
    if brittle_score >= ductile_score + 1 and brittle_score >= 2:
        return "brittle", evidence
    if ductile_score > brittle_score:
        return "ductile", evidence + ["综合形貌与明暗特征倾向塑性断裂"]
    if brittle_score > ductile_score:
        return "brittle", evidence + ["综合形貌与明暗特征倾向脆性断裂"]
    return "uncertain", evidence + ["形貌线索不够明确，建议补拍侧面清晰照片"]


def _classify_comp(contour, gray, mask, features: dict):
    x, y, bw, bh = cv2.boundingRect(contour)
    area = cv2.contourArea(contour)
    hull = cv2.contourArea(cv2.convexHull(contour))
    solidity = area / max(hull, 1)
    features["solidity"] = round(solidity, 3)
    widths, _ = _silhouette_widths(contour, mask)
    if len(widths) >= 12:
        top = float(np.mean(widths[: len(widths) // 5]))
        mid = float(np.mean(widths[len(widths) * 2 // 5 : len(widths) * 3 // 5]))
        bot = float(np.mean(widths[-len(widths) // 5 :]))
        bulge = mid / max(1.0, (top + bot) / 2)
        features["bulgeRatio"] = round(bulge, 3)
        if bulge > 1.08 and solidity < 0.96:
            return "ductile", ["试样中部鼓肚、轮廓饱满，倾向压缩塑性破坏"]
        if bulge < 1.03 and solidity > 0.97:
            return "brittle", ["破坏后形态较规整、鼓肚不明显，倾向压缩脆性破坏"]
    if solidity > 0.98:
        return "brittle", ["轮廓接近凸形且较平整，倾向脆性压缩断口"]
    return "uncertain", ["压缩破坏形貌不典型，请结合曲线与多角度照片复核"]


def _decode_image(raw: bytes):
    buf = np.frombuffer(raw, np.uint8)
    img = cv2.imdecode(buf, cv2.IMREAD_COLOR)
    if img is not None:
        return img
    try:
        pil = Image.open(io.BytesIO(raw))
        pil = pil.convert("RGB")
        return cv2.cvtColor(np.array(pil), cv2.COLOR_RGB2BGR)
    except Exception as exc:
        raise ValueError("无法解码图片，请使用 JPG 或 PNG") from exc


def analyze_macro(image_base64: str, experiment: str = "TENS", angle: str = "正面") -> dict:
    if len(image_base64) > 28 * 1024 * 1024:
        raise ValueError("图像过大")
    raw = base64.b64decode(image_base64.split(",", 1)[-1], validate=True)
    img = _decode_image(raw)
    h, w = img.shape[:2]
    if h * w > 24_000_000:
        raise ValueError("图像像素过多")
    if max(h, w) > 1200:
        img = cv2.resize(img, (round(w * 1200 / max(h, w)), round(h * 1200 / max(h, w))))
        h, w = img.shape[:2]
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    focus = float(cv2.Laplacian(gray, cv2.CV_64F).var())
    warnings = []
    if min(h, w) < 400:
        warnings.append("图像较小，请补拍清晰照片")
    if focus < 45:
        warnings.append("清晰度不足，请检查焦点")
    contour, mask, _ = _mask_specimen(gray)
    features = {}
    evidence = []
    candidate = "uncertain"
    if contour is None:
        warnings.append("未找到试样轮廓，请使用对比明显的背景")
        evidence.append("未能分割试样，无法自动判别")
    else:
        x, y, bw, bh = cv2.boundingRect(contour)
        features["aspectRatio"] = round(bw / max(bh, 1), 3)
        features["areaRatio"] = round(cv2.contourArea(contour) / gray.size, 3)
        exp = (experiment or "TENS").upper()
        circ = _circularity(contour)
        face_end = circ > 0.72 and 0.72 <= (bw / max(bh, 1)) <= 1.38
        if exp == "COMP" and not face_end:
            candidate, lines = _classify_comp(contour, gray, mask, features)
        else:
            candidate, lines = _classify_tens(contour, gray, mask, angle, features)
        evidence.extend(lines)
    return {
        "candidate": candidate,
        "reviewRequired": candidate == "uncertain",
        "features": features,
        "quality": {"focusMeasure": round(focus, 1), "warnings": warnings},
        "evidence": evidence,
        "method": "macro-neck-rim-v3",
        "calibrated": False,
    }
