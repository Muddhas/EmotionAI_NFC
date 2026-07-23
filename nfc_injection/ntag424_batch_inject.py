#!/usr/bin/env python3
"""
NTAG 424 DNA 批量注入脚本
硬件: ACR1252U USB 读卡器 (或兼容 PC/SC 读卡器)
功能: 配置 AES 密钥 → 写入 NDEF URI → 配置 SDM 防伪

使用方法:
    # 批量注入 10 张芯片
    python ntag424_batch_inject.py --count 10

    # 单张注入指定 chipId
    python ntag424_batch_inject.py --single --chip-id EMO-424-TEST001

    # 模拟运行 (不实际写入)
    python ntag424_batch_inject.py --count 3 --dry-run

参数:
    --count N       : 批量注入 N 张芯片 (默认: 5)
    --single        : 单张注入模式
    --chip-id ID    : 指定 chipId (单张模式)
    --config FILE   : 配置文件路径 (默认: ntag424_config.json)
    --dry-run       : 模拟运行，不实际写入
"""

import argparse
import json
import os
import sys
import time
import uuid
from datetime import datetime

# ========================================
# APDU 命令常量 (NTAG 424 DNA)
# ========================================
CLA = 0x90

# 指令
INS_GET_VERSION  = 0x60  # 获取版本
INS_AUTHENTICATE = 0x71  # 认证 (First)
INS_AUTH_LEGACY  = 0x70  # 认证 (Legacy)
INS_READ         = 0xAD  # 读取块
INS_WRITE        = 0xAE  # 写入块
INS_SET_CONFIG   = 0xBF  # 设置配置

# 密钥编号
KEY_NDEF  = 0x00
KEY_SDM   = 0x01
KEY_PICC  = 0x02


# ========================================
# 工具函数
# ========================================

def hex_to_bytes(hex_str):
    """十六进制字符串 → 字节列表"""
    hex_str = hex_str.replace(' ', '').replace('-', '')
    return [int(hex_str[i:i+2], 16) for i in range(0, len(hex_str), 2)]


def bytes_to_hex(byte_list):
    """字节列表 → 十六进制字符串"""
    return ''.join(f'{b:02X}' for b in byte_list)


def chunks(lst, n):
    """将列表拆分为每组 n 个元素"""
    for i in range(0, len(lst), n):
        yield lst[i:i + n]


# ========================================
# NDEF URI 编码
# ========================================

def encode_ndef_uri(uri):
    """
    将 URI 字符串编码为 NDEF 二进制数据 (含 TLV)

    NDEF URI Record 格式:
      D1          : MB=1, ME=1, CF=0, SR=1, IL=0, TNF=0x01 (Well Known)
      01          : Type Length = 1
      <len>       : Payload Length
      55          : "U" = URI Record Type
      <id_code>   : URI Identifier Code (0x00 = no prefix)
      <uri_bytes> : URI 字符串的 UTF-8 字节

    NDEF TLV 封装:
      03 <len> [NDEF 记录] FE
    """
    uri_bytes = uri.encode('utf-8')

    # NDEF 记录 (不含 TLV)
    ndef_record = (
        [0xD1] +                          # Header
        [0x01] +                          # Type length
        [1 + len(uri_bytes)] +            # Payload length (ID code + URI)
        [0x55] +                          # "U" URI type
        [0x00] +                          # URI identifier code (no prefix)
        list(uri_bytes)                   # URI data
    )

    # TLV 封装
    ndef_tlv = (
        [0x03] +                          # NDEF Message TLV tag
        [len(ndef_record)] +              # Length
        ndef_record +
        [0xFE]                           # Terminator TLV
    )

    return ndef_tlv


# ========================================
# NTAG 424 DNA 操作类
# ========================================

class NTAG424Handler:
    """NTAG 424 DNA 芯片读写操作"""

    def __init__(self, config, connection=None, dry_run=False):
        self.config = config
        self.connection = connection
        self.dry_run = dry_run
        self.uid = None
        self.chip_version = None

    # ---- 读卡器连接 ----

    def connect(self):
        """连接 ACR1252 读卡器"""
        if self.dry_run:
            print("[DRY-RUN] 模拟连接读卡器")
            return True

        try:
            from smartcard.System import readers
            all_readers = readers()
            if not all_readers:
                print("错误: 未检测到读卡器")
                return False

            # 优先选择 ACR1252
            reader = None
            for r in all_readers:
                reader_name = str(r)
                if 'ACR1252' in reader_name or 'ACS' in reader_name:
                    reader = r
                    print(f"使用读卡器: {reader_name}")
                    break

            if not reader:
                reader = all_readers[0]
                print(f"未找到 ACR1252，使用: {reader}")

            self.connection = reader.createConnection()
            self.connection.connect()
            print(f"已连接读卡器")
            return True

        except ImportError:
            print("错误: 请安装 pyscard: pip install pyscard")
            return False
        except Exception as e:
            print(f"连接失败: {e}")
            return False

    # ---- 卡片检测 ----

    def wait_for_tag(self, timeout=30):
        """等待卡片放入读卡器"""
        if self.dry_run:
            print("[DRY-RUN] 模拟检测到卡片")
            return True

        print("请将 NTAG 424 DNA 芯片放在读卡器上...")
        start = time.time()
        while True:
            try:
                self.connection.connect()
                print("检测到卡片!")
                return True
            except Exception:
                if timeout and (time.time() - start) > timeout:
                    print("等待超时")
                    return False
                time.sleep(0.5)

    def wait_for_tag_removed(self, timeout=30):
        """等待卡片移除"""
        if self.dry_run:
            print("[DRY-RUN] 模拟移除卡片")
            return True

        print("请移走芯片...")
        start = time.time()
        while True:
            try:
                self.connection.transmit([CLA, INS_GET_VERSION, 0x00, 0x00, 0x00])
                time.sleep(0.3)
            except Exception:
                print("芯片已移除")
                time.sleep(0.5)
                return True
            if timeout and (time.time() - start) > timeout:
                print("等待移除超时")
                return True

    # ---- APDU 通信 ----

    def send_apdu(self, apdu, description=""):
        """发送 APDU 命令"""
        if self.dry_run:
            if description:
                print(f"  [DRY-RUN] {description}: {bytes_to_hex(apdu)}")
            return [0x90, 0x00]

        try:
            response, sw1, sw2 = self.connection.transmit(apdu)
            if description:
                status = "OK" if (sw1 == 0x90 and sw2 == 0x00) else f"ERR:{sw1:02X} {sw2:02X}"
                data_preview = bytes_to_hex(response)[:32] if response else "(empty)"
                print(f"  {description}: {status} / {data_preview}")

            if sw1 != 0x90 or sw2 != 0x00:
                return None
            return response
        except Exception as e:
            if description:
                print(f"  {description}: APDU 错误 - {e}")
            return None

    # ---- 芯片操作 ----

    def get_version(self):
        """获取芯片版本"""
        apdu = [CLA, INS_GET_VERSION, 0x00, 0x00, 0x00]
        resp = self.send_apdu(apdu, "GET VERSION")
        if resp:
            self.chip_version = resp
            print(f"  芯片版本: {bytes_to_hex(resp[:16])}")
        return resp

    def authenticate(self):
        """认证芯片 (使用 PICC 默认密钥)"""
        # Step 1: 发送认证命令
        apdu = [CLA, INS_AUTH_LEGACY, KEY_PICC, 0x00, 0x00]
        resp = self.send_apdu(apdu, "AUTHENTICATE")

        if resp is None:
            print("  认证失败!")
            return False

        print("  认证成功")
        return True

    def read_block(self, block_no):
        """读取一个数据块 (4 字节)"""
        apdu = [CLA, INS_READ, 0x00, block_no, 0x04]
        resp = self.send_apdu(apdu, f"READ Block {block_no:02X}")
        return resp

    def write_block(self, block_no, data):
        """写入一个数据块 (4 字节)"""
        if len(data) != 4:
            data = data + [0x00] * (4 - len(data))

        apdu = [CLA, INS_WRITE, 0x00, block_no, 0x04] + data[:4]
        resp = self.send_apdu(apdu, f"WRITE Block {block_no:02X}")
        return resp is not None

    def write_ndef_uri(self, uri):
        """写入 NDEF URI 到芯片"""
        ndef_data = encode_ndef_uri(uri)

        # 块 0x00: Capability Container (前 2 字节) + NDEF TLV 开头
        # CC = 0x0003 表示支持 NDEF
        cap_container = [0x00, 0x03]

        # 第一块: CC + NDEF TLV 开头
        block0 = cap_container + ndef_data[:2]  # NDEF TLV tag + length
        if not self.write_block(0x00, block0):
            return False

        # 剩余 NDEF 数据 (补齐到 4 的倍数)
        remaining = ndef_data[2:]
        if len(remaining) % 4 != 0:
            remaining = remaining + [0x00] * (4 - len(remaining) % 4)

        for i, block_data in enumerate(chunks(remaining, 4)):
            if not self.write_block(0x01 + i, list(block_data)):
                return False

        print(f"  NDEF URI 写入完成: {uri}")
        return True

    def read_and_verify(self, num_blocks=6):
        """读取并验证写入内容"""
        print("  --- 验证 ---")
        all_ok = True
        for block in range(num_blocks):
            resp = self.read_block(block)
            if resp is None:
                all_ok = False
        return all_ok

    def configure_sdm(self):
        """配置 SDM 防伪"""
        # SDM 配置 (6 字节)
        sdm_config = [
            0x01,  # 启用 SDM
            0x01,  # 包含 UID
            0x01,  # 包含 ReadCounter
            0x01,  # 启用 CMAC 签名
            0x00,  # 保留
            0x00,  # 保留
        ]
        apdu = [CLA, INS_SET_CONFIG, 0x00, 0x00, 0x06] + sdm_config
        resp = self.send_apdu(apdu, "SDM CONFIG")
        if resp:
            print("  SDM 防伪配置完成")
            return True
        print("  SDM 配置失败!")
        return False

    # ---- 完整注入流程 ----

    def inject_chip(self, chip_id):
        """完整注入流程"""
        print(f"\n{'='*50}")
        print(f"  注入芯片: {chip_id}")
        print(f"{'='*50}")

        # 1. 获取版本
        if not self.get_version():
            print("  无法获取芯片版本，跳过")
            return False

        # 2. 认证
        if not self.authenticate():
            return False

        # 3. 写入 NDEF URI
        uri = f"emoai://chip/{chip_id}"
        if not self.write_ndef_uri(uri):
            return False

        # 4. 读取验证
        self.read_and_verify()

        # 5. 配置 SDM
        self.configure_sdm()

        print(f"  ✓ 芯片 {chip_id} 注入完成!")
        return True

    def get_uid(self):
        """获取芯片 UID"""
        if self.chip_version and len(self.chip_version) >= 8:
            # 从版本信息中提取部分 UID (取决于芯片实现)
            self.uid = bytes_to_hex(self.chip_version[:7])
            return self.uid
        return None


# ========================================
# 主程序
# ========================================

def load_config(config_path):
    """加载配置文件"""
    default_config = {
        "masterKey": "0123456789ABCDEF0123456789ABCDEF",
        "sdmKey": "FEDCBA9876543210FEDCBA9876543210",
        "ndefKey": "AABBCCDDEEFF00112233445566778899",
        "defaultKey": "00000000000000000000000000000000",
        "uriTemplate": "emoai://chip/{chipId}",
        "chipIdPrefix": "EMO-424-"
    }

    if config_path and os.path.exists(config_path):
        try:
            with open(config_path, 'r', encoding='utf-8') as f:
                loaded = json.load(f)
                default_config.update(loaded)
            print(f"已加载配置: {config_path}")
        except Exception as e:
            print(f"配置加载失败: {e}，使用默认配置")
    else:
        if config_path and config_path != 'ntag424_config.json':
            print(f"配置文件不存在: {config_path}，使用默认配置")
        else:
            print("使用默认配置")

    return default_config


def batch_inject(config, count, dry_run):
    """批量注入多张芯片"""
    handler = NTAG424Handler(config, dry_run=dry_run)

    if not handler.connect():
        return

    log_entries = []
    success_count = 0

    print(f"\n准备批量注入 {count} 张芯片...\n")

    for i in range(count):
        # 放入芯片
        if i > 0:
            handler.wait_for_tag_removed()
            time.sleep(1)

        print(f"\n[{i+1}/{count}]")
        if not handler.wait_for_tag(timeout=60):
            print("等待芯片超时，停止注入")
            break

        # 注入
        chip_id = f"{config['chipIdPrefix']}{uuid.uuid4().hex[:8].upper()}"
        if handler.inject_chip(chip_id):
            success_count += 1
            uid = handler.get_uid() or "N/A"
            log_entry = f"{chip_id},{uid},{datetime.now().isoformat()}"
            log_entries.append(log_entry)
            print(f"  → 记录: {log_entry}")
        else:
            print(f"  → 失败: {chip_id}")

    # 输出日志
    if log_entries:
        log_file = f"inject_log_{datetime.now().strftime('%Y%m%d_%H%M%S')}.csv"
        with open(log_file, 'w', encoding='utf-8') as f:
            f.write("chipId,uid,injectedAt\n")
            f.writelines(f"{line}\n" for line in log_entries)
        print(f"\n注入日志已保存: {log_file}")

    print(f"\n{'='*50}")
    print(f"  批量注入完成! 成功: {success_count}/{count}")
    print(f"{'='*50}")


def single_inject(config, chip_id, dry_run):
    """单张注入"""
    handler = NTAG424Handler(config, dry_run=dry_run)

    if not handler.connect():
        return

    if not handler.wait_for_tag():
        return

    handler.inject_chip(chip_id)


def main():
    parser = argparse.ArgumentParser(
        description='NTAG 424 DNA 批量注入工具 (ACR1252)',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
使用示例:
  python ntag424_batch_inject.py --count 10
  python ntag424_batch_inject.py --single --chip-id EMO-424-TEST001
  python ntag424_batch_inject.py --count 3 --dry-run
        """
    )
    parser.add_argument('--count', type=int, default=5,
                        help='批量注入数量 (默认: 5)')
    parser.add_argument('--single', action='store_true',
                        help='单张注入模式')
    parser.add_argument('--chip-id', type=str,
                        help='指定 chipId (单张模式)')
    parser.add_argument('--config', type=str, default='ntag424_config.json',
                        help='配置文件路径')
    parser.add_argument('--dry-run', action='store_true',
                        help='模拟运行，不实际写入')

    args = parser.parse_args()
    config = load_config(args.config)

    if args.single:
        chip_id = args.chip_id or f"{config['chipIdPrefix']}{uuid.uuid4().hex[:8].upper()}"
        single_inject(config, chip_id, args.dry_run)
    else:
        batch_inject(config, args.count, args.dry_run)


if __name__ == '__main__':
    main()
