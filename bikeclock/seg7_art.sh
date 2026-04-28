#!/bin/bash

# 7セグメントディスプレイ アスキーアート表示

# セグメント文字列からアスキーアートを生成
show_segment_art() {
    local segments=$(echo "$1" | tr '[:lower:]' '[:upper:]')
    local top="   "
    local upper="   "
    local middle="   "
    local lower="   "
    local bottom="   "

    # 上セグメント（A）
    if [[ "$segments" == *A* ]]; then
        top=" _ "
    fi

    # 左上（F）と右上（B）
    if [[ "$segments" == *F* ]]; then
        upper="|  "
    fi
    if [[ "$segments" == *B* ]]; then
        upper="${upper:0:2}|"
    fi

    # 中セグメント（G）
    if [[ "$segments" == *G* ]]; then
        middle=" _ "
    fi

    # 左下（E）と右下（C）
    if [[ "$segments" == *E* ]]; then
        lower="|  "
    fi
    if [[ "$segments" == *C* ]]; then
        lower="${lower:0:2}|"
    fi

    # 下セグメント（D）
    if [[ "$segments" == *D* ]]; then
        bottom=" _ "
    fi

    echo "$top"
    echo "$upper"
    echo "$middle"
    echo "$lower"
    echo "$bottom"
}

# セグメント文字列からコードを計算（seg7_encode.sh相当）
calc_segment_code() {
    local segments="$1"
    local result=0

    for (( i=0; i<${#segments}; i++ )); do
        char="${segments:$i:1}"
        char=$(echo "$char" | tr '[:lower:]' '[:upper:]')

        case "$char" in
            A) result=$((result | (1 << 0))) ;;
            B) result=$((result | (1 << 1))) ;;
            C) result=$((result | (1 << 2))) ;;
            D) result=$((result | (1 << 3))) ;;
            E) result=$((result | (1 << 4))) ;;
            F) result=$((result | (1 << 5))) ;;
            G) result=$((result | (1 << 6))) ;;
        esac
    done

    printf "0x%02X" "$result"
}

# 引数チェック
if [ $# -eq 0 ]; then
    echo "7セグメントディスプレイ アスキーアート表示ツール"
    echo ""
    echo "使い方:"
    echo "  数字列を表示: $0 \"数字列\""
    echo "  セグメント指定: $0 -s \"セグメント文字列\""
    echo "  コード指定: $0 -c \"16進数コード\""
    echo ""
    echo "例:"
    echo "  $0 \"1234\"           # 数字を表示"
    echo "  $0 -s \"abcefg\"      # セグメント指定（Aの文字）"
    echo "  $0 -s \"abcdefg\"     # 8の文字"
    echo "  $0 -c \"0x77\"        # コード指定（Aの文字）"
    echo ""
    echo "セグメント配置:"
    echo "  AAA"
    echo " F   B"
    echo "  GGG"
    echo " E   C"
    echo "  DDD"
    exit 0
fi

# サンプル表示（ヘルプから呼ばれる場合）
if [ "$1" = "--sample" ]; then
    input="123456789"
fi

# セグメント指定モード
if [ "$1" = "-s" ] || [ "$1" = "--segments" ]; then
    if [ $# -lt 2 ]; then
        echo "エラー: セグメント文字列を指定してください"
        echo "使い方: $0 -s \"abcdefg\""
        exit 1
    fi

    segments="$2"
    code=$(calc_segment_code "$segments")

    echo "Segment pattern: $segments"
    show_segment_art "$segments"
    echo "Segment code: $code"
    exit 0
fi

# コード指定モード
if [ "$1" = "-c" ] || [ "$1" = "--code" ]; then
    if [ $# -lt 2 ]; then
        echo "エラー: コードを指定してください"
        echo "使い方: $0 -c \"0x77\""
        exit 1
    fi

    code_input="$2"

    # 16進数コードを数値に変換
    if [[ "$code_input" =~ ^0x[0-9A-Fa-f]+$ ]]; then
        code_value=$((code_input))
    elif [[ "$code_input" =~ ^[0-9A-Fa-f]+$ ]]; then
        code_value=$((16#$code_input))
    else
        echo "エラー: 無効なコード形式: $code_input"
        echo "例: 0x77, 77, 0x7F"
        exit 1
    fi

    # コードからセグメント文字列を生成
    segments=""
    if [ $((code_value & (1 << 0))) -ne 0 ]; then segments="${segments}A"; fi
    if [ $((code_value & (1 << 1))) -ne 0 ]; then segments="${segments}B"; fi
    if [ $((code_value & (1 << 2))) -ne 0 ]; then segments="${segments}C"; fi
    if [ $((code_value & (1 << 3))) -ne 0 ]; then segments="${segments}D"; fi
    if [ $((code_value & (1 << 4))) -ne 0 ]; then segments="${segments}E"; fi
    if [ $((code_value & (1 << 5))) -ne 0 ]; then segments="${segments}F"; fi
    if [ $((code_value & (1 << 6))) -ne 0 ]; then segments="${segments}G"; fi

    # セグメントが空の場合はメッセージ
    if [ -z "$segments" ]; then
        echo "Segment code: $code_input"
        echo "Segments: (none)"
        show_segment_art "$segments"
        exit 0
    fi

    echo "Segment code: $code_input"
    echo "Segments: $segments"
    show_segment_art "$segments"
    exit 0
fi

input="$1"

# 数字のみ抽出
digits=$(echo "$input" | tr -cd '0-9')

if [ -z "$digits" ]; then
    echo "エラー: 数字が含まれていません"
    exit 1
fi

# セグメントコード定義（TM1637標準）
declare -a SEG_CODES=(
    ["0"]="0x3F"  # 0b00111111
    ["1"]="0x06"  # 0b00000110
    ["2"]="0x5B"  # 0b01011011
    ["3"]="0x4F"  # 0b01001111
    ["4"]="0x66"  # 0b01100110
    ["5"]="0x6D"  # 0b01101101
    ["6"]="0x7D"  # 0b01111101
    ["7"]="0x07"  # 0b00000111
    ["8"]="0x7F"  # 0b01111111
    ["9"]="0x6F"  # 0b01101111
)

# 5行のバッファ
lines=("" "" "" "" "")

# 各数字を処理
for (( i=0; i<${#digits}; i++ )); do
    digit="${digits:$i:1}"

    # パターンを1行ずつ取得（case文で）
    case $digit in
        0) seg=(" _ " "| |" "   " "| |" " _ ") ;;
        1) seg=("   " " | " "   " " | " "   ") ;;
        2) seg=(" _ " " |" " _ " "| " " _ ") ;;
        3) seg=(" _ " " |" " _ " " |" " _ ") ;;
        4) seg=("   " "| |" " _ " " |" "   ") ;;
        5) seg=(" _ " "| " " _ " " |" " _ ") ;;
        6) seg=(" _ " "| " " _ " "| |" " _ ") ;;
        7) seg=(" _ " " |" "   " " |" "   ") ;;
        8) seg=(" _ " "| |" " _ " "| |" " _ ") ;;
        9) seg=(" _ " "| |" " _ " " |" " _ ") ;;
    esac

    # 各行をバッファに追加
    for line_num in {0..4}; do
        lines[$line_num]="${lines[$line_num]}${seg[$line_num]} "
    done
done

# アスキーアートを表示
for line in "${lines[@]}"; do
    echo "$line"
done

# セグメントコードを表示
printf "Segment codes:"
for (( i=0; i<${#digits}; i++ )); do
    digit="${digits:$i:1}"
    code="${SEG_CODES[$digit]}"
    printf " %s=%s" "$digit" "$code"
done
printf "\n"
