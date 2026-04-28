#!/bin/bash

# SEGMENT_CHARS配列ダンプツール
# bikeclock_led.inoからSEGMENT_CHARS配列を抽出して表示

if [ $# -eq 0 ]; then
    echo "SEGMENT_CHARS配列ダンプツール"
    echo ""
    echo "使い方: $0 <bikeclock_led.inoのパス>"
    echo ""
    echo "例:"
    echo "  $0 bikeclock_led.ino"
    echo "  $0 /path/to/bikeclock_led.ino"
    exit 0
fi

infile="$1"

if [ ! -f "$infile" ]; then
    echo "エラー: ファイルが見つかりません: $infile"
    exit 1
fi

# SEGMENT_CHARS配列を抽出
echo "SEGMENT_CHARS配列の解析:"
echo "======================="
echo ""

# 配列を抽出して各行を処理
grep -A 30 "const uint8_t SEGMENT_CHARS\[\] = {" "$infile" | \
grep "0x" | \
grep "//" | \
while read -r line; do
    # コードを抽出（例: 0x77）
    code=$(echo "$line" | grep -o "0x[0-9A-Fa-f]\+")

    # コメントを抽出（例: // A）
    comment=$(echo "$line" | sed 's/.*\/\/ \([A-Z]\).*/\1/')

    if [ -n "$code" ] && [ -n "$comment" ]; then
        echo "======================="
        echo "Character: $comment"
        echo "Code: $code"
        echo ""

        # seg7_art.shを使ってアスキーアートを表示
        script_dir=$(dirname "$0")
        if [ -f "$script_dir/seg7_art.sh" ]; then
            "$script_dir/seg7_art.sh" -c "$code" | tail -5
        else
            echo "（seg7_art.shが見つかりません）"
        fi
        echo ""
    fi
done

echo "======================="
echo "ダンプ完了"
