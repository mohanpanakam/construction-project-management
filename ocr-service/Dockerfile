# PaddleOCR microservice — see main.py for why this is a separate Python
# container instead of a JVM library.
FROM python:3.11-slim

WORKDIR /app

# libgl1/libglib2.0-0: required by opencv-python-headless (a rapidocr dep)
# even in headless/server environments.
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgl1 \
    libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py .

# Pre-download RapidOCR's detection/recognition/angle-classification ONNX
# models at BUILD time (not first-request time), so the container starts
# ready and doesn't need internet access at runtime.
RUN python -c "from rapidocr_onnxruntime import RapidOCR; RapidOCR()"

EXPOSE 8000
HEALTHCHECK --interval=10s --timeout=5s --retries=12 --start-period=30s \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health', timeout=3)" || exit 1
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]

