"""Servidor de entrenamiento controlado por Centinela IP.

Ejecuta un trabajo a la vez para no agotar la GPU. Nunca debe exponerse a
Internet sin HTTPS, autenticación y límites adicionales del proxy frontal.
"""

from __future__ import annotations

import json
import os
import shutil
import threading
import uuid
import zipfile
from pathlib import Path

import yaml
from fastapi import BackgroundTasks, FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse
from ultralytics import YOLO

ROOT = Path(os.getenv("CENTINELA_TRAINING_ROOT", "training-jobs")).resolve()
ROOT.mkdir(parents=True, exist_ok=True)
TOKEN = os.getenv("CENTINELA_TRAINING_TOKEN", "")
BASE_MODEL = os.getenv("CENTINELA_BASE_MODEL", "yolo26n.pt")
DEVICE = os.getenv("CENTINELA_DEVICE", "0")
MAX_UPLOAD = int(os.getenv("CENTINELA_MAX_UPLOAD_BYTES", str(2 * 1024**3)))
MAX_EXTRACTED = int(os.getenv("CENTINELA_MAX_EXTRACTED_BYTES", str(4 * MAX_UPLOAD)))
MAX_ENTRIES = int(os.getenv("CENTINELA_MAX_ZIP_ENTRIES", "100000"))
GPU_LOCK = threading.Lock()
STATE_LOCK = threading.Lock()
JOBS: dict[str, dict] = {}

app = FastAPI(title="Centinela Training", version="1.0")


def authorize(authorization: str | None) -> None:
    if TOKEN and authorization != f"Bearer {TOKEN}":
        raise HTTPException(status_code=401, detail="Token inválido")


def update(job_id: str, **values) -> None:
    with STATE_LOCK:
        JOBS[job_id].update(values)
        job_dir = ROOT / job_id
        (job_dir / "job.json").write_text(
            json.dumps(JOBS[job_id], ensure_ascii=False, indent=2), encoding="utf-8"
        )


def safe_extract(archive: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    root = destination.resolve()
    with zipfile.ZipFile(archive) as source:
        members = source.infolist()
        if len(members) > MAX_ENTRIES:
            raise ValueError("Demasiados archivos dentro del ZIP")
        total = 0
        for member in members:
            target = (destination / member.filename).resolve()
            if root != target and root not in target.parents:
                raise ValueError("Ruta insegura dentro del ZIP")
            if member.file_size > MAX_UPLOAD:
                raise ValueError("Entrada demasiado grande")
            total += member.file_size
            if total > MAX_EXTRACTED:
                raise ValueError("Dataset descomprimido demasiado grande")
        source.extractall(destination)


def labels_from_dataset(dataset_dir: Path, data: dict) -> list[str]:
    classes = dataset_dir / "classes.txt"
    if classes.exists():
        return [line.strip() for line in classes.read_text(encoding="utf-8").splitlines() if line.strip()]
    names = data.get("names", {})
    if isinstance(names, dict):
        return [str(names[key]) for key in sorted(names, key=lambda value: int(value))]
    return [str(value) for value in names]


def train_job(job_id: str, epochs: int) -> None:
    job_dir = ROOT / job_id
    try:
        update(job_id, status="queued", progress=0.0, message="Esperando GPU")
        with GPU_LOCK:
            update(job_id, status="running", progress=0.01, message="Preparando dataset")
            dataset_dir = job_dir / "dataset"
            safe_extract(job_dir / "dataset.zip", dataset_dir)
            source_yaml = dataset_dir / "data.yaml"
            if not source_yaml.exists():
                raise ValueError("El dataset no contiene data.yaml")
            data = yaml.safe_load(source_yaml.read_text(encoding="utf-8"))
            data["path"] = str(dataset_dir)
            training_yaml = job_dir / "training-data.yaml"
            training_yaml.write_text(yaml.safe_dump(data, allow_unicode=True, sort_keys=False), encoding="utf-8")
            labels = labels_from_dataset(dataset_dir, data)
            if not labels:
                raise ValueError("El dataset no tiene clases")
            train_images = list((dataset_dir / "images" / "train").glob("*"))
            val_images = list((dataset_dir / "images" / "val").glob("*"))
            if not train_images or not val_images:
                raise ValueError("El dataset necesita imágenes tanto en train como en val")

            model = YOLO(BASE_MODEL)

            def epoch_end(trainer) -> None:
                completed = int(trainer.epoch) + 1
                update(
                    job_id,
                    progress=min(0.92, completed / max(1, epochs) * 0.92),
                    message=f"Época {completed}/{epochs}",
                )

            model.add_callback("on_train_epoch_end", epoch_end)
            model.train(
                data=str(training_yaml),
                epochs=epochs,
                imgsz=640,
                project=str(job_dir / "runs"),
                name="train",
                exist_ok=True,
                device=DEVICE,
                workers=max(1, min(8, os.cpu_count() or 1)),
            )
            best = Path(model.trainer.best)
            if not best.exists():
                raise RuntimeError("No se generó best.pt")
            update(job_id, progress=0.94, message="Exportando mejor checkpoint a ONNX")
            exported = Path(YOLO(str(best)).export(format="onnx", imgsz=640, simplify=True))
            if not exported.exists():
                raise RuntimeError("La exportación ONNX no produjo un archivo")

            package = job_dir / "best-model.centinela-model.zip"
            manifest = {
                "name": f"YOLO26 · entrenamiento {job_id[:8]}",
                "format": "centinela-yolo-onnx-v1",
                "base_model": BASE_MODEL,
                "epochs": epochs,
                "input": "1x3x640x640",
                "output": "1x300x6",
            }
            with zipfile.ZipFile(package, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.write(exported, "model.onnx")
                archive.writestr("labels.txt", "\n".join(labels) + "\n")
                archive.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
            update(job_id, status="completed", progress=1.0, message="Mejor modelo listo")
    except Exception as error:
        update(job_id, status="failed", message=f"{type(error).__name__}: {error}")


@app.get("/health")
def health(authorization: str | None = Header(default=None)):
    authorize(authorization)
    return {"service": "Centinela Training", "status": "ok", "base_model": BASE_MODEL, "device": DEVICE}


@app.post("/train", status_code=202)
async def start_training(
    background: BackgroundTasks,
    dataset: UploadFile = File(...),
    epochs: int = Form(100),
    authorization: str | None = Header(default=None),
):
    authorize(authorization)
    if epochs < 1 or epochs > 300:
        raise HTTPException(status_code=400, detail="epochs debe estar entre 1 y 300")
    job_id = uuid.uuid4().hex
    job_dir = ROOT / job_id
    job_dir.mkdir(parents=True)
    target = job_dir / "dataset.zip"
    total = 0
    with target.open("wb") as output:
        while chunk := await dataset.read(1024 * 1024):
            total += len(chunk)
            if total > MAX_UPLOAD:
                shutil.rmtree(job_dir, ignore_errors=True)
                raise HTTPException(status_code=413, detail="Dataset demasiado grande")
            output.write(chunk)
    with STATE_LOCK:
        JOBS[job_id] = {
            "job_id": job_id,
            "status": "queued",
            "progress": 0.0,
            "message": "Trabajo recibido",
            "epochs": epochs,
        }
    update(job_id)
    background.add_task(train_job, job_id, epochs)
    return {"job_id": job_id, "status": "queued"}


@app.get("/jobs/{job_id}")
def job_status(job_id: str, authorization: str | None = Header(default=None)):
    authorize(authorization)
    with STATE_LOCK:
        value = JOBS.get(job_id)
    if value is None:
        state = ROOT / job_id / "job.json"
        if not state.exists():
            raise HTTPException(status_code=404, detail="Trabajo desconocido")
        value = json.loads(state.read_text(encoding="utf-8"))
    return value


@app.get("/jobs/{job_id}/model")
def download_model(job_id: str, authorization: str | None = Header(default=None)):
    authorize(authorization)
    package = ROOT / job_id / "best-model.centinela-model.zip"
    if not package.exists():
        raise HTTPException(status_code=409, detail="El mejor modelo todavía no está listo")
    return FileResponse(package, media_type="application/zip", filename=package.name)
