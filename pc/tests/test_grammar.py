import unittest
from nexus_pc.grammar import understand, split_commands
from nexus_pc.actions import Action as A


def s(text): return [str(a) for a in understand(text)]


class Understanding(unittest.TestCase):
    def check(self, text, *expected):
        got = understand(text)
        self.assertEqual([str(e) for e in expected], [str(a) for a in got], f"\n  said: {text}")

    def test_open(self):
        self.check("open youtube", A("open_url", "youtube"))
        self.check("youtube kholo", A("open_url", "youtube"))
        self.check("yt khol do", A("open_url", "youtube"))
        self.check("open notepad", A("open_app", "notepad"))
        self.check("notepad kholo", A("open_app", "notepad"))
        self.check("launch chrome", A("open_app", "chrome"))
        self.check("open youtube music", A("open_url", "youtube music"))
        self.check("go to gmail", A("open_url", "gmail"))
        self.check("open calculator please", A("open_app", "calculator"))
        self.check("hey nexus open vs code", A("open_app", "vs code"))
        self.check("open github.com", A("open_url", "github.com"))
        self.check("open my downloads folder", A("folder", "downloads"))

    def test_play(self):
        self.check("play believer", A("play", "believer", "ytmusic"))
        self.check("play skyfall on youtube music", A("play", "skyfall", "ytmusic"))
        self.check("play skyfall on youtube", A("play", "skyfall", "youtube"))
        self.check("tum hi ho chalao", A("play", "tum hi ho", "ytmusic"))
        self.check("youtube pe tum hi ho chalao", A("play", "tum hi ho", "youtube"))
        self.check("play iron man trailer", A("play", "iron man trailer", "youtube"))
        self.check("play the second video", A("click", "", "video", 2))
        self.check("pause", A("media", "pause"))
        self.check("gaana roko", A("media", "pause"))
        self.check("next song", A("media", "next"))
        self.check("skip 10 seconds", A("media", "forward", n=10))

    def test_search(self):
        self.check("search cat videos on youtube", A("search", "cat videos", "youtube"))
        self.check("youtube pe arijit singh search karo", A("search", "arijit singh", "youtube"))
        self.check("google weather today", A("search", "weather today", "google"))
        self.check("search for best laptops", A("search", "best laptops", "google"))

    def test_scroll_click_keys(self):
        self.check("scroll down", A("scroll", "down"))
        self.check("scroll down 3 times", A("scroll", "down", n=3))
        self.check("teen baar neeche scroll karo", A("scroll", "down", n=3))
        self.check("thoda upar", A("scroll", "up"))
        self.check("scroll to the top", A("scroll", "top"))
        self.check("click the third result", A("click", "", "result", 3))
        self.check("click on the second video", A("click", "", "video", 2))
        self.check("click on sign in", A("click", "sign in"))
        self.check("sign in pe click karo", A("click", "sign in"))
        self.check("double click on readme", A("double_click", "readme"))
        self.check("press ctrl c", A("keys", "ctrl+c"))
        self.check("select all", A("keys", "ctrl+a"))
        self.check("copy karo", A("keys", "ctrl+c"))
        self.check("press enter", A("keys", "enter"))

    def test_typing(self):
        self.check("type hello world", A("type", "hello world"))
        self.check("type okay, I'll do it later", A("type", "okay, i'll do it later"))
        self.check("type see you tomorrow and press enter", A("type", "see you tomorrow"), A("keys", "enter"))
        self.check("hello likho", A("type", "hello"))

    def test_tabs_windows_system(self):
        self.check("new tab", A("tab", "new"))
        self.check("close this tab", A("tab", "close"))
        self.check("go to the third tab", A("tab", "go", n=3))
        self.check("refresh", A("tab", "refresh"))
        self.check("go back", A("tab", "back"))
        self.check("minimize", A("window", "minimize"))
        self.check("close notepad", A("window", "close", "notepad"))
        self.check("show desktop", A("window", "desktop"))
        self.check("take a screenshot", A("system", "screenshot"))
        self.check("lock the pc", A("system", "lock"))
        self.check("volume 40", A("volume", "set", n=40))
        self.check("volume badhao", A("volume", "up", n=10))
        self.check("mute", A("volume", "mute"))
        self.check("brightness 70", A("system", "brightness", n=70))

    def test_many_commands_at_once(self):
        self.check("open chrome, go to youtube and play believer",
                   A("open_app", "chrome"), A("open_url", "youtube"), A("play", "believer", "youtube"))
        self.check("youtube kholo aur tum hi ho chalao", A("open_url", "youtube"), A("play", "tum hi ho", "youtube"))
        self.check("open notepad then type meeting at 5 and save",
                   A("open_app", "notepad"), A("type", "meeting at 5"), A("keys", "ctrl+s"))
        self.check("scroll down 3 times, then click the second video", A("scroll", "down", n=3), A("click", "", "video", 2))
        self.check("open gmail\nsearch invoice\nclick the first email",
                   A("open_url", "gmail"), A("search", "invoice", "google"), A("click", "", "email", 1))

    def test_never_empty(self):
        for t in ["do the thing with the blue stuff", "subscribe to this channel", "sort by date"]:
            self.assertTrue(understand(t), t)


class Splitting(unittest.TestCase):
    def test_typed_text_stays_together(self):
        self.assertEqual(["type milk, eggs and bread"], split_commands("type milk, eggs and bread"))
        self.assertEqual(["type hi there", "press enter"], split_commands("type hi there and press enter"))


if __name__ == "__main__":
    unittest.main()
