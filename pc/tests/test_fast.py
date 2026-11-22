import unittest
from nexus_pc.fast import EarlyTrigger
from nexus_pc.actions import Action as A


class Fast(unittest.TestCase):
    def feed(self, *partials):
        t = EarlyTrigger(); fired = []
        for p in partials:
            a = t.on_partial(p)
            if a: fired.append((p, a))
        return t, fired

    def test_fires_the_moment_the_name_is_complete(self):
        t, fired = self.feed("open", "open note", "open notepad")
        self.assertEqual([("open notepad", A("open_app", "notepad"))], fired)

    def test_waits_a_beat_when_the_name_could_continue(self):
        t, fired = self.feed("open", "open you", "open youtube")
        self.assertEqual([], fired)                       # could still be "youtube music"
        self.assertEqual(A("open_url", "youtube"), t.on_pause())

    def test_youtube_music_not_mistaken_for_youtube(self):
        t, fired = self.feed("open youtube", "open youtube music")
        self.assertEqual([("open youtube music", A("open_url", "youtube music"))], fired)

    def test_final_does_not_repeat_what_already_ran(self):
        t, fired = self.feed("open notepad", "open notepad and type", "open notepad and type hi")
        self.assertEqual(1, len(fired))
        self.assertEqual([A("type", "hi")], t.on_final("open notepad and type hi"))

    def test_media_and_volume_are_instant(self):
        _, fired = self.feed("pause")
        self.assertEqual(A("media", "pause"), fired[0][1])
        _, fired = self.feed("volume", "volume up")
        self.assertEqual(A("volume", "up", n=10), fired[0][1])

    def test_nothing_risky_fires_early(self):
        _, fired = self.feed("type", "type hello", "search", "search cats", "play", "play believer", "shut down", "close")
        self.assertEqual([], fired)


if __name__ == "__main__":
    unittest.main()
