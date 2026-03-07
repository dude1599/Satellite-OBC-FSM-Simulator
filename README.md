# 🛰️ Satellite OBC Software Simulator
**임베디드 시스템 설계 원칙을 적용한 위성 탑재 소프트웨어(OBC) 시뮬레이터**

이 프로젝트는 위성의 상태 관리(FSM)와 지상국 간의 신뢰성 있는 통신 프로토콜을 구현한 시뮬레이터입니다.  
단순한 데이터 송수신을 넘어, **시스템 신뢰성(Reliability)** 과 **자율 방어 로직(Fault Tolerance)** 에 초점을 맞추어 설계했습니다.

## 🛠️ Key Technical Features
* **Finite State Machine (FSM):** 위성의 운용 모드(BOOT, NOMINAL, SAFE, EMERGENCY)를 체계적으로 관리.
* **Custom Communication Protocol:** 비트 단위의 데이터 직렬화 및 CRC-16-XMODEM 검증을 통한 통신 무결성 확보.
* **Autonomous Protection:** 지상국 명령 없이도 배터리 고갈 및 임계 온도 도달 시 스스로 시스템을 보호하는 자율 방어 물리 엔진 적용.
* **Multithreaded Task Scheduling:** 명령 수신과 실시간 상태 전송(Telemetry)을 독립적인 스레드에서 실시간 비동기 처리.

---

## 📊 Packet Structure (UDP / Big-Endian)

본 프로젝트는 지상국과 위성 간의 통신을 위해 커스텀 바이너리 프로토콜을 사용합니다. 모든 데이터는 네트워크 바이트 순서인 **Big-Endian**으로 직렬화됩니다.



### 1. Common Header (8 Bytes)
모든 패킷(TC, TM, ACK, NAK)의 앞단에 공통으로 붙는 헤더 영역입니다.

| Offset | Field | Size | Data Type | Description |
| :--- | :--- | :--- | :--- | :--- |
| 0 | **Magic Number** | 2 bytes | `short` | `0xCAFE` (위성 통신 프로토콜 식별자) |
| 2 | **Version** | 1 byte | `byte` | `0x01` (프로토콜 호환성 버전) |
| 3 | **Type** | 1 byte | `byte` | `0x05`(TM), `0x10`(SAFE명령), `0x20`(NOMINAL명령), `0x00`(ACK), `0xFF`(NAK) |
| 4 | **Sequence** | 2 bytes | `short` | 패킷 추적 및 중복 방지용 일련번호 |
| 6 | **Payload Length**| 2 bytes | `short` | 헤더 뒤에 이어지는 Payload의 바이트 길이 (TM의 경우 `5`) |

### 2. Telemetry (TM) Payload (5 Bytes)
Type이 `0x05` (상태 데이터)일 경우, 헤더 뒤에 붙는 가변 데이터 영역입니다.

| Offset | Field | Size | Data Type | Description |
| :--- | :--- | :--- | :--- | :--- |
| 8 | **Battery** | 2 bytes | `unsigned short`| 위성 배터리 잔량 (0 ~ 100%) |
| 10 | **Temperature** | 2 bytes | `signed short` | 위성 현재 온도 (-10°C ~ 60°C) |
| 12 | **Current Mode** | 1 byte | `byte` | 현재 위성 모드 (`0x10`: SAFE, `0x20`: NOMINAL) |

### 3. Tail (2 Bytes)
패킷의 가장 마지막에 붙어 데이터의 변조 여부를 확인합니다.

| Offset | Field | Size | Data Type | Description |
| :--- | :--- | :--- | :--- | :--- |
| 끝단 | **CRC-16** | 2 bytes | `short` | `XMODEM (0x1021)` 다항식을 이용한 패킷 무결성 검증 지문 |
