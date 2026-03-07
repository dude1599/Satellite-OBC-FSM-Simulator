🛰️ Satellite OBC Software Simulator
==임베디드 시스템 설계 원칙을 적용한 위성 탑재 소프트웨어(OBC) 시뮬레이터==

이 프로젝트는 위성의 상태 관리(FSM)와 지상국 간의 신뢰성 있는 통신 프로토콜을 구현한 시뮬레이터입니다. 
단순한 데이터 송수신을 넘어, **시스템 신뢰성(Reliability)**과 **자율 방어 로직(Fault Tolerance)**에 초점을 맞추어 설계되었습니다.

🛠️ Key Technical Features
Finite State Machine (FSM): 위성의 운용 모드(BOOT, NOMINAL, SAFE, EMERGENCY)를 체계적으로 관리.

Custom Communication Protocol: 비트 단위의 데이터 직렬화 및 CRC-16-XMODEM 검증을 통한 통신 무결성 확보.

Autonomous Protection: 지상국 명령 없이도 임계 온도 도달 시 스스로 시스템을 보호하는 자율 방어 로직.

Multithreaded Task Scheduling: 명령 수신과 실시간 상태 전송(Telemetry)을 독립적인 스레드에서 실시간 처리.
