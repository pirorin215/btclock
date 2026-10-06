#!/opt/homebrew/bin/bash
# CycleClock 共通関数ライブラリ
# 各スクリプトから source コマンドで読み込まれます

#=============================================================================
# シリアルポートデバイスパターン（共通定義）
#=============================================================================
SERIAL_PORT_PATTERNS="/dev/cu.usbmodem* /dev/cu.usbserial* /dev/cu.wchusbserial* /dev/ttyUSB* /dev/ttyACM*"

#=============================================================================
# ログ関数
#=============================================================================
log_error() {
    printf "\033[31mエラー: %s\033[0m\n" "$1" >&2
}

log_warning() {
    printf "\033[33m警告: %s\033[0m\n" "$1" >&2
}

log_info() {
    printf "情報: %s\n" "$1"
}

#=============================================================================
# スクリプト環境初期化
#=============================================================================
init_script_env() {
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[1]}")" && pwd)"

    if [ -z "$COMMON_SH_LOADED" ]; then
        COMMON_SH_LOADED=true
    fi

    if [ -f "$SCRIPT_DIR/setting.sh" ]; then
        source "$SCRIPT_DIR/setting.sh"
    fi
}

#=============================================================================
# CycleClockポート設定チェック
#=============================================================================
# CYCLECLOCK_PORT が設定されていることをチェックし、未設定の場合は
# 利用可能なシリアルポートの候補を表示してエラー終了します
#=============================================================================
check_cycleclock_port() {
    if [ -z "$CYCLECLOCK_PORT" ]; then
        log_error "CYCLECLOCK_PORT が設定されていません。"
        echo "" >&2

        echo "利用可能なシリアルポートの候補：" >&2
        FOUND=false
        for pattern in $SERIAL_PORT_PATTERNS; do
            if [ -e "$pattern" ]; then
                printf "  \033[32m%s\033[0m\n" "$pattern" >&2
                FOUND=true
            fi
        done

        if [ "$FOUND" = false ]; then
            echo "  （見つかりませんでした）" >&2
        fi

        echo "" >&2
        echo "次の手順で設定してください：" >&2
        echo "1. cp cycleclock/setting.sh.example cycleclock/setting.sh" >&2
        echo "2. cycleclock/setting.sh を編集して CYCLECLOCK_PORT を設定" >&2
        echo "" >&2
        echo "設定例：" >&2
        echo "  CYCLECLOCK_PORT=\"/dev/cu.usbmodem212101\"  # 開発共通ポート" >&2
        exit 1
    fi
}

#=============================================================================
# コマンド存在チェック
#=============================================================================
check_command() {
    local cmd="$1"
    local install_hint="$2"

    if ! command -v "$cmd" &> /dev/null; then
        log_error "${cmd}がインストールされていません。${install_hint}"
        exit 1
    fi
}

#=============================================================================
# リトライ処理
#=============================================================================
retry_command() {
    local max_retries="$1"
    local retry_delay="$2"
    shift 2

    local attempt=1
    while [ $attempt -le $max_retries ]; do
        if "$@"; then
            return 0
        fi

        if [ $attempt -lt $max_retries ]; then
            echo "コマンド失敗 ($attempt/$max_retries)、${retry_delay}秒待機してリトライします..." >&2
            sleep $retry_delay
        fi

        attempt=$((attempt + 1))
    done

    return 1
}
