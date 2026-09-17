#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""断口分析 HTTP 服务：复用 server/fracture/analyze.py（与 Node 版同一脚本）。"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from macro import analyze_macro

ROOT = Path(__file__).resolve().parent
SCRIPT = ROOT / "analyze.py"
if not SCRIPT.exists():
    SCRIPT = ROOT.parent / "server" / "fracture" / "analyze.py"

app = FastAPI(title="Fracture Analysis Service", version="1.0.0")

class MacroIn(BaseModel):
    imageBase64: str
    experiment: str = "TENS"
    angle: str = "正面"

@app.post("/analyze-macro")
def macro_api(body: MacroIn):
    try:
        return analyze_macro(body.imageBase64, body.experiment, body.angle)
    except Exception:
        return JSONResponse({"error": "无法分析该照片，请检查图片格式与大小"}, status_code=400)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


class AnalyzeIn(BaseModel):
    imageBase64: str
    points: list | None = None


@app.get("/health")
def health():
    return {"ok": True, "script": str(SCRIPT)}


@app.post("/analyze")
def analyze_api(body: AnalyzeIn):
    if not SCRIPT.exists():
        return JSONResponse({"error": "缺少分析脚本 analyze.py"}, status_code=500)
    payload = json.dumps({"imageBase64": body.imageBase64, "points": body.points or []}, ensure_ascii=False)
    try:
        proc = subprocess.run(
            [sys.executable, str(SCRIPT)],
            input=payload,
            capture_output=True,
            text=True,
            encoding="utf-8",
            timeout=45,
        )
    except subprocess.TimeoutExpired:
        return JSONResponse({"error": "断口分析超时，请缩小图片后重试"}, status_code=504)
    raw = (proc.stdout or "").strip()
    data = {}
    try:
        data = json.loads(raw) if raw else {}
    except Exception:
        data = {}
    if data.get("error"):
        return JSONResponse({"error": data["error"]}, status_code=400)
    if proc.returncode != 0 or not data.get("fractureType"):
        msg = data.get("error") or (proc.stderr or "").strip() or ("断口分析进程退出码 " + str(proc.returncode))
        return JSONResponse({"error": msg}, status_code=500)
    return data
