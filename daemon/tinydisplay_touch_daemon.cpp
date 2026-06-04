#include <android/log.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define TAG "TinyTouchDaemon"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static const char* FALLBACK_TOUCH = "/dev/input/event5";

// Gesture thresholds (panel is 340x340). Kept small because the panel is tiny;
// anything past SWIPE_MIN is a swipe, everything else is a tap/long-press, so
// there is no dead zone where short flicks get dropped.
static const int SWIPE_MIN_MOVE2 = 28 * 28;
static const long LONGPRESS_MS = 600;

static int set_nonblock(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags >= 0) fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    return fd;
}

static int open_named_input(const char* needle, char* out, size_t out_len) {
    DIR* d = opendir("/dev/input");
    if (!d) {
        LOGW("cannot open /dev/input directory: %s", strerror(errno));
        return -1;
    }
    struct dirent* e;
    while ((e = readdir(d)) != nullptr) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        char path[128];
        snprintf(path, sizeof(path), "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
        if (fd < 0) continue;
        char name[256] = {0};
        if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) >= 0 && strstr(name, needle)) {
            LOGI("selected input %s (%s)", path, name);
            closedir(d);
            snprintf(out, out_len, "%s", path);
            return fd;
        }
        close(fd);
    }
    closedir(d);
    return -1;
}

static int open_hyn_ts(char* out, size_t out_len) {
    int fd = open_named_input("hyn_ts", out, out_len);
    if (fd >= 0) return fd;
    fd = open(FALLBACK_TOUCH, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd >= 0) {
        LOGI("fallback opened %s", FALLBACK_TOUCH);
        snprintf(out, out_len, "%s", FALLBACK_TOUCH);
    } else {
        LOGW("fallback open %s failed: %s", FALLBACK_TOUCH, strerror(errno));
    }
    return fd;
}

// There are TWO "yft-gpio-keys" input nodes on this device (event2 and
// event7); the physical F1/F2 buttons can emit on either, so we must open and
// poll all of them rather than just the first match.
static int open_all_named(const char* needle, int* fds, int max_fds) {
    DIR* d = opendir("/dev/input");
    if (!d) return 0;
    int n = 0;
    struct dirent* e;
    while ((e = readdir(d)) != nullptr && n < max_fds) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        char path[128];
        snprintf(path, sizeof(path), "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
        if (fd < 0) continue;
        char name[256] = {0};
        if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) >= 0 && strstr(name, needle)) {
            LOGI("selected key input %s (%s)", path, name);
            fds[n++] = fd;
        } else {
            close(fd);
        }
    }
    closedir(d);
    return n;
}

static void send(const char* action, const char* extras) {
    char cmd[512];
    snprintf(cmd, sizeof(cmd),
             "/system/bin/cmd activity start-foreground-service "
             "-n com.tinydisplay/.TinyDisplayService -a %s %s >/dev/null 2>/dev/null",
             action, extras ? extras : "");
    int rc = system(cmd);
    LOGI("%s rc=%d", action, rc);
}

static void send_tap(int x, int y) {
    char ex[64];
    snprintf(ex, sizeof(ex), "--ei x %d --ei y %d", x, y);
    LOGI("tap %d,%d", x, y);
    send("com.tinydisplay.action.REAR_TAP", ex);
}

static void send_longpress(int x, int y) {
    char ex[64];
    snprintf(ex, sizeof(ex), "--ei x %d --ei y %d", x, y);
    LOGI("longpress %d,%d", x, y);
    send("com.tinydisplay.action.REAR_LONGPRESS", ex);
}

static void send_swipe(int sx, int sy, int ex, int ey) {
    char e[128];
    snprintf(e, sizeof(e), "--ei sx %d --ei sy %d --ei ex %d --ei ey %d", sx, sy, ex, ey);
    LOGI("swipe %d,%d -> %d,%d", sx, sy, ex, ey);
    send("com.tinydisplay.action.REAR_SWIPE", e);
}

static void send_key(int code) {
    if (code == KEY_F1) { LOGI("F1 pressed"); send("com.tinydisplay.action.KEY_F1", ""); }
    else if (code == KEY_F2) { LOGI("F2 pressed"); send("com.tinydisplay.action.KEY_F2", ""); }
}

static long ms_between(const struct timeval* a, const struct timeval* b) {
    return (b->tv_sec - a->tv_sec) * 1000L + (b->tv_usec - a->tv_usec) / 1000L;
}

// ── Live touch stream (abstract UNIX socket to TinyDisplayService) ────────
// When the service is listening we stream raw down/move/up so the UI can make
// the next page follow the finger. If we cannot connect we silently fall back
// to the discrete tap/swipe intents below.
static const char* STREAM_NAME = "tinydisplay_touch";
static int g_stream_fd = -1;

static bool stream_connect() {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return false;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';  // abstract namespace
    strncpy(addr.sun_path + 1, STREAM_NAME, sizeof(addr.sun_path) - 2);
    socklen_t len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(STREAM_NAME);
    if (connect(fd, (struct sockaddr*)&addr, len) < 0) { close(fd); return false; }
    g_stream_fd = fd;
    LOGI("connected live touch stream");
    return true;
}

static bool stream_send(const char* line) {
    if (g_stream_fd < 0) return false;
    size_t n = strlen(line);
    ssize_t w = write(g_stream_fd, line, n);
    if (w != (ssize_t)n) { close(g_stream_fd); g_stream_fd = -1; return false; }
    return true;
}

static bool stream_connected() { return g_stream_fd >= 0; }

static void classify(int sx, int sy, int ex, int ey, long heldMs) {
    if (sx < 0 || sy < 0 || ex < 0 || ey < 0) return;
    int dx = ex - sx, dy = ey - sy;
    int d2 = dx * dx + dy * dy;
    if (d2 >= SWIPE_MIN_MOVE2) send_swipe(sx, sy, ex, ey);
    else if (heldMs >= LONGPRESS_MS) send_longpress(ex, ey);
    else send_tap(ex, ey);
}

struct TouchState {
    int x = -1, y = -1, sx = -1, sy = -1;
    int lastSentX = -1, lastSentY = -1;
    bool touching = false;
    bool startPending = false;
    bool streaming = false;  // streaming this particular gesture
    struct timeval downTime = {0, 0};
};

static void stream_begin(TouchState& st) {
    char line[64];
    snprintf(line, sizeof(line), "D %d %d\n", st.sx, st.sy);
    st.streaming = stream_send(line);
    st.lastSentX = st.sx; st.lastSentY = st.sy;
}

static void stream_move(TouchState& st) {
    if (!st.streaming) return;
    if (abs(st.x - st.lastSentX) + abs(st.y - st.lastSentY) < 2) return;
    char line[64];
    snprintf(line, sizeof(line), "M %d %d\n", st.x, st.y);
    if (stream_send(line)) { st.lastSentX = st.x; st.lastSentY = st.y; }
    else st.streaming = false;
}

static void touch_down(TouchState& st) {
    // Try to (re)connect lazily so the feature works as soon as the service
    // comes up, without busy-looping when it is absent.
    if (!stream_connected()) stream_connect();
    if (stream_connected()) stream_begin(st);
}

static void touch_up(TouchState& st, const struct input_event& ev) {
    long held = ms_between(&st.downTime, &ev.time);
    if (st.streaming) {
        char line[64];
        snprintf(line, sizeof(line), "U %d %d %ld\n", st.x, st.y, held);
        stream_send(line);
    } else {
        classify(st.sx, st.sy, st.x, st.y, held);
    }
    st.streaming = false;
}

// Capture the swipe start as soon as we have valid coordinates after a down
// event. Some drivers report TRACKING_ID/BTN_TOUCH before the first X/Y, which
// used to leave the start point at -1 and silently drop the whole swipe.
static void maybe_capture_start(TouchState& st, const struct input_event& ev) {
    if (st.startPending && st.x >= 0 && st.y >= 0) {
        st.sx = st.x; st.sy = st.y; st.downTime = ev.time; st.startPending = false;
        touch_down(st);
    }
}

static void handle_touch_event(TouchState& st, const struct input_event& ev) {
    if (ev.type == EV_ABS) {
        if (ev.code == ABS_MT_POSITION_X || ev.code == ABS_X) { st.x = ev.value; maybe_capture_start(st, ev); stream_move(st); }
        else if (ev.code == ABS_MT_POSITION_Y || ev.code == ABS_Y) { st.y = ev.value; maybe_capture_start(st, ev); stream_move(st); }
        else if (ev.code == ABS_MT_TRACKING_ID) {
            if (ev.value >= 0) {
                if (!st.touching) { st.touching = true; st.startPending = true; st.sx = st.sy = -1; maybe_capture_start(st, ev); }
            } else if (st.touching) {
                st.touching = false;
                touch_up(st, ev);
                st.sx = st.sy = -1; st.startPending = false;
            }
        }
    } else if (ev.type == EV_KEY && ev.code == BTN_TOUCH) {
        if (ev.value) {
            if (!st.touching) { st.touching = true; st.startPending = true; st.sx = st.sy = -1; maybe_capture_start(st, ev); }
        } else if (st.touching) {
            st.touching = false;
            touch_up(st, ev);
            st.sx = st.sy = -1; st.startPending = false;
        }
    }
}

static void handle_key_event(const struct input_event& ev) {
    if (ev.type == EV_KEY && ev.value == 1 && (ev.code == KEY_F1 || ev.code == KEY_F2)) send_key(ev.code);
}

int main() {
    LOGI("starting native rear-touch daemon");
    for (;;) {
        char touchPath[128] = {0};
        int touchFd = open_hyn_ts(touchPath, sizeof(touchPath));
        if (touchFd < 0) { sleep(3); continue; }
        int grab = ioctl(touchFd, EVIOCGRAB, 1);
        LOGI("opened %s; EVIOCGRAB rc=%d", touchPath, grab);

        int keyFds[4];
        int keyCount = open_all_named("yft-gpio-keys", keyFds, 4);
        if (keyCount == 0) LOGW("F1/F2 key input not found; photo button disabled");
        else LOGI("opened %d F1/F2 key input(s)", keyCount);

        TouchState st;
        struct pollfd fds[5];
        fds[0].fd = touchFd; fds[0].events = POLLIN;
        for (int i = 0; i < keyCount; i++) { fds[i + 1].fd = keyFds[i]; fds[i + 1].events = POLLIN; }
        int nfds = 1 + keyCount;

        bool retry = false;
        while (!retry) {
            int pr = poll(fds, nfds, -1);
            if (pr < 0) {
                if (errno == EINTR) continue;
                LOGW("poll failed: %s", strerror(errno));
                break;
            }
            for (int i = 0; i < nfds; i++) {
                if (!(fds[i].revents & POLLIN)) continue;
                struct input_event ev;
                while (read(fds[i].fd, &ev, sizeof(ev)) == sizeof(ev)) {
                    if (fds[i].fd == touchFd) handle_touch_event(st, ev);
                    else handle_key_event(ev);
                }
                if (errno != EAGAIN && errno != EWOULDBLOCK && errno != 0) {
                    LOGW("read ended on fd %d: %s", fds[i].fd, strerror(errno));
                    retry = true;
                    break;
                }
            }
        }
        close(touchFd);
        for (int i = 0; i < keyCount; i++) close(keyFds[i]);
        sleep(1);
    }
}
