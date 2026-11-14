#!/usr/bin/env python3
"""Нагрузочный тест OMNIX-сервера (только стандартная библиотека).

ТОЛЬКО для тестового сервера. НЕ запускать на боевом и не против реальных платных
облачных провайдеров ИИ. Подробности в README.md.

Пример:
    OMNIX_LOADTEST_TOKEN=test-token python loadtest.py --base-url http://localhost:8090 -n 200 -c 50
    OMNIX_LOADTEST_TOKEN=test-token python loadtest.py --ai -n 40 -c 10
"""
import argparse
import http.client
import json
import os
import statistics
import sys
import time
import urllib.parse
import uuid
from concurrent.futures import ThreadPoolExecutor

LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1", "[::1]"}


def one_request(base, method, path, token, body, timeout):
    """Один запрос. Возвращает (статус или 0 при сетевой ошибке, секунды)."""
    parsed = urllib.parse.urlsplit(base)
    cls = http.client.HTTPSConnection if parsed.scheme == "https" else http.client.HTTPConnection
    headers = {"Content-Type": "application/json", "Connection": "close"}
    if token:
        headers["Authorization"] = "Bearer " + token
    payload = json.dumps(body).encode("utf-8") if body is not None else None
    started = time.perf_counter()
    status = 0
    try:
        conn = cls(parsed.hostname, parsed.port, timeout=timeout)
        conn.request(method, path, body=payload, headers=headers)
        response = conn.getresponse()
        response.read()
        status = response.status
        conn.close()
    except (OSError, http.client.HTTPException):
        status = 0
    return status, time.perf_counter() - started


def percentile(sorted_values, q):
    if not sorted_values:
        return 0.0
    index = min(len(sorted_values) - 1, max(0, int(round(q * (len(sorted_values) - 1)))))
    return sorted_values[index]


def run_scenario(name, base, method, path, token, make_body, total, concurrency, timeout):
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = [
            pool.submit(one_request, base, method, path, token, make_body(i), timeout)
            for i in range(total)
        ]
        results = [f.result() for f in futures]
    wall = time.perf_counter() - started

    statuses = [s for s, _ in results]
    times = sorted(t for _, t in results)
    count = len(results)

    def share(predicate):
        return 100.0 * sum(1 for s in statuses if predicate(s)) / count

    print("\n=== %s  %s %s ===" % (name, method, path))
    print("запросов: %d, одновременно: %d, время: %.2f c, ~%.1f запр/с" % (count, concurrency, wall, count / wall if wall else 0))
    print("успех 2xx: %5.1f%%   429: %5.1f%%   503: %5.1f%%   другие 4xx: %5.1f%%   другие 5xx: %5.1f%%   сеть/таймаут: %5.1f%%" % (
        share(lambda s: 200 <= s < 300),
        share(lambda s: s == 429),
        share(lambda s: s == 503),
        share(lambda s: 400 <= s < 500 and s != 429),
        share(lambda s: 500 <= s < 600 and s != 503),
        share(lambda s: s == 0),
    ))
    print("задержка, мс: p50=%.0f  p95=%.0f  p99=%.0f  max=%.0f  mean=%.0f" % (
        percentile(times, 0.50) * 1000,
        percentile(times, 0.95) * 1000,
        percentile(times, 0.99) * 1000,
        (times[-1] if times else 0) * 1000,
        (statistics.mean(times) if times else 0) * 1000,
    ))


def main():
    parser = argparse.ArgumentParser(description="Нагрузочный тест OMNIX (только тестовый сервер!)")
    parser.add_argument("--base-url", default="http://localhost:8090")
    parser.add_argument("-n", "--requests", type=int, default=200, help="запросов на сценарий")
    parser.add_argument("-c", "--concurrency", type=int, default=50, help="одновременных запросов")
    parser.add_argument("--timeout", type=float, default=35.0, help="таймаут одного запроса, секунды")
    parser.add_argument("--ai", action="store_true", help="добавить /v1/ai/execute (тратит квоты ИИ!)")
    parser.add_argument("--allow-remote", action="store_true",
                        help="разрешить НЕ локальный адрес (только свой тестовый стенд)")
    args = parser.parse_args()

    host = urllib.parse.urlsplit(args.base_url).hostname or ""
    if host not in LOCAL_HOSTS and not args.allow_remote:
        print("Отказ: %s не локальный адрес. Тест нельзя запускать на боевом сервере. "
              "Для своего тестового стенда добавьте --allow-remote." % host, file=sys.stderr)
        return 2
    if args.requests < 1 or args.concurrency < 1:
        print("-n и -c должны быть положительными", file=sys.stderr)
        return 2

    token = os.environ.get("OMNIX_LOADTEST_TOKEN", "")
    if not token:
        print("Предупреждение: OMNIX_LOADTEST_TOKEN не задан, запросы с авторизацией получат 401.", file=sys.stderr)

    print("Цель: %s (тестовый сервер!)" % args.base_url)
    run_scenario("health", args.base_url, "GET", "/v1/health", None, lambda i: None,
                 args.requests, args.concurrency, args.timeout)
    run_scenario("license validate", args.base_url, "POST", "/v1/license/validate", token,
                 lambda i: {"requestId": str(uuid.uuid4())}, args.requests, args.concurrency, args.timeout)
    if args.ai:
        run_scenario("ai execute", args.base_url, "POST", "/v1/ai/execute", token,
                     lambda i: {"text": "ping %d" % i, "requestId": str(uuid.uuid4()),
                                "privacyLevel": "NORMAL", "source": "CHAT"},
                     args.requests, args.concurrency, args.timeout)
    return 0


if __name__ == "__main__":
    sys.exit(main())
