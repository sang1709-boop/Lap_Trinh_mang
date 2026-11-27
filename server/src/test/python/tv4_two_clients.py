"""Run against a test server with a leader and member in the same group (see docs/tv4.md)."""
import base64
import datetime as dt
import io
import json
import os
import socket
import struct
import uuid
import zipfile
from contextlib import ExitStack


class Client:
    def __init__(self, stack):
        sock = stack.enter_context(socket.create_connection(
            (os.getenv("TV4_HOST", "127.0.0.1"), int(os.getenv("TV4_PORT", "5050"))), timeout=10))
        self.stream = stack.enter_context(sock.makefile("rwb"))
        self.seq = 0
        self.events = []

    def read(self):
        size, = struct.unpack(">I", self.stream.read(4))
        return json.loads(self.stream.read(size))

    def call(self, action, data=None, error=None):
        self.seq += 1
        ident = str(self.seq)
        raw = json.dumps(dict(type="request", requestId=ident, action=action, data=data or {})).encode()
        self.stream.write(struct.pack(">I", len(raw)) + raw)
        self.stream.flush()
        while True:
            reply = self.read()
            if reply["type"] == "event":
                self.events.append(reply)
                continue
            assert reply["requestId"] == ident and reply.get("error") == error, (action, reply)
            return reply.get("data", {})

    def login(self, prefix):
        return self.call("LOGIN", dict(studentId=os.environ[prefix+"_ID"], password=os.environ[prefix+"_PASSWORD"]))


def main():
    with ExitStack() as stack:
        a, b = Client(stack), Client(stack)
        a.call("NOTIFICATION_LIST", error="UNAUTHENTICATED")
        leader = a.login("TV4_LEADER")["userId"]
        member = b.login("TV4_MEMBER")["userId"]
        group = int(os.environ["TV4_GROUP"])
        today = dt.datetime.now(dt.timezone(dt.timedelta(hours=7))).date()
        due = today + dt.timedelta(days=1)
        form = dict(groupId=group, period="week", date=str(due), deadline=str(due), hours=3,
                    assigneeId=member, title="TV4 test " + dt.datetime.now().isoformat(timespec="seconds"))
        b.call("NOTIFICATION_SUBSCRIBE")
        before = b.call("NOTIFICATION_LIST")["unreadCount"]
        b.call("WORKLOAD_SUGGEST", form, "FORBIDDEN")
        b.call("CAPACITY_SAVE", dict(form, userId=leader), "FORBIDDEN")
        own = b.call("WORKLOAD_VIEW", form)["members"]
        assert [r["userId"] for r in own] == [member]
        b.call("PROGRESS_GROUP", form, "FORBIDDEN")
        choices = a.call("WORKLOAD_SUGGEST", form)["candidates"]
        assert member in [r["userId"] for r in choices]
        sim = a.call("WORKLOAD_SIMULATE", form)["simulation"]
        assert sim["afterHours"] == sim["usedHours"] + 3
        assert b.call("NOTIFICATION_LIST")["unreadCount"] == before
        form["confirmationId"] = str(uuid.uuid4())
        task = a.call("TASK_ASSIGN_CONFIRM", form)["taskId"]
        assert a.call("TASK_ASSIGN_CONFIRM", form)["taskId"] == task
        if not b.events:
            b.events.append(b.read())
        assert b.events[-1]["action"] == "INBOX_CHANGED"
        inbox = b.call("NOTIFICATION_LIST", dict(kind="task_assigned", status="unread"))
        notice = next(x for x in inbox["notifications"] if x["targetId"] == task)
        assert inbox["unreadCount"] == before + 1 and notice["isRead"] == 0
        assert not any(x["targetId"] == task for x in a.call("NOTIFICATION_LIST", dict(kind="task_assigned"))["notifications"])
        a.call("NOTIFICATION_OPEN", dict(notificationId=notice["id"]), "NOTIFICATION_NOT_FOUND")
        for _ in range(2):
            opened = b.call("NOTIFICATION_OPEN", dict(notificationId=notice["id"]))
            assert opened["notification"]["isRead"] == 1 and opened["unreadCount"] == before
            assert opened["task"]["id"] == task
        b.call("LOGOUT")
        b = Client(stack)
        b.login("TV4_MEMBER")
        assert any(x["id"] == notice["id"] for x in b.call("NOTIFICATION_LIST", dict(status="read"))["notifications"])
        mine = b.call("PROGRESS_MINE", form)
        assert task in [x["task_id"] for x in mine["tasks"]]
        report = dict(groupId=group, **{"from": str(today), "to": str(today)})
        assert a.call("REPORT_VIEW", report)["summary"]["total"] >= 1
        for fmt in ["docx", "pdf"]:
            result = a.call("REPORT_EXPORT", dict(report, format=fmt))
            body = base64.b64decode(result["content"])
            if fmt == "docx":
                assert b"wordprocessingml" in zipfile.ZipFile(io.BytesIO(body)).read("word/document.xml")
            else:
                assert body.startswith(b"%PDF-") and b"%%EOF" in body
        b.call("LOGOUT")
        offline_task = a.call("TASK_ASSIGN_CONFIRM", dict(form, confirmationId=str(uuid.uuid4())))["taskId"]
        b = Client(stack)
        b.login("TV4_MEMBER")
        offline_inbox = b.call("NOTIFICATION_LIST", dict(status="unread", kind="task_assigned"))
        offline_notice = next(x for x in offline_inbox["notifications"] if x["targetId"] == offline_task)
        assert b.call("NOTIFICATION_OPEN", dict(notificationId=offline_notice["id"]))["task"]["id"] == offline_task
        a.call("LOGOUT")
        b.call("LOGOUT")
        print("PASS: simulation, assignment, push, recipient, read twice, reconnect, progress, Word, PDF and offline delivery")


if __name__ == "__main__":
    main()
