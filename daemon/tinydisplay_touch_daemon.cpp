#include <android/log.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define TAG "TinyTouchDaemon"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static const char* FALLBACK = "/dev/input/event5";

// Gesture thresholds (panel is 340x340).
static const int TAP_MAX_MOVE2 = 35 * 35;   // <= this squared distance counts as a tap
static const int SWIPE_MIN_MOVE2 = 55 * 55;  // >= this squared distance counts as a swipe
static const long LONGPRESS_MS = 600;        // hold longer than this (with little move) = long press

static int open_hyn_ts(char* out, size_t out_len) {
    DIR* d = opendir("/dev/input");
    if (d) {
        struct dirent* e;
        while ((e = readdir(d)) != nullptr) {
            if (strncmp(e->d_name, "event", 5) != 0) continue;
            char path[128];
            snprintf(path, sizeof(path), "/dev/input/%s", e->d_name);
            int fd = open(path, O_RDONLY | O_CLOEXEC);
            if (fd < 0) continue;
            char name[256] = {0};
            if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) >= 0) {
                if (strstr(name, "hyn_ts")) {
                    LOGI("selected rear touch %s (%s)", path, name);
                    closedir(d);
                    snprintf(out, out_len, "%s", path);
                    return fd;
                }
            }
            close(fd);
        }
        closedir(d);
    } else {
        LOGW("cannot open /dev/input directory: %s", strerror(errno));
    }

    int fd = open(FALLBACK, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        LOGI("fallback opened %s", FALLBACK);
        snprintf(out, out_len, "%s", FALLBACK);
    } else {
        LOGW("fallback open %s failed: %s", FALLBACK, strerror(errno));
    }
    return fd;
}

static void send(const char* action, const char* extras) {
    char cmd[512];
    snprintf(cmd, sizeof(cmd),
             "/system/bin/cmd activity start-foreground-service "
             "-n com.tinydisplay/.TinyDisplayService -a %s %s >/dev/null 2>/dev/null",
             action, extras);
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

static long ms_between(const struct timeval* a, const struct timeval* b) {
    return (b->tv_sec - a->tv_sec) * 1000L + (b->tv_usec - a->tv_usec) / 1000L;
}

static void classify(int sx, int sy, int ex, int ey, long heldMs) {
    if (sx < 0 || sy < 0 || ex < 0 || ey < 0) return;
    int dx = ex - sx, dy = ey - sy;
    int d2 = dx * dx + dy * dy;
    if (d2 >= SWIPE_MIN_MOVE2) {
        send_swipe(sx, sy, ex, ey);
    } else if (d2 <= TAP_MAX_MOVE2) {
        if (heldMs >= LONGPRESS_MS) send_longpress(ex, ey);
        else send_tap(ex, ey);
    }
    // In-between small drags are ignored as noise.
}

int main() {
    LOGI("starting native rear-touch daemon");
    for (;;) {
        char path[128] = {0};
        int fd = open_hyn_ts(path, sizeof(path));
        if (fd < 0) {
            sleep(3);
            continue;
        }
        int grab = ioctl(fd, EVIOCGRAB, 1);
        LOGI("opened %s; EVIOCGRAB rc=%d", path, grab);

        int x = -1, y = -1, sx = -1, sy = -1;
        bool touching = false;
        struct timeval downTime = {0, 0};
        struct input_event ev;
        while (read(fd, &ev, sizeof(ev)) == sizeof(ev)) {
            if (ev.type == EV_ABS) {
                if (ev.code == ABS_MT_POSITION_X || ev.code == ABS_X) x = ev.value;
                else if (ev.code == ABS_MT_POSITION_Y || ev.code == ABS_Y) y = ev.value;
                else if (ev.code == ABS_MT_TRACKING_ID) {
                    if (ev.value >= 0) {
                        if (!touching) { touching = true; sx = x; sy = y; downTime = ev.time; }
                    } else if (touching) {
                        touching = false;
                        classify(sx, sy, x, y, ms_between(&downTime, &ev.time));
                        sx = sy = -1;
                    }
                }
            } else if (ev.type == EV_KEY && ev.code == BTN_TOUCH) {
                if (ev.value) {
                    if (!touching) { touching = true; sx = x; sy = y; downTime = ev.time; }
                } else if (touching) {
                    touching = false;
                    classify(sx, sy, x, y, ms_between(&downTime, &ev.time));
                    sx = sy = -1;
                }
            }
        }
        LOGW("read loop ended for %s: %s; retrying", path, strerror(errno));
        close(fd);
        sleep(1);
    }
}
