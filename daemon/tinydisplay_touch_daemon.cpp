#include <android/log.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define TAG "TinyTouchDaemon"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static const char* FALLBACK_TOUCH = "/dev/input/event5";

// Gesture thresholds (panel is 340x340).
static const int TAP_MAX_MOVE2 = 35 * 35;
static const int SWIPE_MIN_MOVE2 = 55 * 55;
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

static int open_f2_keys(char* out, size_t out_len) {
    return open_named_input("yft-gpio-keys", out, out_len);
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

static void classify(int sx, int sy, int ex, int ey, long heldMs) {
    if (sx < 0 || sy < 0 || ex < 0 || ey < 0) return;
    int dx = ex - sx, dy = ey - sy;
    int d2 = dx * dx + dy * dy;
    if (d2 >= SWIPE_MIN_MOVE2) send_swipe(sx, sy, ex, ey);
    else if (d2 <= TAP_MAX_MOVE2) {
        if (heldMs >= LONGPRESS_MS) send_longpress(ex, ey);
        else send_tap(ex, ey);
    }
}

struct TouchState {
    int x = -1, y = -1, sx = -1, sy = -1;
    bool touching = false;
    struct timeval downTime = {0, 0};
};

static void handle_touch_event(TouchState& st, const struct input_event& ev) {
    if (ev.type == EV_ABS) {
        if (ev.code == ABS_MT_POSITION_X || ev.code == ABS_X) st.x = ev.value;
        else if (ev.code == ABS_MT_POSITION_Y || ev.code == ABS_Y) st.y = ev.value;
        else if (ev.code == ABS_MT_TRACKING_ID) {
            if (ev.value >= 0) {
                if (!st.touching) { st.touching = true; st.sx = st.x; st.sy = st.y; st.downTime = ev.time; }
            } else if (st.touching) {
                st.touching = false;
                classify(st.sx, st.sy, st.x, st.y, ms_between(&st.downTime, &ev.time));
                st.sx = st.sy = -1;
            }
        }
    } else if (ev.type == EV_KEY && ev.code == BTN_TOUCH) {
        if (ev.value) {
            if (!st.touching) { st.touching = true; st.sx = st.x; st.sy = st.y; st.downTime = ev.time; }
        } else if (st.touching) {
            st.touching = false;
            classify(st.sx, st.sy, st.x, st.y, ms_between(&st.downTime, &ev.time));
            st.sx = st.sy = -1;
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

        char keyPath[128] = {0};
        int keyFd = open_f2_keys(keyPath, sizeof(keyPath));
        if (keyFd >= 0) LOGI("opened F2 key input %s", keyPath);
        else LOGW("F2 key input not found; photo button disabled");

        TouchState st;
        struct pollfd fds[2];
        fds[0].fd = touchFd; fds[0].events = POLLIN;
        fds[1].fd = keyFd;   fds[1].events = keyFd >= 0 ? POLLIN : 0;
        int nfds = keyFd >= 0 ? 2 : 1;

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
        if (keyFd >= 0) close(keyFd);
        sleep(1);
    }
}
