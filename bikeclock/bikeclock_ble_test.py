#!/usr/bin/env python3
"""
BikeClock BLE接続テストツール

BikeClockデバイスへのBLE接続とサービス/キャラクタリスティックの確認、
時刻同期コマンドのテストを行います。
"""

import asyncio
import time
import argparse
from datetime import datetime, timezone, timedelta
from bleak import BleakClient, BleakScanner

# 設定
DEVICE_NAME_PREFIX = "BikeClock"
SERVICE_UUID = "4fafc201-1fb5-459e-8fcc-c5c9c331914c"
COMMAND_UUID = "beb5483e-36e1-4688-b7f5-ea07361b26a0"
# Use Command Characteristic for both command and response
RESPONSE_UUID = "beb5483e-36e1-4688-b7f5-ea07361b26a0"  # Same as command (bidirectional)

# グローバル変数
received_response = None
response_event = asyncio.Event()
test_times = []  # テスト用時刻リスト

def datetime_to_jst_timestamp(dt_str):
    """
    日付時刻文字列をJSTタイムスタンプに変換
    スマホアプリ（BTClockMob）と同じ挙動：JST日時を「JSTとしてのUnix timestamp」に変換

    Args:
        dt_str: "YYYY-MM-DD HH:MM:SS" 形式の文字列（JST）

    Returns:
        int: JSTとしてのUnix timestamp（Unix timestamp + 9時間）
    """
    try:
        # JSTとしてパース（タイムゾーンなし）
        dt = datetime.strptime(dt_str, "%Y-%m-%d %H:%M:%S")

        # JSTタイムゾーンを指定
        jst_tz = timezone(timedelta(hours=9))
        dt_jst = dt.replace(tzinfo=jst_tz)

        # Unix timestampに変換（JST→UTCの変換が自動で行われる）
        utc_timestamp = int(dt_jst.timestamp())

        # マイコンは「JSTとしてのUnix timestamp」を期待しているので+9時間
        jst_timestamp = utc_timestamp + 32400  # +9時間

        return jst_timestamp
    except ValueError as e:
        print(f"❌ 日付時刻パースエラー: {e}")
        return None

def get_midnight_test_times():
    """
    深夜0時の境界テスト用時刻リストを生成

    Returns:
        list: JSTタイムスタンプのリスト
    """
    today = datetime.now(timezone(timedelta(hours=9)))
    base_date = today.replace(hour=0, minute=0, second=0, microsecond=0)

    test_cases = [
        ("23:59:59 (前日深夜)", base_date - timedelta(seconds=1)),
        ("00:00:00 (当日0時)", base_date),
        ("00:00:01 (当日0時1秒)", base_date + timedelta(seconds=1)),
        ("01:00:00 (当日1時)", base_date + timedelta(hours=1)),
        ("08:59:59 (当日8時59分)", base_date + timedelta(hours=8, minutes=59, seconds=59)),
        ("09:00:00 (当日9時)", base_date + timedelta(hours=9)),
    ]

    results = []
    for desc, dt in test_cases:
        ts = int(dt.timestamp())
        results.append((desc, ts))

    return results

def print_header(title):
    """ヘッダーを表示"""
    print("\n" + "=" * 60)
    print(f" {title}")
    print("=" * 60)

async def notification_handler(characteristic, data):
    """通知ハンドラー"""
    global received_response
    message = data.decode('utf-8')
    print(f"\n[NOTIFICATION] 受信: {message}")
    received_response = message
    response_event.set()

async def scan_for_bikeclock(timeout=10.0):
    """BikeClockデバイスをスキャン"""
    print_header("BLEデバイススキャン")
    print(f" '{DEVICE_NAME_PREFIX}' で始まるデバイスを探しています...")

    devices = await BleakScanner.discover(timeout=timeout)
    bikeclock_devices = [
        d for d in devices
        if d.name and d.name.startswith(DEVICE_NAME_PREFIX)
    ]

    if not bikeclock_devices:
        print(f"❌ '{DEVICE_NAME_PREFIX}' デバイスが見つかりませんでした")
        return None

    device = bikeclock_devices[0]
    print(f"✅ デバイス見つかりました！")
    print(f"   名前: {device.name}")
    print(f"   アドレス: {device.address}")
    # RSSIはbleakのバージョンによって取得方法が異なるため省略

    return device

async def connect_and_discover(device):
    """接続してサービス/キャラクタリスティックを発見"""
    print_header("接続とサービス発見")

    async with BleakClient(device.address) as client:
        print(f"🔄 {device.name} に接続中...")

        try:
            await client.connect()
            print(f"✅ 接続成功！")
            # Wait for service discovery to complete
            print(f"⏳ サービス発見を待機中... (3秒)")
            await asyncio.sleep(3)
        except Exception as e:
            print(f"❌ 接続失敗: {e}")
            return False

        # サービスを取得
        print(f"\n📋 サービス一覧:")
        services = client.services
        for service in services:
            print(f"   Service: {service.uuid}")
            if str(service.uuid).lower() == SERVICE_UUID.lower():
                print(f"      👈 BikeClockサービスです！")

            # キャラクタリスティックを表示
            for char in service.characteristics:
                props = ", ".join(char.properties)
                print(f"      Characteristic: {char.uuid}")
                print(f"         Properties: {props}")

        # BikeClockサービスを確認
        bikeclock_service = services.get_service(SERVICE_UUID)
        if not bikeclock_service:
            print(f"\n❌ BikeClockサービスが見つかりません: {SERVICE_UUID}")
            return False

        print(f"\n✅ BikeClockサービス見つかりました！")

        # 必要なキャラクタリスティックを確認
        print(f"\n📋 必要なキャラクタリスティック確認:")
        command_char = bikeclock_service.get_characteristic(COMMAND_UUID)
        response_char = bikeclock_service.get_characteristic(RESPONSE_UUID)

        print(f"   Command Characteristic ({COMMAND_UUID}):")
        print(f"      {'✅ 見つかりました' if command_char else '❌ 見つかりません'}")
        if command_char:
            print(f"      Properties: {', '.join(command_char.properties)}")

        print(f"   Response Characteristic ({RESPONSE_UUID}):")
        print(f"      {'✅ 見つかりました' if response_char else '❌ 見つかりません'}")
        if response_char:
            print(f"      Properties: {', '.join(response_char.properties)}")

        if not command_char:
            print(f"\n❌ Command Characteristicが見つかりません")
            return False

        if not response_char:
            print(f"\n❌ Response Characteristicが見つかりません")
            return False

        # Skip notification setup - use read instead
        print(f"\n⚠️  通知はスキップ（Read + Writeモード）")

        # 時刻同期コマンドを送信してテスト
        print_header("時刻同期テスト")

        # テスト用時刻が設定されている場合
        if test_times:
            print(f"📋 テストモード: {len(test_times)}件の時刻を送信します\n")

            for i, (desc, ts) in enumerate(test_times, 1):
                # JSTタイムスタンプを表示
                # ts は「JSTとしてのUnix timestamp」（+9時間された値）
                # 表示時は-9時間してUTCベースに戻してからJSTとして表示する
                utc_timestamp = ts - 32400  # -9時間
                dt_utc = datetime.fromtimestamp(utc_timestamp, timezone.utc)
                dt_jst = dt_utc + timedelta(hours=9)

                command = f"SET:time:{ts}"
                print(f"[{i}/{len(test_times)}] {desc}")
                print(f"   タイムスタンプ: {ts}")
                print(f"   JST時刻: {dt_jst.strftime('%Y-%m-%d %H:%M:%S')}")
                print(f"   曜日: {['月', '火', '水', '木', '金', '土', '日'][dt_jst.weekday()]}曜日")
                print(f"   📤 コマンド送信: {command}")

                try:
                    await client.write_gatt_char(command_char, command.encode('utf-8'))
                    print(f"   ✅ コマンド送信成功")
                except Exception as e:
                    print(f"   ❌ コマンド送信失敗: {e}")
                    continue

                # レスポンス読み取り
                try:
                    response_data = await client.read_gatt_char(response_char)
                    response_message = response_data.decode('utf-8').rstrip('\x00')
                    print(f"   レスポンス: {response_message}")
                    if "OK" in response_message:
                        print(f"   ✅ 時刻同期成功！")
                    else:
                        print(f"   ⚠️  時刻同期失敗")
                except Exception as e:
                    print(f"   ❌ レスポンス読み取り失敗: {e}")

                print()  # 空行

                # 連続送信の場合は少し待機
                if i < len(test_times):
                    await asyncio.sleep(0.5)

            print(f"🎉 全テスト完了！")
        else:
            # 通常モード：現在時刻を送信
            current_time = int(time.time())
            command = f"SET:time:{current_time}"
            print(f"📤 コマンド送信: {command}")
            print(f"   現在時刻: {datetime.fromtimestamp(current_time).strftime('%Y-%m-%d %H:%M:%S')}")

            try:
                await client.write_gatt_char(command_char, command.encode('utf-8'))
                print(f"✅ コマンド送信成功")
            except Exception as e:
                print(f"❌ コマンド送信失敗: {e}")
                return False

            # Read response from characteristic
            print(f"\n⏳ レスポンス読み取り中...")
            try:
                response_data = await client.read_gatt_char(response_char)
                response_message = response_data.decode('utf-8').rstrip('\x00')
                print(f"✅ レスポンス読み取り成功: {response_message}")
                if "OK" in response_message:
                    print(f"🎉 時刻同期成功！")
                else:
                    print(f"⚠️  時刻同期失敗: {response_message}")
            except Exception as e:
                print(f"❌ レスポンス読み取り失敗: {e}")

        # 少し待機してから切断
        print(f"\n⏳ 3秒間接続を維持...")
        await asyncio.sleep(3)

        print(f"\n✅ テスト完了！")

        return True

async def main():
    """メイン関数"""
    global test_times

    parser = argparse.ArgumentParser(
        description="BikeClock BLE接続テストツール",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
使用例:
  python bikeclock_ble_test.py                    # 通常モード（現在時刻を送信）
  python bikeclock_ble_test.py --midnight-test    # 深夜0時境界テスト
  python bikeclock_ble_test.py --time "2026-04-27 00:00:00"  # 特定時刻設定
  python bikeclock_ble_test.py --time "2026-04-27 00:00:00" --time "2026-04-27 01:00:00"  # 複数時刻
        """
    )

    parser.add_argument(
        "--midnight-test",
        action="store_true",
        help="深夜0時の境界テスト（23:59, 00:00, 00:01, 01:00, 08:59, 09:00）"
    )

    parser.add_argument(
        "--time",
        action="append",
        help="テスト用時刻 (形式: 'YYYY-MM-DD HH:MM:SS')。複数指定可"
    )

    args = parser.parse_args()

    # テスト用時刻を設定
    if args.midnight_test:
        print_header("深夜0時境界テストモード")
        test_times = get_midnight_test_times()
        print(f"✅ {len(test_times)}件のテストケースを生成しました\n")

    if args.time:
        print_header("指定時刻テストモード")
        for time_str in args.time:
            ts = datetime_to_jst_timestamp(time_str)
            if ts:
                # ts はJST補正済み（+9時間）なので、-9時間してUTCベースに戻す
                utc_timestamp = ts - 32400
                dt_utc = datetime.fromtimestamp(utc_timestamp, timezone.utc)
                dt_jst = dt_utc + timedelta(hours=9)
                desc = f"{time_str} ({dt_jst.strftime('%Y-%m-%d %H:%M:%S')} JST)"
                test_times.append((desc, ts))
                print(f"✅ 追加: {desc}")

    print_header("BikeClock BLE接続テスト")
    print(f"サービスUUID: {SERVICE_UUID}")
    print(f"Command UUID: {COMMAND_UUID}")
    print(f"Response UUID: {RESPONSE_UUID}")

    if test_times:
        print(f"\n📋 テストモード: {len(test_times)}件の時刻を送信します")

    # デバイスをスキャン
    device = await scan_for_bikeclock()
    if not device:
        print(f"\n❌ テスト失敗: デバイスが見つかりません")
        return

    # 接続してテスト
    success = await connect_and_discover(device)

    if success:
        print(f"\n🎉 全てのテストが成功しました！")
    else:
        print(f"\n❌ テスト失敗しました")

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print(f"\n\n⚠️  中断されました")
    except Exception as e:
        print(f"\n\n❌ エラー: {e}")
        import traceback
        traceback.print_exc()
