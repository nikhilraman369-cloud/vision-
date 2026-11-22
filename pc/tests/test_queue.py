import threading
import time
import unittest

from nexus_pc.actions import Action as A
from nexus_pc.cmdqueue import CommandQueue, restore_case, is_cancel
from nexus_pc.executor import Result


class FakeExecutor:
    """Records what it was asked to do; 'wait' honours cancel like the real one; open_app 'missing' fails."""
    def __init__(self):
        self.ran, self.cancel = [], threading.Event()

    def run(self, a):
        self.ran.append(a)
        if a.kind == "wait":
            return Result(not self.cancel.wait(a.n), "waited")
        if a.kind == "open_app" and a.target == "missing": return Result(False, "couldn't find 'missing'")
        if a.kind == "click": return Result(False, "not on screen")
        return Result(True, "ok")


def make(confirm=None):
    ex = FakeExecutor()
    events = []
    q = CommandQueue(ex, on_event=events.append, confirm=confirm).start()
    return q, ex, events


class Queue(unittest.TestCase):
    def test_runs_everything_in_order(self):
        q, ex, _ = make()
        q.submit_text("open notepad, type hello and press enter")
        q.submit_text("volume up")
        self.assertTrue(q.wait_idle(5))
        self.assertEqual([A("open_app", "notepad"), A("type", "hello"), A("keys", "enter"), A("volume", "up", n=10)], ex.ran)
        q.stop()

    def test_cancel_drops_waiting_and_interrupts_the_current_wait(self):
        q, ex, _ = make()
        q.submit_text("wait 5 seconds then type nope")
        time.sleep(0.2)
        t = time.time()
        self.assertTrue(q.submit_text("cancel") is None)
        self.assertTrue(q.wait_idle(3))
        self.assertLess(time.time() - t, 1.0)
        self.assertEqual([A("wait", n=5)], ex.ran)          # "type nope" never ran
        q.submit_text("type after")                          # the queue still works afterwards
        self.assertTrue(q.wait_idle(3))
        self.assertEqual(A("type", "after"), ex.ran[-1])
        q.stop()

    def test_failed_open_drops_the_rest_of_that_batch_only(self):
        q, ex, events = make()
        q.submit([A("open_app", "missing"), A("type", "secret")])
        q.submit([A("type", "next batch")])
        self.assertTrue(q.wait_idle(3))
        self.assertEqual([A("open_app", "missing"), A("type", "next batch")], ex.ran)
        self.assertIn("skipped", [e.kind for e in events])
        q.stop()

    def test_a_failed_click_does_not_stop_the_batch(self):
        q, ex, _ = make()
        q.submit([A("click", "nothing"), A("type", "still")])
        q.wait_idle(3)
        self.assertEqual(A("type", "still"), ex.ran[-1])
        q.stop()

    def test_risky_steps_need_a_yes(self):
        q, ex, events = make(confirm=None)
        q.submit_text("shut down")
        q.wait_idle(3)
        self.assertEqual([], ex.ran)
        self.assertIn("needs your OK", [e for e in events if e.kind == "failed"][0].job.result.msg)
        q.stop()
        q, ex, _ = make(confirm=lambda a: a.target == "restart")
        q.submit_text("restart the pc"); q.submit_text("go to sleep")
        q.wait_idle(3)
        self.assertEqual([A("system", "restart")], ex.ran)
        q.stop()

    def test_typed_text_keeps_your_capitals(self):
        self.assertEqual([A("type", "Hello World"), A("keys", "enter")],
                         restore_case("Type Hello World and press enter", [A("type", "hello world"), A("keys", "enter")]))

    def test_cancel_words(self):
        for t in ["stop", "cancel", "never mind", "ruko", "rehne do", "please cancel"]: self.assertTrue(is_cancel(t), t)
        for t in ["stop the video", "cancel button", "open notepad"]: self.assertFalse(is_cancel(t), t)


if __name__ == "__main__":
    unittest.main()
