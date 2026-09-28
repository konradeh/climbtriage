FROM python:3.14-slim
WORKDIR /app
COPY requirements.lock .
RUN pip install --no-cache-dir -r requirements.lock
COPY climbtriage ./climbtriage
ENV CLIMBTRIAGE_DATA=/data
# Local development only. Publish on host loopback; worker uses the same /data volume.
CMD ["python", "-m", "uvicorn", "climbtriage.api:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000"]
