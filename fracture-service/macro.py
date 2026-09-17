"""Macro shape evidence and image quality checks. No calibrated confidence claim."""
import base64
import cv2
import numpy as np


def analyze_macro(image_base64: str, experiment: str = "TENS", angle: str = "正面") -> dict:
    if len(image_base64) > 28 * 1024 * 1024:
        raise ValueError("图像过大")
    raw = base64.b64decode(image_base64.split(",", 1)[-1], validate=True)
    img = cv2.imdecode(np.frombuffer(raw, np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        raise ValueError("无法解码图片")
    h, w = img.shape[:2]
    if h * w > 24_000_000:
        raise ValueError("图像像素过多")
    if max(h, w) > 1200:
        img = cv2.resize(img, (round(w * 1200 / max(h, w)), round(h * 1200 / max(h, w))))
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    focus = float(cv2.Laplacian(gray, cv2.CV_64F).var())
    warnings = []
    if min(h, w) < 400:
        warnings.append("图像较小，请补拍清晰照片")
    if focus < 45:
        warnings.append("清晰度不足或纹理较少，请检查焦点")
    if np.mean(gray > 248) > .45:
        warnings.append("大片区域过亮，请检查反光与曝光")
    if np.mean(gray < 8) > .45:
        warnings.append("大片区域过暗，请检查补光")
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    _, mask = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    border = np.concatenate((mask[0, :], mask[-1, :], mask[:, 0], mask[:, -1]))
    if np.mean(border) > 127:
        mask = 255 - mask
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    candidates = [c for c in contours if cv2.contourArea(c) > gray.size * .025]
    features = {}
    evidence = []
    candidate = "uncertain"
    if not candidates:
        warnings.append("未找到完整试样轮廓；请使用对比明显的背景重新拍摄")
    else:
        contour = max(candidates, key=cv2.contourArea)
        x, y, bw, bh = cv2.boundingRect(contour)
        area = cv2.contourArea(contour)
        hull = cv2.contourArea(cv2.convexHull(contour))
        features = {"areaRatio": round(area / gray.size, 3), "aspectRatio": round(bw / max(bh, 1), 3), "solidity": round(area / max(hull, 1), 3)}
        if x <= 2 or y <= 2 or x + bw >= gray.shape[1] - 2 or y + bh >= gray.shape[0] - 2:
            warnings.append("试件轮廓触及画面边缘，请完整取景")
        if area / gray.size > .85:
            warnings.append("无法可靠区分试件与背景，请调整构图")
        evidence.append("已提取整体外轮廓；面积占比、宽高比与轮廓完整度供教师检查分割结果")
        # Long-side silhouette only. In-plane orientation is normalized before measuring widths.
        if angle in ("侧面", "斜面") and max(bw, bh) / max(1, min(bw, bh)) >= 1.8:
            silhouette = np.zeros_like(mask)
            cv2.drawContours(silhouette, [contour], -1, 255, -1)
            crop = silhouette[y:y + bh, x:x + bw]
            if bw > bh:
                crop = crop.T
            widths = np.count_nonzero(crop, axis=1)
            middle = widths[len(widths) // 5:len(widths) * 4 // 5]
            positive = middle[middle > 0]
            if len(positive) >= 20:
                ratio = float(np.percentile(positive, 15) / max(1, np.percentile(positive, 85)))
                features["silhouetteWidthRatio"] = round(ratio, 3)
                evidence.append("侧视轮廓局部宽度比 %.3f，需检查拍摄倾角及试样原始形状" % ratio)
                if experiment == "TENS" and ratio < .7 and not warnings:
                    candidate = "ductile"
                    evidence.append("局部收窄可作为塑性变形的候选线索；须排除原始几何及投影影响")
        if experiment == "COMP":
            evidence.append("压缩实验按整体变形和破坏形态人工复核；不套用拉伸颈缩规则")
    if candidate == "uncertain":
        evidence.append("现有图像不足以可靠区分塑性与脆性，请结合多角度照片和教师参考")
    overlay = img.copy()
    if candidates:
        cv2.drawContours(overlay, [max(candidates, key=cv2.contourArea)], -1, (40, 170, 50), 2)
    ok, encoded = cv2.imencode(".jpg", overlay)
    return {"candidate": candidate, "reviewRequired": True, "features": features,
            "quality": {"focusMeasure": round(focus, 1), "warnings": warnings}, "evidence": evidence,
            "overlay": "data:image/jpeg;base64," + base64.b64encode(encoded).decode() if ok else None,
            "method": "macro-outline-v1", "calibrated": False}
