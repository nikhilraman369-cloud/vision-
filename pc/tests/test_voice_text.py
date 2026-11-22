import unittest

from nexus_pc.voice import choose, clean


class SpeechCleanup(unittest.TestCase):
    def test_numbers_become_digits_where_commands_need_them(self):
        self.assertEqual("set volume to 40", clean("set volume to forty"))
        self.assertEqual("volume 75", clean("volume seventy five"))
        self.assertEqual("skip 10 seconds", clean("skip ten seconds"))
        self.assertEqual("brightness 100", clean("brightness hundred"))
        self.assertEqual("scroll down three times", clean("scroll down three times"))   # the grammar reads these itself

    def test_lead_in_noise_is_dropped_only_before_a_command(self):
        self.assertEqual("open notepad", clean("an open notepad"))
        self.assertEqual("open youtube and play believer", clean("uh open youtube and play believer"))
        self.assertEqual("an apple a day", clean("an apple a day"))

    def test_common_mishearings(self):
        self.assertEqual("cancel", clean("cancer"))
        self.assertEqual("pause", clean("pass"))
        self.assertEqual("set volume 30", clean("said volume thirty"))
        self.assertEqual("open youtube", clean("open you tube"))
        self.assertEqual("scroll down three times then click the second video", clean("scroll down three times then quit the second video"))
        self.assertEqual("quit notepad", clean("quit notepad"))          # a real "quit" stays

    def test_background_noise_does_nothing(self):
        from nexus_pc.voice import is_noise
        for t in ["yeah", "huh", "the", "oh okay", "haan haan", ""]: self.assertTrue(is_noise(t), t)
        for t in ["pause", "next", "open notepad", "yes sign in"]: self.assertFalse(is_noise(t), t)

    def test_picks_the_alternative_that_is_a_command(self):
        self.assertEqual("set volume to 40", choose(["said volume two forty", "set volume to forty"]))
        self.assertEqual("open youtube and play believer", choose(["open youtube employee believer", "open youtube and play believer"]))
        self.assertEqual("blue sky thinking", choose(["blue sky thinking", "blew sky thinking"]))   # nothing parses: best guess


if __name__ == "__main__":
    unittest.main()
