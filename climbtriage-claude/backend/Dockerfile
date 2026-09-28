# One image, two processes: run the API and the worker as separate container apps
# (Azure Container Apps) or services. Not built in the M1 environment (no Docker available).
FROM python:3.12-slim
WORKDIR /app
COPY pyproject.toml ./
COPY climbtriage_backend ./climbtriage_backend
RUN pip install --no-cache-dir .
ENV CLIMBTRIAGE_DATA_DIR=/data
EXPOSE 8000
# API:    docker run ... climbtriage-backend
# Worker: docker run ... climbtriage-backend python -m climbtriage_backend.worker
CMD ["uvicorn", "climbtriage_backend.api:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000"]
