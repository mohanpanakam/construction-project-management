"""
Minimal OCR HTTP microservice using RapidOCR (rapidocr-onnxruntime).

Exposes POST /ocr — accepts a single image file (multipart/form-data, field
name "file") and returns the recognized text, one line per detected text
region, in top-to-bottom reading order (matching what Tesseract/Textract
already return, so the Kotlin backend's line-based parser in OcrService.kt
works unchanged regardless of which OCR provider produced the text).

Why RapidOCR instead of the "paddleocr" pip package directly: paddleocr
depends on the full PaddlePaddle deep-learning framework, whose official
wheels are built almost exclusively for x86_64 and were unreliable here even
under x86_64 QEMU emulation on this ARM64 Mac (segfaults / corrupted native
extensions). RapidOCR ships the SAME underlying PP-OCR detection/recognition
models, pre-exported to ONNX, and runs them via plain `onnxruntime` — which
has solid, genuinely native ARM64 wheels. That also means this container
will actually run correctly on the ARM64 (t4g) production EC2 instance,
unlike the native PaddlePaddle framework.
"""
import io
import logging

from fastapi import FastAPI, File, UploadFile
from fastapi.responses import JSONResponse
from rapidocr_onnxruntime import RapidOCR
from PIL import Image
import numpy as np

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("ocr-service")

app = FastAPI(title="RapidOCR (PP-OCR/ONNX) microservice")

log.info("Loading RapidOCR (PP-OCR ONNX) models...")
_ocr = RapidOCR()
log.info("RapidOCR ready.")


@app.get("/health")
def health():
    return {"status": "ok", "engine": "rapidocr-onnxruntime"}


@app.post("/ocr")
async def ocr(file: UploadFile = File(...)):
    raw = await file.read()
    if not raw:
        return JSONResponse(status_code=400, content={"error": "empty file"})

    try:
        image = Image.open(io.BytesIO(raw)).convert("RGB")
    except Exception as e:
        return JSONResponse(status_code=400, content={"error": f"unreadable image: {e}"})

    result, _elapsed = _ocr(np.array(image))

    # result is a list of [ [[x,y]*4 box], text, confidence ], roughly sorted
    # top-to-bottom already. Pull out just the text, one per line, matching
    # the plain-text format Tesseract/Textract already return.
    lines: list[str] = []
    if result:
        for _box, text, _conf in result:
            if text and text.strip():
                lines.append(text.strip())

    return {"text": "\n".join(lines), "lineCount": len(lines)}

