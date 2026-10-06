/**
 * CycleClock - 誤起動・D0短絡の統計(v0.4.11)
 *
 * フィールド運用の観測要件(2026-10-06 ユーザー要件):
 * - 誤起動回数: 「BT接続されないままスタンバイに入った」回数(不在時の誤起動=
 *   電池を静かに食う犯人)。BT接続ありのスタンバイで0にリセット(=正当な起動)。
 *   System OFF は RAM を保持しないため内部フラッシュ(InternalFS/LittleFS)へ
 *   永続化する。書込みはスタンバイ直前の1回だけなのでフラッシュ耐久は問題なし。
 * - D0短絡回数: BT接続中にD0が導通した回数。カウントは processWakeSwitch の
 *   デバウンス確定(=「[SW] Wake switch pressed」ログと同タイミング)で行う
 *   (cycleclock.ino 参照)。RAMのみ(スタンバイ入りでリセット=今回の乗車
 *   セッションの値)。
 *
 * どちらも詳細ビュー(モード3)に表示する。
 */

#include "cycleclock.h"

// --- Global Variables ---
uint32_t g_falseWakeCount = 0;
uint32_t g_d0ShortCount = 0;
bool g_everConnectedThisBoot = false;

// --- 永続化ファイル ---
// 内部フラッシュの LittleFS に uint32 1個だけ書く。固定4バイト上書きなので
// seek(0)+write で常に最新値になる(追記されない・肥大化しない)。
#define STATS_FILE "/fwake.bin"

void setupStats() {
    InternalFS.begin();
    using namespace Adafruit_LittleFS_Namespace;
    File f = InternalFS.open(STATS_FILE, FILE_O_READ);
    if (f) {
        uint32_t v = 0;
        if (f.read(&v, sizeof(v)) == (int)sizeof(v)) {
            g_falseWakeCount = v;
        }
        f.close();
    }
    logPrint("STATS", "Restored: falseWake=%lu", (unsigned long)g_falseWakeCount);
}

// 誤起動回数をフラッシュへ書き込む(スタンバイ直前に1回だけ呼ばれる)
static void persistFalseWakeCount() {
    using namespace Adafruit_LittleFS_Namespace;
    File f = InternalFS.open(STATS_FILE, FILE_O_WRITE);
    if (f) {
        f.seek(0);
        f.write((const uint8_t*)&g_falseWakeCount, sizeof(g_falseWakeCount));
        f.close();
    } else {
        logPrint("STATS", "WARN: cannot open %s for write", STATS_FILE);
    }
}

// enterSystemOff から呼ぶ(解放待ちループの前=必ず実行される位置に置くこと)。
//   BT未接続のまま寝る = 誤起動として+1(永続化)
//   BT接続した後に寝る = 正当な起動だったので0にリセット(永続化)
void commitStatsAtSleep() {
    if (g_everConnectedThisBoot) {
        if (g_falseWakeCount != 0) {
            g_falseWakeCount = 0;
            persistFalseWakeCount();
        }
        logPrint("STATS", "Sleep after BT session: falseWake reset, d0Short=%lu",
                 (unsigned long)g_d0ShortCount);
    } else {
        g_falseWakeCount++;
        persistFalseWakeCount();
        logPrint("STATS", "Sleep without BT (false wake): count=%lu, d0Short=%lu",
                 (unsigned long)g_falseWakeCount, (unsigned long)g_d0ShortCount);
    }
    // D0短絡回数は「スタンバイに入る時にリセット」の仕様
    g_d0ShortCount = 0;
}
