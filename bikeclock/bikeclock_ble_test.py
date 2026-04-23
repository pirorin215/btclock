#!/usr/bin/env python3
"""
BikeClock BLE接続テストツール

BikeClockデバイスへのBLE接続とサービス/キャラクタリスティックの確認、
時刻同期コマンドのテストを行います。
"""

import asyncio
import time
from datetime import datetime
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
    print_header("BikeClock BLE接続テスト")
    print(f"サービスUUID: {SERVICE_UUID}")
    print(f"Command UUID: {COMMAND_UUID}")
    print(f"Response UUID: {RESPONSE_UUID}")

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
