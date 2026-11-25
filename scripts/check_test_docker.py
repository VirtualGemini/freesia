#!/usr/bin/env python3
"""Build and verify the actual test Compose stack without resetting its data."""

import json
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
COMPOSE = ["docker", "compose", "--env-file", "docker.env"]
SERVICES = ("postgres", "valkey", "freesia-admin", "freesia-web")


def compose(*args, capture=False):
    return subprocess.run(
        [*COMPOSE, *args], cwd=ROOT, check=True, text=True,
        stdout=subprocess.PIPE if capture else None,
    ).stdout


def read_http_body(url, expected_status):
    try:
        response = urllib.request.urlopen(url, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        if response.status != expected_status:
            raise RuntimeError(f"{url}: expected HTTP {expected_status}, got {response.status}")
        return response.read()


def main():
    config = json.loads(compose("config", "--format", "json", capture=True))
    admin = config["services"]["freesia-admin"]
    if config["name"] != "freesia-test" or admin["environment"].get("SPRING_PROFILES_ACTIVE") != "test":
        raise RuntimeError("Refusing to operate outside the freesia-test project and test profile")
    port = config["services"]["freesia-web"]["ports"][0]
    host = port.get("host_ip", "127.0.0.1")
    if host == "0.0.0.0":
        host = "127.0.0.1"
    if host in ("::", "::1"):
        host = "[::1]"
    entry = f"http://{host}:{port['published']}"

    try:
        compose("up", "-d", "--build", "--wait", "--wait-timeout", "180")
    except subprocess.CalledProcessError:
        # Report the useful migration signal, not full logs that might contain user data.
        logs = compose("logs", "--no-color", "--tail", "120", "freesia-admin", capture=True)
        if "Migration checksum mismatch" in logs:
            print(
                "Test database V1 checksum differs from the image. Preserve the database; "
                "use an explicitly reviewed data migration or authorized disposable rebuild. "
                "Do not repair Flyway history or disable validation.",
                file=sys.stderr,
            )
        raise

    for service in SERVICES:
        container = compose("ps", "-q", service, capture=True).strip()
        state = json.loads(subprocess.check_output(
            ["docker", "inspect", "--format", "{{json .State}}", container], text=True,
        ))
        if state["Status"] != "running" or state.get("Health", {}).get("Status") != "healthy":
            raise RuntimeError(f"{service} is not running and healthy")
        print(f"PASS: {service} running and healthy")

    readiness = compose(
        "exec", "-T", "freesia-admin", "wget", "-q", "-T", "3", "-O", "-",
        "http://127.0.0.1:8080/actuator/health/readiness", capture=True,
    )
    if json.loads(readiness)["status"] != "UP":
        raise RuntimeError("Backend readiness is not UP")
    if read_http_body(entry + "/healthz", 200).strip() != b"ok":
        raise RuntimeError("Unexpected frontend health response")
    if b"<html" not in read_http_body(entry + "/", 200).lower():
        raise RuntimeError("Frontend entry is not HTML")
    unauthenticated = json.loads(read_http_body(entry + "/api/role/list", 401))
    if unauthenticated["code"] != 10003:
        raise RuntimeError("Protected API did not return the unauthenticated envelope")
    read_http_body(entry + "/actuator/health/readiness", 404)
    print("PASS: backend readiness, frontend entry, same-origin API and private Actuator")


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, OSError, RuntimeError, ValueError, KeyError) as error:
        print(f"FAIL: test Docker verification: {error}", file=sys.stderr)
        sys.exit(1)
