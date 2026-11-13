#!/usr/bin/env python3
"""Exercise the Tuan flow against a disposable demo server (standard library only)."""
import argparse
import datetime as dt
import json
from pathlib import Path
import time
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
import uuid


def run(base_url, output):
    if output.exists():
        raise SystemExit(f"Output already exists: {output}")
    url = urlsplit(base_url)
    if url.scheme != "https" and not (url.scheme == "http" and url.hostname in {"localhost", "127.0.0.1", "::1"}):
        raise SystemExit("Use HTTPS for a remote demo server.")

    token = None

    def call(method, path, body=None, expected=200, pdf=False):
        headers = {"Content-Type": "application/json"} if body is not None else {}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        request = Request(base_url.rstrip("/") + "/api/v1" + path,
                          data=json.dumps(body).encode() if body is not None else None,
                          headers=headers, method=method)
        try:
            with urlopen(request, timeout=20) as response:
                if response.status != expected:
                    raise RuntimeError(f"{method} {path}: expected {expected}, got {response.status}")
                if pdf:
                    if response.headers.get_content_type() != "application/pdf":
                        raise RuntimeError("Expected application/pdf")
                    return response.read()
                return json.load(response)["data"]
        except HTTPError as exc:
            raise RuntimeError(f"{method} {path}: HTTP {exc.code}") from None

    now = dt.datetime.now(dt.timezone(dt.timedelta(hours=9)))
    month = (now.date().replace(day=1) - dt.timedelta(days=1)).strftime("%Y-%m")
    token = call("POST", "/auth/signup", {
        "email": f"tuan-{uuid.uuid4().hex}@example.com",
        "password": uuid.uuid4().hex + "Aa1!",
        "name": "Tuan", "language": "vi",
        "consents": {"privacy": True, "location": True, "camera": True},
        "agreedAt": now.isoformat(timespec="seconds"),
    })["accessToken"]
    seed = call("POST", "/demo/seed", {"month": month}, expected=201)
    call("GET", "/contracts/" + seed["contractId"])
    comparison = call("POST", "/payroll/comparisons", {
        "month": month, "statementId": seed["statementId"],
    }, expected=201)
    if comparison["status"] != "MISMATCH":
        raise RuntimeError("Expected a wage mismatch")
    complaint = call("POST", "/complaints", {"comparisonId": comparison["id"]}, expected=202)
    path = "/complaints/" + complaint["complaintId"]
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        draft = call("GET", path)
        if draft["status"] == "DONE" and draft["translationStatus"] == "DONE":
            break
        if "FAILED" in (draft["status"], draft["translationStatus"]):
            raise RuntimeError("Complaint generation or translation failed")
        time.sleep(1)
    else:
        raise RuntimeError("Timed out waiting for complaint")
    call("PATCH", path, {"confirm": True})
    pdf = call("GET", path + "/pdf", pdf=True)
    if not pdf.startswith(b"%PDF-"):
        raise RuntimeError("Invalid PDF header")
    with output.open("xb") as file:
        file.write(pdf)
    print(f"Tuan demo passed. PDF: {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base_url")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    run(args.base_url, args.output)
