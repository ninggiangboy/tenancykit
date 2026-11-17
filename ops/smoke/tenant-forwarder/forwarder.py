#!/usr/bin/env python3
"""A Tenant's own forwarder, standing in for the code a Tenant writes.

It is not a suite service: it is test scaffolding that proves the supported integration, that a Tenant's system receives
crm's signed webhooks and calls loyalty itself. It verifies the `Crm-*` signature with the endpoint secret it was
given, then maps `order.recorded`, `order.refunded` and `customer.erased` onto loyalty's endpoints, field for field.

Only the standard library is used, so the container needs no package installation.
"""

import hashlib
import hmac
import json
import os
import sys
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

SECRET = os.environ["FORWARDER_CRM_SECRET"]
LOYALTY = os.environ.get("FORWARDER_LOYALTY_URL", "http://loyalty-api:8080")
TOKEN = os.environ["FORWARDER_LOYALTY_TOKEN"]
# The app resolves the Tenant from the host, exactly as its public edge does; the forwarder replays that host.
LOYALTY_HOST = os.environ["FORWARDER_LOYALTY_HOST"]
LOG = os.environ.get("FORWARDER_LOG", "/tmp/forwarder.log")

# The Tenant's own mapping from crm's opaque customer id to the reference it chose. `customer.erased` carries the id
# only, deliberately, so a forwarder that wants to erase in another app keeps this mapping itself — as a Tenant's system
# naturally can, because it chose the reference.
REFS: dict = {}

# The loyalty webhook receiver: one list of what arrived, so the smoke can assert which event types the Tenant saw.
RECEIVED: list = []
LOYALTY_SECRET = os.environ.get("FORWARDER_LOYALTY_SECRET", "")


def log(line: str) -> None:
    print(line, flush=True)


def verify(timestamp: str, signature: str, body: bytes) -> bool:
    """The suite signs `v1=hex(HMAC-SHA256(secret, timestamp + "." + body))`; the secret is used whole."""
    expected = "v1=" + hmac.new(
        SECRET.encode(), timestamp.encode() + b"." + body, hashlib.sha256
    ).hexdigest()
    return hmac.compare_digest(expected, signature or "")


def loyalty_call(method: str, path: str, payload) -> int:
    request = urllib.request.Request(
        LOYALTY + path,
        data=json.dumps(payload).encode() if payload is not None else None,
        method=method,
        headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json", "Host": LOYALTY_HOST},
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status
    except urllib.error.HTTPError as refused:
        return refused.code


def order_body(data):
    # crm's order carries `name` and a total; loyalty reads neither, and the rest maps field for field.
    return {
        "externalRef": data["externalRef"],
        "currency": data["currency"],
        "occurredAt": data["occurredAt"],
        "storeCode": data.get("storeCode"),
        "channel": data.get("channel"),
        "lines": [
            {
                "lineNo": line["lineNo"],
                "sku": line["sku"],
                "category": line["category"],
                "quantity": line["quantity"],
                "amount": line["amount"],
                "earnEligible": line["earnEligible"],
            }
            for line in data["lines"]
        ],
    }


def forward(event_type: str, data) -> int:
    if event_type in ("order.recorded", "order.refunded"):
        REFS[data["customerId"]] = data["externalRef"]
    if event_type == "order.recorded":
        return loyalty_call("PUT", f"/api/v1/orders/{data['orderRef']}", order_body(data))
    if event_type == "order.refunded":
        # loyalty answers 422 ORDER_UNKNOWN until the order is there; a non-2xx makes crm retry the delivery.
        return loyalty_call(
            "PUT",
            f"/api/v1/orders/{data['orderRef']}/returns/{data['return']['returnRef']}",
            {"occurredAt": data["return"]["occurredAt"], "lines": data["return"]["lines"]},
        )
    if event_type == "customer.erased":
        ref = REFS.get(data["customerId"])
        if ref is None:
            log("skipped customer.erased: no reference was learned for that customerId")
            return 204
        return loyalty_call("POST", f"/api/v1/members/{ref}/erasure", None)
    return 204


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802 - the stdlib's spelling
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if self.path.startswith("/loyalty"):
            self._receive_loyalty(body)
            return
        timestamp = self.headers.get("Crm-Timestamp", "")
        signature = self.headers.get("Crm-Signature", "")
        if not verify(timestamp, signature, body):
            log(f"refused: a delivery whose Crm-Signature does not verify "
                f"(ts={timestamp!r} got={signature[:10]!r} len={len(signature)} body={len(body)}B)")
            self.send_response(401)
            self.end_headers()
            return
        envelope = json.loads(body)
        status = forward(envelope["type"], envelope["data"])
        log(f"forwarded {envelope['type']} -> loyalty answered {status}")
        # A non-2xx to crm is how a forwarder asks for the delivery again.
        self.send_response(200 if 200 <= status < 300 else 502)
        self.end_headers()

    def _receive_loyalty(self, body: bytes) -> None:
        """Records what loyalty sent, so the smoke can assert the Tenant saw every type it produced."""
        envelope = json.loads(body)
        RECEIVED.append({"type": envelope["type"], "data": envelope["data"], "body": body.decode()})
        log(f"received {envelope['type']}")
        self.send_response(200)
        self.end_headers()

    def do_GET(self):  # noqa: N802
        if self.path.startswith("/received"):
            payload = json.dumps(RECEIVED).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        self.send_response(404)
        self.end_headers()

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(os.environ.get("FORWARDER_PORT", "8080"))
    log(f"forwarder listening on {port}, relaying to {LOYALTY}")
    HTTPServer(("0.0.0.0", port), Handler).serve_forever()
