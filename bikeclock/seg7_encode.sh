#!/bin/bash

# 7セグメントLED エンコーダー
# 使い方: ./seg7_encode.sh "abcefg"
# 例: ./seg7_encode.sh "abcefg" → 0x77

# セグメントとビットの対応
# Bit layout: 0bGFEDCBA
# A=bit0, B=bit1, C=bit2, D=bit3, E=bit4, F=bit5, G=bit6, dot=bit7

encode_segments() {
    local input="$1"
    local result=0

    # 入力文字列を1文字ずつ処理
    for (( i=0; i<${#input}; i++ )); do
        char="${input:$i:1}"
        # 小文字を大文字に変換
        char=$(echo "$char" | tr '[:lower:]' '[:upper:]')

        case "$char" in
            A) result=$((result | (1 << 0))) ;;
            B) result=$((result | (1 << 1))) ;;
            C) result=$((result | (1 << 2))) ;;
            D) result=$((result | (1 << 3))) ;;
            E) result=$((result | (1 << 4))) ;;
            F) result=$((result | (1 << 5))) ;;
            G) result=$((result | (1 << 6))) ;;
            # dotは無視（必要に応じて追加可能）
        esac
    done

    # 16進数で出力 (0x形式)
    printf "0x%02X\n" "$result"
}

# メイン処理
if [ $# -eq 0 ]; then
    echo "使い方: $0 \"セグメント文字列\""
    echo ""
    echo "例:"
    echo "  $0 \"abcefg\"    # Aのセグメント"
    echo "  $0 \"abdefg\"    # Hのセグメント"
    echo "  $0 \"abcdefg\"   # 8のセグメント"
    echo ""
    echo "セグメント配置:"
    echo "  AAA"
    echo " F   B"
    echo "  GGG"
    echo " E   C"
    echo "  DDD"
    echo ""
    echo "ビットレイアウト: 0bGFEDCBA"
    exit 1
fi

encode_segments "$1"
